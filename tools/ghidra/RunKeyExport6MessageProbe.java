import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Probe key.drv export slot 6 and record its module-0x0f message requests. */
public class RunKeyExport6MessageProbe extends GhidraScript {
    private static final long ENTRY = 0x29009654L;
    private static final long SERVICE_TRAP = 0xbfc10004L;
    private static final long STOP = 0x700300L;
    private static final long STACK = 0x10003f00L;
    private static final long KEY_RAM = 0x9fc14000L;
    private static final int KEY_RAM_SIZE = 0x1000;
    private static final long STATUS_RAM = 0x9fc09000L;
    private static final int STATUS_RAM_SIZE = 0x1000;
    private static final long STATUS_BYTE = 0x9fc09f71L;
    private static final long KEY_STATE_BYTE = 0x9fc1437dL;
    private static final long MMIO = 0xc0000000L;
    private static final int MMIO_SIZE = 0x100;
    private static final long INITIAL_MMIO_WORD = 0xa5a50001L;
    private static final int INITIAL_STATUS_BYTE = 0x01;
    private static final long RESIDENT_FILE_OFFSET = 0x200L;
    private static final int RESIDENT_SIZE = 0xa00;
    private static final long BANK_FILE_OFFSET = 0x1000L;
    private static final long BANK_BASE = 0x29009400L;
    private static final int BANK_SIZE = 0x600;
    private static final String EXPECTED_SHA256 =
        "858d56ff19106fccb75e35552c9cecca99dce0579c7daac5dce376d742f67ba1";

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private int readByte(EmulatorHelper emulator, long location) throws Exception {
        return emulator.readMemory(address(location), 1)[0] & 0xff;
    }

    private long readWord(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private long fileWord(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
            | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16)
            | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private void writeWord(EmulatorHelper emulator, long location, long value)
            throws Exception {
        emulator.writeMemory(address(location), new byte[] {
            (byte)value,
            (byte)(value >>> 8),
            (byte)(value >>> 16),
            (byte)(value >>> 24)
        });
    }

    private MemoryBlock mapBlock(
        Memory memory,
        String name,
        long start,
        byte[] data,
        boolean executable
    ) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            name,
            address(start),
            new ByteArrayInputStream(data),
            data.length,
            monitor,
            false
        );
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(executable);
        return block;
    }

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size
    ) throws Exception {
        MemoryBlock block = mapBlock(memory, name, start, new byte[size], true);
        block.setExecute(true);
    }

    private void setMips16Context(long start, int size) throws Exception {
        Register isaMode = currentProgram.getRegister("ISA_MODE");
        currentProgram.getProgramContext().setValue(
            isaMode,
            address(start),
            address(start + size - 1),
            BigInteger.ONE
        );
    }

    private void loadDriverSegments(byte[] driver) throws Exception {
        Memory memory = currentProgram.getMemory();
        for (MemoryBlock block : memory.getBlocks()) {
            memory.removeBlock(block, monitor);
        }

        byte[] resident = Arrays.copyOfRange(
            driver,
            (int)RESIDENT_FILE_OFFSET,
            (int)(RESIDENT_FILE_OFFSET + RESIDENT_SIZE)
        );
        byte[] bank = Arrays.copyOfRange(
            driver,
            (int)BANK_FILE_OFFSET,
            (int)(BANK_FILE_OFFSET + BANK_SIZE)
        );
        mapBlock(memory, "key_export6_resident", 0xbfc10000L, resident, true);
        mapBlock(memory, "key_export6_bank", BANK_BASE, bank, true);
        mapWritableRegion(memory, "key_export6_key_ram", KEY_RAM, KEY_RAM_SIZE);
        mapWritableRegion(memory, "key_export6_status_ram", STATUS_RAM, STATUS_RAM_SIZE);
        mapWritableRegion(memory, "key_export6_mmio", MMIO, MMIO_SIZE);
        mapWritableRegion(memory, "key_export6_stack", STACK - 0x1000L, 0x2000);
        setMips16Context(0xbfc10000L, RESIDENT_SIZE);
        setMips16Context(BANK_BASE, BANK_SIZE);

        byte[] mappedEntry = new byte[0x0c];
        memory.getBytes(address(ENTRY), mappedEntry);
        require(Arrays.equals(mappedEntry,
                Arrays.copyOfRange(driver, 0x1254, 0x1260)),
            "loaded export-slot-6 bytes do not match file offset 0x1254");
    }

    private String runCase(
        int inputMask,
        long statusReturn,
        int initialStateByte
    ) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> serviceCalls = new ArrayList<>();
        int statusCallCount = 0;
        int messageCallCount = 0;
        int messageId = -1;
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(
                currentProgram.getRegister("ISA_MODE"),
                BigInteger.ONE
            );
            emulator.writeMemory(address(KEY_RAM), new byte[KEY_RAM_SIZE]);
            emulator.writeMemory(
                address(KEY_STATE_BYTE),
                new byte[] {(byte)initialStateByte}
            );
            emulator.writeMemory(address(STATUS_RAM), new byte[STATUS_RAM_SIZE]);
            emulator.writeMemory(address(MMIO), new byte[MMIO_SIZE]);
            emulator.writeMemory(address(STACK - 0x1000L), new byte[0x2000]);
            writeWord(emulator, MMIO, INITIAL_MMIO_WORD);
            emulator.writeMemory(
                address(STATUS_BYTE),
                new byte[] {(byte)INITIAL_STATUS_BYTE}
            );
            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("a0", inputMask);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 6);
            emulator.writeRegister("pc", ENTRY);

            Register isaMode = currentProgram.getRegister("ISA_MODE");
            int steps;
            for (steps = 0; steps < 5000; steps++) {
                long pc = register(emulator, "pc") & 0xffffffffL;
                if ((pc & ~1L) == STOP) {
                    break;
                }

                emulator.setContextRegister(isaMode, BigInteger.ONE);

                if ((pc & ~1L) == SERVICE_TRAP) {
                    long v1AtTrap = register(emulator, "v1");
                    long a0 = register(emulator, "a0");
                    long a1 = register(emulator, "a1");
                    long a2 = register(emulator, "a2");
                    long a3 = register(emulator, "a3");
                    long ra = register(emulator, "ra");
                    require((ra & 1L) == 1L,
                        String.format("service return address lacks MIPS16 tag: %08x", ra));
                    if (a3 == 9) {
                        require(a0 == 1 && a1 == 0 && a2 == 0,
                            String.format("unexpected status-call ABI %08x/%08x/%08x",
                                a0, a1, a2));
                        statusCallCount++;
                        serviceCalls.add(String.format(
                            "v1=%08x op=9 args=%08x/%08x/%08x/%08x ra=%08x",
                            v1AtTrap, a0, a1, a2, a3, ra
                        ));
                        emulator.writeRegister("v0", statusReturn);
                    }
                    else if (a3 == 24) {
                        require(a0 == 5 && a1 == STACK - 40 && a2 == 0,
                            String.format("unexpected message-call ABI %08x/%08x/%08x",
                                a0, a1, a2));
                        messageCallCount++;
                        messageId = (int)readWord(emulator, a1) & 0xffff;
                        serviceCalls.add(String.format(
                            "v1=%08x op=24 args=%08x/%08x/%08x/%08x payload=%04x ra=%08x",
                            v1AtTrap, a0, a1, a2, a3, messageId, ra
                        ));
                        emulator.writeRegister("v0", 0);
                    }
                    else {
                        throw new Exception(String.format(
                            "unexpected module-0x0f selector %d", a3));
                    }

                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "key slot-6 stopped at 0x%08x: %s",
                        pc,
                        emulator.getLastError()
                    ));
                }
            }

            require(steps < 5000, "key slot-6 exceeded instruction limit");
            boolean statusQueried = (inputMask & 0x80) != 0
                || ((inputMask & 0x01) != 0 && initialStateByte >= 1);
            require(statusCallCount == (statusQueried ? 1 : 0),
                "status-call count mismatch for mask " + Integer.toHexString(inputMask));
            boolean messageExpected = statusQueried
                && (inputMask & 0x02) != 0
                && statusReturn == 0;
            int expectedMessageId = messageExpected ? 0xb0 : -1;
            require(messageCallCount == (messageExpected ? 1 : 0),
                String.format("message-call count mismatch for mask=%02x status=%08x",
                    inputMask, statusReturn));
            require(messageId == expectedMessageId,
                String.format("message id=%04x expected=%04x for mask=%02x status=%08x",
                    messageId, expectedMessageId, inputMask, statusReturn));

            long actualMmio = readWord(emulator, MMIO);
            int actualStatusByte = readByte(emulator, STATUS_BYTE);
            long expectedMmio = messageExpected
                ? (INITIAL_MMIO_WORD & ~0x08L) | 0x08L
                : INITIAL_MMIO_WORD;
            int expectedStatusByte = messageExpected
                ? INITIAL_STATUS_BYTE | 0x80
                : INITIAL_STATUS_BYTE;
            require(actualMmio == expectedMmio,
                String.format("MMIO effect=%08x expected=%08x", actualMmio, expectedMmio));
            require(actualStatusByte == expectedStatusByte,
                String.format("status byte=%02x expected=%02x",
                    actualStatusByte, expectedStatusByte));

            return String.format(
                "mask=%02x state-byte-start=%02x status-return=%08x steps=%d "
                    + "status-calls=%d message-calls=%d "
                    + "message=%s mmio=%08x>%08x status-byte=%02x calls=%s",
                inputMask,
                initialStateByte,
                statusReturn,
                steps,
                statusCallCount,
                messageCallCount,
                messageId < 0 ? "none" : String.format("%04x", messageId),
                INITIAL_MMIO_WORD,
                actualMmio,
                actualStatusByte,
                serviceCalls.isEmpty() ? "none" : String.join(";", serviceCalls)
            );
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKeyExport6MessageProbe.java KEY.DRV");
        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);
        require(driver.length == 0x1600, "unexpected key.drv size");
        require(fileWord(driver, 0x0c18) == 0x29009655L,
            "export-table slot 6 does not point to 0x29009654");
        require(fileWord(driver, 0x1248) == 0xbfc10005L
                && fileWord(driver, 0x124c) == MMIO
                && fileWord(driver, 0x1250) == STATUS_BYTE,
            "slot-6 service/MMIO/RAM pool words differ from the expected image");

        loadDriverSegments(driver);
        int[] masks = {0, 1, 2, 3, 0x80, 0x81, 0x82, 0x83};
        long[] statusReturns = {0, 1};
        for (int mask : masks) {
            for (long statusReturn : statusReturns) {
                println("KEY EXPORT-6 EFFECT " + runCase(mask, statusReturn, 0));
            }
        }
        for (int repeatState : new int[] {1, 2}) {
            for (long statusReturn : statusReturns) {
                println("KEY EXPORT-6 EFFECT " + runCase(1, statusReturn, repeatState));
            }
        }
        println("KEY EXPORT-6 EFFECT PROBE COMPLETE entry=0x29009654 "
            + "cases=20 status-service=synthetic message-service=synthetic mmio=plain-ram");
    }
}

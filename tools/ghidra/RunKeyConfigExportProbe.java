// Execute the key driver's bounded two-byte configuration export in p-code.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;

public class RunKeyConfigExportProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc10518L;
    private static final long STOP = 0x700300L;
    private static final long BSS = 0x9fc14328L;
    private static final long STATUS = 0x9fc14368L;
    private static final long MMIO_2 = 0xc0020000L;
    private static final long CONTROL = MMIO_2 + 0x0c;
    private static final long DEVICE_STATUS = MMIO_2 + 0x10;
    private static final long STACK = 0x10003f00L;
    private static final int MAX_STEPS = 500;
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

    private long readWord(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private int readByte(EmulatorHelper emulator, long location) throws Exception {
        return emulator.readMemory(address(location), 1)[0] & 0xff;
    }

    private void writeWord(EmulatorHelper emulator, long location, long value) throws Exception {
        emulator.writeMemory(address(location), new byte[] {
            (byte)value,
            (byte)(value >>> 8),
            (byte)(value >>> 16),
            (byte)(value >>> 24)
        });
    }

    private void addBlock(Memory memory, long start, int size, String name) throws Exception {
        MemoryBlock block = memory.getBlock(address(start));
        if (block == null) {
            block = memory.createInitializedBlock(
                name,
                address(start),
                new ByteArrayInputStream(new byte[size]),
                size,
                monitor,
                false
            );
        }
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private void runCase(
            String label,
            int argument0,
            int argument1,
            int stored0,
            int stored1,
            long deviceStatus,
            long initialControl
    ) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
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

            emulator.writeMemory(address(BSS), new byte[0xd6]);
            emulator.writeMemory(address(MMIO_2), new byte[0x1000]);
            emulator.writeMemory(address(STACK), new byte[0x100]);
            emulator.writeMemory(address(STATUS + 5), new byte[] {(byte)stored0, (byte)stored1});
            writeWord(emulator, CONTROL, initialControl);
            writeWord(emulator, DEVICE_STATUS, deviceStatus);
            emulator.writeRegister("sp", STACK + 0x80);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", argument0);
            emulator.writeRegister("a1", argument1);
            emulator.writeRegister("pc", ENTRY);

            int steps = 0;
            for (; steps < MAX_STEPS; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    break;
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "key configuration export stopped at 0x%08x: %s",
                        pc,
                        emulator.getLastError()
                    ));
                }
            }
            require(steps < MAX_STEPS, "key configuration export did not return");

            int config0 = readByte(emulator, STATUS + 5);
            int config1 = readByte(emulator, STATUS + 6);
            int status = readByte(emulator, STATUS);
            int reset0 = readByte(emulator, BSS + 6);
            int reset1 = readByte(emulator, BSS + 8);
            int reset2 = readByte(emulator, BSS + 9);
            int reset3 = readByte(emulator, BSS + 10);
            long control = readWord(emulator, CONTROL);

            long expectedControl = label.equals("clamp-idle") ? 0xa5a573ffL : 0x123451adL;
            int expectedStatus = label.equals("clamp-idle") ? 0 : 4;
            int expectedConfig0 = label.equals("clamp-idle") ? 9 : 2;
            int expectedConfig1 = label.equals("clamp-idle") ? 9 : 3;
            require(control == expectedControl,
                String.format("control=%08x expected=%08x", control, expectedControl));
            require(status == expectedStatus,
                String.format("status=%02x expected=%02x", status, expectedStatus));
            require(config0 == expectedConfig0 && config1 == expectedConfig1,
                String.format("stored config=%02x,%02x", config0, config1));
            require(reset0 == 0 && reset1 == 0 && reset2 == 0 && reset3 == 1,
                String.format("reset state=%02x,%02x,%02x,%02x",
                    reset0, reset1, reset2, reset3));

            println(String.format(
                "KEY CONFIG EXPORT CASE %s args=%02x,%02x stored=%02x,%02x device=%08x control=%08x status=%02x reset=%02x,%02x,%02x,%02x steps=%d",
                label,
                argument0,
                argument1,
                config0,
                config1,
                deviceStatus,
                control,
                status,
                reset0,
                reset1,
                reset2,
                reset3,
                steps
            ));
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKeyConfigExportProbe.java KEY.DRV");
        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver)
        );
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);

        Memory memory = currentProgram.getMemory();
        require(memory.getBlock(address(ENTRY)) != null, "key.drv code is not mapped");
        addBlock(memory, BSS, 0xd6, "key_config_export_bss");
        addBlock(memory, MMIO_2, 0x1000, "key_config_export_mmio_2");
        addBlock(memory, STACK, 0x100, "key_config_export_stack");

        runCase("clamp-idle", 9, 9, 0, 0, 0, 0xa5a5f3ffL);
        runCase("retain-active", 0, 0, 2, 3, 0x80, 0x1234abcdL);
        println("KEY CONFIG EXPORT P-CODE PASS cases=2 synthetic_bss=1 synthetic_mmio=1 device_semantics=unmodeled");
    }
}

// Measure when key.drv's module-0x0f operation 9 caller prepares message 0xb0.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class RunKeyModule0fOp9MessageProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc10f98L;
    private static final long STOP_WITHOUT_MESSAGE = 0xbfc10fc2L;
    private static final long STOP_AFTER_MESSAGE = 0xbfc11032L;
    private static final long SERVICE_TRAP = 0xbfc10006L;
    private static final long STACK = 0x10003f00L;
    private static final long KEY_DATA = 0x9fc14300L;
    private static final long EVENT_DATA = 0x9fc09f00L;
    private static final long MMIO = 0xc0000000L;
    private static final long INITIAL_MMIO_WORD = 0xa5a50001L;
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

    private int readHalf(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 2);
        return (bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8);
    }

    private int readByte(EmulatorHelper emulator, long location) throws Exception {
        return emulator.readMemory(address(location), 1)[0] & 0xff;
    }

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size,
        byte[] initial
    ) throws Exception {
        var block = memory.createInitializedBlock(
            name, address(start), new ByteArrayInputStream(initial), size, monitor, false
        );
        block.setRead(true);
        block.setWrite(true);
    }

    private String runProfile(
        Memory memory,
        long inputFlags,
        long operation9Return
    ) throws Exception {
        memory.setBytes(address(KEY_DATA), new byte[0x100]);
        memory.setBytes(address(EVENT_DATA), new byte[0x100]);
        memory.setBytes(address(MMIO), new byte[0x1000]);
        memory.setBytes(address(STACK - 0x1000), new byte[0x2000]);
        memory.setBytes(address(MMIO), new byte[] {
            (byte)INITIAL_MMIO_WORD,
            (byte)(INITIAL_MMIO_WORD >>> 8),
            (byte)(INITIAL_MMIO_WORD >>> 16),
            (byte)(INITIAL_MMIO_WORD >>> 24)
        });

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> calls = new ArrayList<>();
        List<String> stores = new ArrayList<>();
        int messageAtOp24 = -1;
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                java.math.BigInteger.ONE);
            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP_WITHOUT_MESSAGE | 1L);
            emulator.writeRegister("a0", inputFlags);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.writeRegister("pc", ENTRY);

            int steps;
            for (steps = 0; steps < 10000; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == STOP_WITHOUT_MESSAGE || pc == STOP_AFTER_MESSAGE) {
                    break;
                }

                if (pc == SERVICE_TRAP) {
                    long module = register(emulator, "v1");
                    long a0 = register(emulator, "a0");
                    long a1 = register(emulator, "a1");
                    long a2 = register(emulator, "a2");
                    long a3 = register(emulator, "a3");
                    long ra = register(emulator, "ra");
                    require(module == 0x0f && a2 == 0,
                        String.format("unexpected module call: module=%x args=%08x/%08x/%08x/%08x",
                            module, a0, a1, a2, a3));
                    require((ra & 1L) == 1L,
                        String.format("module-0x0f return address lacks MIPS16 tag: %08x", ra));

                    long returnValue;
                    if (a3 == 9) {
                        require(a0 == 1 && a1 == 0,
                            String.format("unexpected op-9 ABI: %08x/%08x/%08x/%08x",
                                a0, a1, a2, a3));
                        returnValue = operation9Return;
                    } else if (a3 == 24) {
                        require(a0 == 5 && a1 == STACK - 16,
                            String.format("unexpected op-24 ABI: %08x/%08x/%08x/%08x",
                                a0, a1, a2, a3));
                        messageAtOp24 = readHalf(emulator, a1);
                        returnValue = 0;
                    } else {
                        throw new Exception(String.format("unexpected module-0x0f op %08x", a3));
                    }

                    calls.add(String.format("op=%02x args=%08x/%08x/%08x/%08x ra=%08x",
                        a3, a0, a1, a2, a3, ra));
                    emulator.writeRegister("v0", returnValue);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (pc == 0xbfc10ffcL || pc == 0xbfc1100eL) {
                    long destination = register(emulator, "a0");
                    String sourceRegister = pc == 0xbfc10ffcL ? "a1" : "a3";
                    long source = register(emulator, sourceRegister);
                    if (!emulator.step(monitor)) {
                        throw new Exception(String.format("key store stopped at 0x%08x: %s",
                            pc, emulator.getLastError()));
                    }
                    stores.add(String.format("pc=%08x dst=%08x src=%08x after=%08x",
                        pc, destination, source, readWord(emulator, destination)));
                    continue;
                }

                if (pc >= 0xbfc10000L && pc < 0xbfc10e00L) {
                    emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                        java.math.BigInteger.ONE);
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("key handler stopped at 0x%08x: %s",
                        pc, emulator.getLastError()));
                }
            }

            require(steps < 10000, "key handler exceeded instruction limit");
            boolean expectedMessage = (inputFlags & 0x81) == 0x80
                && (inputFlags & 0x02) != 0
                && (operation9Return & 0xff) == 0;
            require(calls.size() == (expectedMessage ? 2 : 1),
                "unexpected service-call sequence: " + calls);
            require(calls.get(0).contains("op=09 args=00000001/00000000/00000000/00000009"),
                "op-9 call did not match the expected ABI: " + calls.get(0));

            long finalMmio = readWord(emulator, MMIO);
            long expectedMmio = expectedMessage ? 0xa5a50009L : INITIAL_MMIO_WORD;
            require(finalMmio == expectedMmio,
                String.format("MMIO result=%08x expected=%08x", finalMmio, expectedMmio));
            int eventStateByte = readByte(emulator, EVENT_DATA + 0x71);
            int keyStateByte = readByte(emulator, KEY_DATA + 0x7d);
            if (expectedMessage) {
                require(messageAtOp24 == 0x00b0,
                    String.format("op-24 payload=%04x expected=00b0", messageAtOp24));
                require(calls.get(1).contains("op=18 args=00000005/10003ef0/00000000/00000018"),
                    "op-24 call did not carry the 0xb0 payload: " + calls.get(1));
            }

            return String.format("flags=%02x op9-return=%08x calls=%d message=%s mmio=%08x event-state=%02x key-state=%02x steps=%d",
                inputFlags, operation9Return, calls.size(),
                expectedMessage ? "00b0" : "none", finalMmio,
                eventStateByte, keyStateByte, steps) + " stores=" + stores;
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKeyModule0fOp9MessageProbe.java KEY.DRV");
        byte[] driver = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);

        Memory memory = currentProgram.getMemory();
        mapWritableRegion(memory, "key_op9_state", KEY_DATA, 0x100, new byte[0x100]);
        mapWritableRegion(memory, "key_op9_event", EVENT_DATA, 0x100, new byte[0x100]);
        mapWritableRegion(memory, "key_op9_mmio", MMIO, 0x1000, new byte[0x1000]);
        mapWritableRegion(memory, "key_op9_stack", STACK - 0x1000, 0x2000, new byte[0x2000]);

        long[] flags = {0x80, 0x82};
        long[] returns = {0, 1, 0x100, 0xffffffffL};
        List<String> results = new ArrayList<>();
        for (long inputFlags : flags) {
            for (long operation9Return : returns) {
                results.add(runProfile(memory, inputFlags, operation9Return));
            }
        }

        for (String result : results) {
            println("KEY MODULE-0F OP-9 MESSAGE PROFILE " + result);
        }
        println("KEY MODULE-0F OP-9 MESSAGE SWEEP COMPLETE profiles=8 "
            + "op24-return=0 synthetic-mmio=seeded");
    }
}

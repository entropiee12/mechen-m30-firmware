import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Run the real key-driver slot-8 path and capture its module-0x0f ABI calls. */
public class RunKeyExport8AbiProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc102e4L;
    private static final long EVENT_ENTRY = 0xbfc10220L;
    private static final long SERVICE_TRAP = 0xbfc10006L;
    private static final long STOP = 0x700300L;
    private static final long STACK = 0x10003f00L;
    private static final long OUTPUT = 0x10002000L;
    private static final long KEY_RAM = 0x9fc14300L;
    private static final int KEY_RAM_SIZE = 0x200;
    private static final long KEY_PREVIOUS = 0x9fc14324L;
    private static final long KEY_STATE = 0x9fc14334L;
    private static final long MMIO_2 = 0xc0020000L;
    private static final long MMIO_9 = 0xc0090000L;
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

    private byte[] read(EmulatorHelper emulator, long start, int length) throws Exception {
        return emulator.readMemory(address(start), length);
    }

    private long readWord(EmulatorHelper emulator, long start) throws Exception {
        byte[] bytes = read(emulator, start, 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size
    ) throws Exception {
        if (memory.getBlock(address(start)) != null) {
            MemoryBlock block = memory.getBlock(address(start));
            block.setRead(true);
            block.setWrite(true);
            return;
        }

        MemoryBlock block = memory.createInitializedBlock(
            name,
            address(start),
            new ByteArrayInputStream(new byte[size]),
            size,
            monitor,
            false
        );
        block.setRead(true);
        block.setWrite(true);
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }

        return result.toString();
    }

    private String changedBytes(byte[] before, byte[] after, long base) {
        List<String> changes = new ArrayList<>();
        for (int offset = 0; offset < before.length; offset++) {
            if (before[offset] != after[offset]) {
                changes.add(String.format("%08x:%02x>%02x",
                    base + offset,
                    before[offset] & 0xff,
                    after[offset] & 0xff));
            }
        }

        return changes.isEmpty() ? "none" : String.join(",", changes);
    }

    private String normalizeSyntheticEffects(String result) {
        return result
            .replaceAll("profile=[^ ]+ ", "profile=<case> ")
            .replaceAll("return=[0-9a-f]{8}", "return=<synthetic>")
            .replaceAll("v0=[0-9a-f]{8}", "v0=<result>");
    }

    private void seedCase(
        EmulatorHelper emulator,
        int normalizedValue,
        int previousValue,
        int eventRepeatCount
    ) throws Exception {
        emulator.writeMemory(address(KEY_RAM), new byte[KEY_RAM_SIZE]);
        emulator.writeMemory(address(MMIO_2), new byte[0x1000]);
        emulator.writeMemory(address(MMIO_9), new byte[0x20]);
        emulator.writeMemory(address(STACK - 0x100), new byte[0x200]);
        emulator.writeMemory(address(OUTPUT), new byte[4]);

        byte[] normalizedValues = {0, (byte)normalizedValue, 0, 0, 0, 0, 0};
        emulator.writeMemory(address(KEY_STATE + 8), normalizedValues);
        emulator.writeMemory(address(KEY_PREVIOUS + 1), new byte[] {(byte)previousValue});
        emulator.writeMemory(address(KEY_STATE + 0x18), new byte[] {
            (byte)eventRepeatCount,
            (byte)(eventRepeatCount >>> 8),
            (byte)(eventRepeatCount >>> 16),
            (byte)(eventRepeatCount >>> 24)
        });
        emulator.writeMemory(address(KEY_STATE + 0x1c), new byte[] {(byte)previousValue});
        emulator.writeMemory(address(MMIO_2 + 0x28), new byte[] {0});
    }

    private String runTick(
        EmulatorHelper emulator,
        String profile,
        int tick,
        long op24Return,
        long op25Return
    ) throws Exception {
        byte[] keyBefore = read(emulator, KEY_RAM, KEY_RAM_SIZE);
        byte[] mmio2Before = read(emulator, MMIO_2, 0x1000);
        byte[] mmio9Before = read(emulator, MMIO_9, 0x20);
        emulator.writeMemory(address(STACK - 0x100), new byte[0x200]);

        List<String> calls = new ArrayList<>();
        emulator.writeRegister("sp", STACK);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("a0", OUTPUT);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("s0", 0);
        emulator.writeRegister("s1", 0);
        emulator.writeRegister("pc", ENTRY);

        int steps;
        for (steps = 0; steps < 20000; steps++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc == STOP) {
                break;
            }

            if (pc == SERVICE_TRAP) {
                long module = register(emulator, "v1");
                long a0 = register(emulator, "a0");
                long a1 = register(emulator, "a1");
                long a2 = register(emulator, "a2");
                long a3 = register(emulator, "a3");
                long ra = register(emulator, "ra");
                require(module == 0x0f && a0 == 6 && a2 == 0
                        && (a3 == 24 || a3 == 25),
                    String.format("unexpected module ABI module=%02x args=%08x/%08x/%08x/%08x",
                        module, a0, a1, a2, a3));
                require((ra & 1L) == 1L,
                    String.format("module return address lacks MIPS16 tag: %08x", ra));

                byte[] bufferAtCall = read(emulator, a1, 4);
                String bufferLabel = a3 == 24 ? "payload" : "buffer-before";
                calls.add(String.format(
                    "module=0f op=%02x args=%08x/%08x/%08x/%08x %s=%s return=%08x ra=%08x",
                    a3, a0, a1, a2, a3, bufferLabel, hex(bufferAtCall),
                    a3 == 24 ? op24Return : op25Return, ra));

                // The ROM service is opaque here. Only its caller-side ABI is measured.
                emulator.writeRegister("v0", a3 == 24 ? op24Return : op25Return);
                emulator.writeRegister("pc", ra & ~1L);
                continue;
            }

            if (pc >= 0xbfc10000L && pc < 0xbfc10e00L) {
                emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                    java.math.BigInteger.ONE);
            }

            if (!emulator.step(monitor)) {
                throw new Exception(String.format("key slot-8 path stopped at %08x: %s",
                    pc, emulator.getLastError()));
            }
        }

        require(steps < 20000, "key slot-8 path exceeded instruction limit");
        byte[] keyAfter = read(emulator, KEY_RAM, KEY_RAM_SIZE);
        byte[] mmio2After = read(emulator, MMIO_2, 0x1000);
        byte[] mmio9After = read(emulator, MMIO_9, 0x20);

        return String.format(
            "profile=%s tick=%d steps=%d v0=%08x calls=%s key-delta=%s mmio2-delta=%s mmio9-delta=%s",
            profile,
            tick,
            steps,
            register(emulator, "v0"),
            calls.isEmpty() ? "none" : String.join("|", calls),
            changedBytes(keyBefore, keyAfter, KEY_RAM),
            changedBytes(mmio2Before, mmio2After, MMIO_2),
            changedBytes(mmio9Before, mmio9After, MMIO_9)
        );
    }

    private String runProfile(
        Memory memory,
        String profile,
        int normalizedValue,
        int previousValue,
        int eventRepeatCount,
        int ticks,
        long op24Return,
        long op25Return,
        String expectedOp18Payload,
        boolean expectOp19
    ) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> trace = new ArrayList<>();
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                java.math.BigInteger.ONE);
            seedCase(emulator, normalizedValue, previousValue, eventRepeatCount);

            for (int tick = 1; tick <= ticks; tick++) {
                String result = runTick(emulator, profile, tick, op24Return, op25Return);
                if (tick == 4 && expectedOp18Payload != null) {
                    require(result.contains("op=18 ")
                            && result.contains("payload=" + expectedOp18Payload),
                        "profile " + profile + " did not produce expected op-18 payload: " + result);
                }
                if (tick == 4 && expectedOp18Payload == null) {
                    require(!result.contains("op=18 "),
                        "profile " + profile + " unexpectedly produced an op-18 payload: " + result);
                }
                if (tick == 4 && expectOp19) {
                    require(result.contains("op=19 ")
                            && result.contains("buffer-before="),
                        "profile " + profile + " did not reach module op-25: " + result);
                }
                if (tick == 4 && !expectOp19) {
                    require(!result.contains("op=19 "),
                        "profile " + profile + " unexpectedly reached module op-25: " + result);
                }
                if (tick == 4 && (expectedOp18Payload != null || expectOp19)) {
                    long expectedResult = op24Return == 0 ? 1 : 0;
                    require(result.contains(String.format("v0=%08x", expectedResult)),
                        "profile " + profile + " returned an unexpected caller result: " + result);
                }
                trace.add(result);
                println("KEY EXPORT-8 ABI " + result);
            }
        }
        finally {
            emulator.dispose();
        }

        return String.join("\n", trace);
    }

    private String runEventTick(
        EmulatorHelper emulator,
        int inputValue,
        int expectedRepeatCount,
        int expectedCurrentKey,
        String expectedPayload
    ) throws Exception {
        emulator.writeMemory(address(STACK - 0x100), new byte[0x200]);
        emulator.writeRegister("sp", STACK);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("a0", inputValue);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("s0", 0);
        emulator.writeRegister("s1", 0);
        emulator.writeRegister("pc", EVENT_ENTRY);

        List<String> calls = new ArrayList<>();
        int steps;
        for (steps = 0; steps < 20000; steps++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc == STOP) {
                break;
            }

            if (pc == SERVICE_TRAP) {
                long module = register(emulator, "v1");
                long a0 = register(emulator, "a0");
                long a1 = register(emulator, "a1");
                long a2 = register(emulator, "a2");
                long a3 = register(emulator, "a3");
                long ra = register(emulator, "ra");
                require(module == 0x0f && a0 == 6 && a2 == 0
                        && (a3 == 24 || a3 == 25),
                    String.format("unexpected event formatter ABI module=%02x args=%08x/%08x/%08x/%08x",
                        module, a0, a1, a2, a3));
                require((ra & 1L) == 1L,
                    String.format("event formatter return address lacks MIPS16 tag: %08x", ra));

                byte[] payload = read(emulator, a1, 4);
                calls.add(String.format("op=%02x payload=%s", a3, hex(payload)));
                emulator.writeRegister("v0", 0);
                emulator.writeRegister("pc", ra & ~1L);
                continue;
            }

            if (pc >= 0xbfc10000L && pc < 0xbfc10e00L) {
                emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                    java.math.BigInteger.ONE);
            }

            if (!emulator.step(monitor)) {
                throw new Exception(String.format("key event formatter stopped at %08x: %s",
                    pc, emulator.getLastError()));
            }
        }

        require(steps < 20000, "key event formatter exceeded instruction limit");
        long repeatCount = readWord(emulator, KEY_STATE + 0x18);
        int currentKey = read(emulator, KEY_STATE + 0x1c, 1)[0] & 0xff;
        require(repeatCount == expectedRepeatCount && currentKey == expectedCurrentKey,
            String.format("unexpected event state count=%d key=%02x expected count=%d key=%02x",
                repeatCount, currentKey, expectedRepeatCount, expectedCurrentKey));

        if (expectedPayload == null) {
            require(calls.isEmpty(), "unexpected event payloads: " + calls);
        }
        else {
            require(calls.size() == 1 && calls.get(0).equals("op=18 payload=" + expectedPayload),
                "unexpected event payloads: " + calls);
        }

        return String.format("input=%02x count=%d key=%02x calls=%s",
            inputValue, repeatCount, currentKey, calls.isEmpty() ? "none" : calls);
    }

    private void runEventRepeatSequence(Memory memory) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                java.math.BigInteger.ONE);
            seedCase(emulator, 0, 0, 0);

            String[] expectedPayloads = {
                "00020020",
                null,
                null,
                null,
                "00020010",
                "00020008",
                "00020008"
            };
            for (int index = 0; index < expectedPayloads.length; index++) {
                String result = runEventTick(
                    emulator,
                    2,
                    index + 1,
                    2,
                    expectedPayloads[index]
                );
                println("KEY EVENT REPEAT " + (index + 1) + " " + result);
            }
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKeyExport8AbiProbe.java KEY.DRV");
        byte[] driver = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);
        require(currentProgram.getMemory().getBlock(address(ENTRY)) != null,
            "key.drv slot-8 code is not mapped");

        Memory memory = currentProgram.getMemory();
        mapWritableRegion(memory, "key_slot8_ram", KEY_RAM, KEY_RAM_SIZE);
        mapWritableRegion(memory, "key_slot8_mmio_2", MMIO_2, 0x1000);
        mapWritableRegion(memory, "key_slot8_mmio_9", MMIO_9, 0x20);
        mapWritableRegion(memory, "key_slot8_stack", STACK - 0x100, 0x200);
        mapWritableRegion(memory, "key_slot8_output", OUTPUT, 4);

        String op18Zero = runProfile(memory, "ordinary-key", 2, 0, 0, 6, 0, 0,
            "00020020", false);
        runProfile(memory, "release", 0x30, 2, 0, 4, 0, 0,
            "00020004", false);
        runProfile(memory, "play", 0x32, 0, 0, 4, 0, 0,
            "000c0020", false);
        runProfile(memory, "card", 0x34, 0, 0, 4, 0, 0,
            "000d0020", false);
        runProfile(memory, "normal-31", 0x31, 0, 0, 4, 0, 0, "00310020", false);
        runProfile(memory, "normal-33", 0x33, 0, 0, 4, 0, 0, "00330020", false);
        runProfile(memory, "normal-35", 0x35, 0, 0, 4, 0, 0, "00350020", false);
        runProfile(memory, "unmapped-ff", 0xff, 0, 0, 4, 0, 0, null, false);
        String op18One = runProfile(memory, "op18-return-one", 2, 0, 0, 6, 1, 0,
            "00020020", false);
        String op18Negative = runProfile(memory, "op18-return-ffffffff", 2, 0, 0, 6,
            0xffffffffL, 0, "00020020", false);
        require(normalizeSyntheticEffects(op18Zero).equals(normalizeSyntheticEffects(op18One))
                && normalizeSyntheticEffects(op18Zero).equals(normalizeSyntheticEffects(op18Negative)),
            "module op-24 synthetic return changed caller memory or control flow");
        runProfile(memory, "release-repeat-count-12", 0x30, 2, 12, 4, 0, 0,
            "00020004", false);
        String op19Zero = runProfile(memory, "release-long-op19-zero", 0x30, 2, 13, 4, 0, 0,
            "00020004", true);
        String op19One = runProfile(memory, "release-long-op19-one", 0x30, 2, 13, 4, 0, 1,
            "00020004", true);
        String op19Negative = runProfile(memory, "release-long-op19-ffffffff", 0x30, 2, 13, 4, 0,
            0xffffffffL, "00020004", true);
        require(normalizeSyntheticEffects(op19Zero).equals(normalizeSyntheticEffects(op19One))
                && normalizeSyntheticEffects(op19Zero).equals(normalizeSyntheticEffects(op19Negative)),
            "module op-25 synthetic return changed caller-visible state or flow");
        runEventRepeatSequence(memory);

        println("KEY EXPORT-8 ABI PROBE COMPLETE entry=0xbfc102e4 "
            + "op18-op19-returns=synthetic-profile-values event-repeat=verified-pcode mmio=plain-ram");
    }
}

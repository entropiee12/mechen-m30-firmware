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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Measure the caller-side RAM and MMIO effects of key.drv export-table slot 7. */
public class RunKeyExport7EffectProbe extends GhidraScript {
    private static final long CODE_BASE = 0xbfc10000L;
    private static final long ENTRY = 0xbfc10064L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc14300L;
    private static final int RAM_SIZE = 0x100;
    private static final long OUTPUT_BYTE = RAM + 0x3a;
    private static final long MMIO = 0xc0090000L;
    private static final int MMIO_SIZE = 0x1000;
    private static final long MMIO_WORD = MMIO + 0x5c;
    private static final long STACK = 0x10003f00L;
    private static final int STACK_SIZE = 0x200;
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

    private void putWord(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte)value;
        bytes[offset + 1] = (byte)(value >>> 8);
        bytes[offset + 2] = (byte)(value >>> 16);
        bytes[offset + 3] = (byte)(value >>> 24);
    }

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size
    ) throws Exception {
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
                changes.add(String.format(
                    "%08x:%02x>%02x",
                    base + offset,
                    before[offset] & 0xff,
                    after[offset] & 0xff
                ));
            }
        }

        if (changes.isEmpty()) {
            return "none";
        }

        return String.join(",", changes);
    }

    private String runCase(int inputValue, long initialMmioWord) throws Exception {
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

            byte[] initialRam = new byte[RAM_SIZE];
            Arrays.fill(initialRam, (byte)0xa5);
            emulator.writeMemory(address(RAM), initialRam);

            byte[] initialMmio = new byte[MMIO_SIZE];
            int wordOffset = (int)(MMIO_WORD - MMIO);
            putWord(initialMmio, wordOffset, initialMmioWord);
            emulator.writeMemory(address(MMIO), initialMmio);
            emulator.writeMemory(address(STACK - STACK_SIZE), new byte[STACK_SIZE]);
            byte[] ramBaseline = initialRam.clone();
            byte[] mmioBaseline = initialMmio.clone();

            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", inputValue);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("v0", 0);
            emulator.writeRegister("pc", ENTRY);

            List<String> externalPcs = new ArrayList<>();
            int steps;
            for (steps = 0; steps < 2000; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    break;
                }

                if (pc < CODE_BASE || pc >= CODE_BASE + 0x10000L) {
                    externalPcs.add(String.format("%08x", pc));
                }
                else {
                    emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                        java.math.BigInteger.ONE);
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "key slot-7 stopped at %08x: %s",
                        pc,
                        emulator.getLastError()
                    ));
                }
            }

            require(steps < 2000, "key slot-7 exceeded instruction limit");
            require(externalPcs.isEmpty(),
                "slot-7 execution left the mapped driver image: " + externalPcs);

            byte[] finalRam = read(emulator, RAM, RAM_SIZE);
            byte[] finalMmio = read(emulator, MMIO, MMIO_SIZE);
            long finalMmioWord = readWord(emulator, MMIO_WORD);
            int expectedByte = inputValue & 0xff;
            long expectedMmioWord = (inputValue & 0xff) == 0
                ? initialMmioWord | 0x80000000L
                : initialMmioWord;

            byte[] expectedRam = ramBaseline.clone();
            expectedRam[(int)(OUTPUT_BYTE - RAM)] = (byte)expectedByte;
            require(Arrays.equals(expectedRam, finalRam),
                String.format("slot-7 RAM effect mismatch for a0=%08x: %s",
                    inputValue, changedBytes(ramBaseline, finalRam, RAM)));
            require(finalMmioWord == expectedMmioWord,
                String.format("slot-7 MMIO word mismatch for a0=%08x: %08x",
                    inputValue, finalMmioWord));

            byte[] expectedMmio = mmioBaseline.clone();
            putWord(expectedMmio, wordOffset, expectedMmioWord);
            require(Arrays.equals(expectedMmio, finalMmio),
                String.format("slot-7 unexpected MMIO delta for a0=%08x: %s",
                    inputValue, changedBytes(mmioBaseline, finalMmio, MMIO)));
            require(register(emulator, "v0") == RAM + 0x34,
                String.format("slot-7 return pointer mismatch for a0=%08x: %08x",
                    inputValue, register(emulator, "v0")));

            return String.format(
                "a0=%08x steps=%d v0=%08x ram-delta=%s mmio-word=%08x>%08x mmio-delta=%s",
                inputValue,
                steps,
                register(emulator, "v0"),
                changedBytes(ramBaseline, finalRam, RAM),
                initialMmioWord,
                finalMmioWord,
                changedBytes(mmioBaseline, finalMmio, MMIO)
            );
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKeyExport7EffectProbe.java KEY.DRV");
        byte[] driver = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);
        require(currentProgram.getMemory().getBlock(address(ENTRY)) != null,
            "key.drv slot-7 code is not mapped");

        Memory memory = currentProgram.getMemory();
        mapWritableRegion(memory, "key_slot7_ram", RAM, RAM_SIZE);
        mapWritableRegion(memory, "key_slot7_mmio", MMIO, MMIO_SIZE);
        mapWritableRegion(memory, "key_slot7_stack", STACK - STACK_SIZE, STACK_SIZE);

        int[][] cases = {
            {0, 0},
            {0, 0x12345678},
            {1, 0x12345678},
            {0x100, 0},
            {0x101, 0xa5a55aa5}
        };
        for (int[] testCase : cases) {
            println("KEY EXPORT-7 EFFECT " + runCase(testCase[0],
                Integer.toUnsignedLong(testCase[1])));
        }

        println("KEY EXPORT-7 EFFECT PROBE COMPLETE entry=0xbfc10064 "
            + "inputs=00000000,00000001,00000100,00000101 mmio-mask=0x80000000 plain-ram");
    }
}

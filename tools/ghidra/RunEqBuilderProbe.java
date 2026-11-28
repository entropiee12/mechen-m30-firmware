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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunEqBuilderProbe extends GhidraScript {
    private static final long ENTRY = 0x601d0d58L;
    private static final long STOP = 0x700300L;
    private static final long STACK = 0x9fc19000L;
    private static final long RAM_BASE = 0x9fc15000L;
    private static final int RAM_SIZE = 0x6000;
    private static final long SELECTOR = 0x9fc15cc9L;
    private static final long FLAGS = 0x9fc15d9cL;
    private static final long STRUCTURE = 0x9fc15dd4L;
    private static final long CUSTOM = 0x9fc1a19cL;
    private static final long COPY_STUB = 0xbfc11cc0L;
    private static final long MESSAGE_STUB = 0xbfc11cc4L;
    private static final long SHM_STUB = 0xbfc11cc8L;
    private static final int[] FREQUENCIES = {80, 200, 480, 980, 4000, 8000, 16000};
    private static final int[] Q_VALUES = {10, 6, 4, 6, 10, 5, 8};
    private static final int[][] GAINS = {
        {0, 0, 0, 0, 0, 0, 0},
        {5, 3, 0, -1, 0, 4, 5},
        {3, 0, 0, 0, 0, -1, -2},
        {0, 0, 1, 2, 0, 1, 1},
        {3, 0, 0, -1, 0, 2, 4},
        {0, 0, 0, -1, -1, -2, -4},
        {5, 2, 0, 0, 0, 0, 6}
    };

    private Address address(long value) throws Exception {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private byte[] bytes(EmulatorHelper emulator, long start, int length) throws Exception {
        return emulator.readMemory(address(start), length);
    }

    private void writeBytes(Memory memory, long start, byte[] data) throws Exception {
        memory.setBytes(address(start), data);
    }

    private void verifyBands(byte[] data, int selector, byte[] custom) throws Exception {
        for (int index = 0; index < 7; index++) {
            int offset = 0x10 + index * 8;
            int gain = selector == 7 ? custom[index] : GAINS[selector][index];
            int scaledGain = gain * 10;
            require((data[offset] & 0xff) == (FREQUENCIES[index] & 0xff), "frequency low byte");
            require((data[offset + 1] & 0xff) == (FREQUENCIES[index] >>> 8), "frequency high byte");
            require((data[offset + 2] & 0xff) == (Q_VALUES[index] & 0xff), "Q low byte");
            require((data[offset + 3] & 0xff) == (Q_VALUES[index] >>> 8), "Q high byte");
            require((short)((data[offset + 4] & 0xff) | ((data[offset + 5] & 0xff) << 8)) == scaledGain,
                "scaled signed gain");
            require(data[offset + 6] == 1, "band enable byte");
            require(data[offset + 7] == 0, "band reserved byte");
        }

        for (int offset = 0x48; offset < 0x90; offset++) {
            require(data[offset] == 0, "unused band region must be zero");
        }

        for (int offset = 0x90; offset < 0xf0; offset++) {
            require((data[offset] & 0xff) == 0xaa, "builder must preserve trailing bytes");
        }
    }

    private byte[] runCase(
        Memory memory,
        String name,
        int selector,
        byte[] custom,
        int busy,
        int active
    ) throws Exception {
        memory.setBytes(address(RAM_BASE), new byte[RAM_SIZE]);
        byte[] initialStructure = new byte[0xf0];
        Arrays.fill(initialStructure, (byte)0xaa);
        initialStructure[0x0c] = (byte)busy;
        initialStructure[0x07] = 0x55;
        initialStructure[0x0d] = 0x55;
        writeBytes(memory, STRUCTURE, initialStructure);
        writeBytes(memory, SELECTOR, new byte[] {(byte)selector});
        writeBytes(memory, FLAGS + 0x18, new byte[] {(byte)active});
        writeBytes(memory, CUSTOM, custom);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<Long> stubs = new ArrayList<>();
        byte[][] copiedStructure = new byte[1][];
        boolean returned = false;
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("a0", 0);
            emulator.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 200000; steps++) {
                long pc = register(emulator, "pc");
                long target = pc & ~1L;
                if (target == STOP) {
                    returned = true;
                    break;
                }

                if (target == COPY_STUB || target == MESSAGE_STUB || target == SHM_STUB) {
                    long a0 = register(emulator, "a0");
                    long a1 = register(emulator, "a1");
                    long a2 = register(emulator, "a2");
                    long a3 = register(emulator, "a3");
                    long ra = register(emulator, "ra");
                    stubs.add(target);

                    if (target == COPY_STUB) {
                        require(a1 == STRUCTURE && a2 == 0xf0, "copy stub source/length");
                        copiedStructure[0] = bytes(emulator, a1, (int)a2);
                    }

                    if (target == SHM_STUB) {
                        require(a0 == 3 && a3 == 0x49, "shared-memory stub arguments");
                    }

                    if (target == MESSAGE_STUB) {
                        require(a1 == 0x1f && a2 == 0 && a3 == 0x49, "message stub arguments");
                    }

                    emulator.writeRegister("v0", target == SHM_STUB ? 0x9fc16000L : 0);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (!emulator.step(monitor)) {
                    throw new Exception("EQ builder stopped at 0x" + Long.toHexString(pc)
                        + ": " + emulator.getLastError());
                }
            }

            require(returned, "EQ builder exceeded the instruction limit");

            byte[] result = bytes(emulator, STRUCTURE, 0xf0);
            byte[] selectorCopy = bytes(emulator, FLAGS + 0x19, 1);
            require((selectorCopy[0] & 0xff) == selector, "selector mirror");

            boolean shouldBuild = active != 0 && busy != 1;
            if (shouldBuild) {
                require(stubs.equals(Arrays.asList(COPY_STUB, SHM_STUB, MESSAGE_STUB)),
                    "expected copy, shared-memory, message call order");
                verifyBands(result, selector, custom);
                require(copiedStructure[0] != null && Arrays.equals(result, copiedStructure[0]),
                    "copy snapshot must match built structure");
                require((result[0x07] & 0xff) == 1, "header ready byte");
                require((result[0x0d] & 0xff) == (selector == 0 ? 0 : 1),
                    "header selector-enabled byte");
            } else {
                require(stubs.isEmpty(), "guarded path must not invoke service stubs");
                require(Arrays.equals(initialStructure, result), "guarded path changed structure");
            }

            println("EQ BUILDER CASE name=" + name + " selector=" + selector
                + " active=" + active + " busy=" + busy + " stubs=" + stubs.size());
            return result;
        } finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        String[] scriptArgs = getScriptArgs();
        if (scriptArgs.length > 1)
            throw new Exception("usage: RunEqBuilderProbe.java [OUTPUT_DIRECTORY]");
        Path outputDirectory = scriptArgs.length == 1 ? Paths.get(scriptArgs[0]) : null;
        if (outputDirectory != null) Files.createDirectories(outputDirectory);

        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.createInitializedBlock(
            "eq_builder_probe_ram",
            address(RAM_BASE),
            new ByteArrayInputStream(new byte[RAM_SIZE]),
            RAM_SIZE,
            monitor,
            false
        );
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sentinel = {1, 2, 3, (byte)0xfd, (byte)0xfe, 0x7f, (byte)0x80};
        byte[] defaultCustom = {(byte)0xf7, 0, 0, 0, 0, 0, 6};
        int cases = 0;

        for (int selector = 0; selector <= 7; selector++) {
            byte[] result = runCase(memory, "preset", selector, sentinel, 0, 1);
            digest.update((byte)selector);
            digest.update(result);
            cases++;
            if (outputDirectory != null && (selector == 0 || selector == 3)) {
                String name = selector == 0 ? "off.bin" : "hip-hop.bin";
                Files.write(outputDirectory.resolve(name), result);
                println("EQ BUILDER EMIT preset=" + selector + " file=" + name + " bytes=" + result.length);
            }
        }

        byte[] customResult = runCase(memory, "custom-default", 7, defaultCustom, 0, 1);
        if (outputDirectory != null) {
            Files.write(outputDirectory.resolve("custom-default.bin"), customResult);
            println("EQ BUILDER EMIT preset=custom-default file=custom-default.bin bytes=" + customResult.length);
        }
        digest.update((byte)8);
        digest.update(customResult);
        cases++;

        // The exact experimental music.ap curve previously reported as
        // inaudible: exercise it through the real builder without packaging
        // or modifying a firmware image.
        byte[] candidateCustom = {6, 5, 0, (byte)0xfe, 0, 4, 3};
        byte[] candidateResult = runCase(memory, "custom-candidate", 7, candidateCustom, 0, 1);
        if (outputDirectory != null) {
            Files.write(outputDirectory.resolve("custom-candidate.bin"), candidateResult);
            println("EQ BUILDER EMIT preset=custom-candidate file=custom-candidate.bin bytes=" + candidateResult.length);
        }
        digest.update((byte)9);
        digest.update(candidateResult);
        cases++;

        byte[] busyResult = runCase(memory, "busy", 3, sentinel, 1, 1);
        digest.update((byte)10);
        digest.update(busyResult);
        cases++;

        byte[] inactiveResult = runCase(memory, "inactive", 3, sentinel, 0, 0);
        digest.update((byte)11);
        digest.update(inactiveResult);
        cases++;

        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) {
            hash.append(String.format("%02x", value & 0xff));
        }
        println("EQ BUILDER P-CODE PASS cases=" + cases + " result-sha256=" + hash);
    }
}

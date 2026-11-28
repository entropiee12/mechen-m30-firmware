// Execute aud_dev bank-5 EQ handling and capture the module-0x10 copy ABI.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunAuddevEqCopyProbe extends GhidraScript {
    private static final long ENTRY = 0x9400L;
    private static final long STOP = 0x700300L;
    private static final long COPY_STUB = 0x10003000L;
    private static final long RAM = 0x10000000L;
    private static final long SOURCE = RAM + 0x1000;
    private static final long STATE = 0x9fc34000L;
    private static final long STACK = RAM + 0x4000;
    private static final int BANDS = 16;
    private static final List<String> calls = new ArrayList<>();

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private void putWord(Memory memory, long at, long value) throws Exception {
        memory.setBytes(addr(at), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private long fileWord(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private short s16(byte[] bytes, int offset) {
        return (short)u16(bytes, offset);
    }

    private byte[] read(EmulatorHelper emu, long address, int length) throws Exception {
        return emu.readMemory(addr(address), length);
    }

    private void expectBytes(EmulatorHelper emu, long address, byte[] expected, String label)
            throws Exception {
        byte[] actual = read(emu, address, expected.length);
        require(Arrays.equals(actual, expected), String.format(
            "%s mismatch at 0x%08x expected=%s actual=%s",
            label, address, hex(expected), hex(actual)));
    }

    private byte[] le16(int value) {
        return new byte[] { (byte)value, (byte)(value >>> 8) };
    }

    private void verifyDecodedGroups(EmulatorHelper emu, byte[] input) throws Exception {
        require(input[0x0c] == 1 && input[7] == 1 && input[9] == 1
                && input[10] == 1 && input[11] == 1 && input[13] == 1,
            "decoded-groups fixture must enable flags +0xc,+7,+9,+0xa,+0xb,+0xd");
        expectBytes(emu, STATE + 0x40, le16(input[0x0c]), "DRC group enable");
        expectBytes(emu, STATE + 0x6c, Arrays.copyOfRange(input, 0x9e, 0xa0), "DRC first value");
        expectBytes(emu, STATE + 0x74, Arrays.copyOfRange(input, 0xa2, 0xba), "DRC records");
        expectBytes(emu, STATE + 0x8c, le16(50), "DRC constant 50");
        expectBytes(emu, STATE + 0x8e, le16(500), "DRC constant 500");
        expectBytes(emu, STATE + 0x44, Arrays.copyOfRange(input, 0x98, 0x9a), "gain value");
        expectBytes(emu, STATE + 0x48, new byte[] { input[0x9a], 0, 0, 0 }, "gain mode");
        expectBytes(emu, STATE + 0x5c, new byte[] { input[0x9c], 0, 0, 0 }, "secondary gain value");
        expectBytes(emu, STATE + 0x60, new byte[] { input[0x9d], 0, 0, 0 }, "secondary gain mode");
        expectBytes(emu, STATE + 0x64, Arrays.copyOfRange(input, 0x90, 0x92), "bass value");
        expectBytes(emu, STATE + 0x68, new byte[] { input[0x92], 0, 0, 0 }, "bass mode");
        expectBytes(emu, STATE + 0x50, Arrays.copyOfRange(input, 0xba, 0xbc), "output gain");
        expectBytes(emu, STATE + 0x54, Arrays.copyOfRange(input, 0xbc, 0xbe), "output gain item");
        expectBytes(emu, STATE + 0x58, Arrays.copyOfRange(input, 0xbe, 0xc0), "output gain value");
    }

    private void verifyDisabledDrcGroup(EmulatorHelper emu, byte[] input) throws Exception {
        require(input[0x0c] == 0 && input[7] == 1,
            "disabled-groups fixture must clear +0xc while retaining EQ flag +7");
        expectBytes(emu, STATE + 0x40, le16(0), "DRC group enable when disabled");
        expectBytes(emu, STATE + 0x6c, new byte[2], "disabled DRC first value");
        expectBytes(emu, STATE + 0x74, new byte[0x1c], "disabled DRC records and constants");
    }

    private byte[] expectedBand(byte[] source, byte[] structure, String expectation) {
        byte[] expected = Arrays.copyOf(source, source.length);
        int frequency = u16(source, 0);
        int mode = source[6] & 0xff;
        int delta = 0;
        if ("decoded-adjustments".equals(expectation) && mode == 1) {
            int lowThreshold = u16(structure, 0xc4) & 0x7fff;
            int highThreshold = u16(structure, 0xc8) & 0x7fff;
            if (frequency <= lowThreshold) delta += s16(structure, 0xc6);
            if (highThreshold <= frequency) delta += s16(structure, 0xca);
            for (int index = 0; index < 3; index++) {
                int adjustmentOffset = 0xc2 + index * 4;
                int exactFrequencyOffset = 0xcc + index * 4;
                if (s16(structure, adjustmentOffset) != 0
                        && frequency == u16(structure, exactFrequencyOffset))
                    delta += s16(structure, adjustmentOffset);
            }
        }
        int gain = (short)(s16(source, 4) + delta);
        expected[4] = (byte)gain;
        expected[5] = (byte)(gain >>> 8);
        return expected;
    }

    private int execute(EmulatorHelper emu) throws Exception {
        emu.writeRegister("sp", STACK);
        emu.writeRegister("ra", STOP | 1L);
        emu.writeRegister("a0", 1);
        emu.writeRegister("a1", SOURCE);
        emu.writeRegister("a2", 0);
        emu.writeRegister("a3", 0);
        emu.writeRegister("pc", ENTRY);
        for (int steps = 0; steps < 100000; steps++) {
            long pc = reg(emu, "pc") & ~1L;
            if (pc >= 0x9400 && pc < 0x9a00) {
                emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            }
            if (pc == STOP) return steps;
            if (pc == COPY_STUB) {
                long a0 = reg(emu, "a0");
                long a1 = reg(emu, "a1");
                long a2 = reg(emu, "a2");
                long a3 = reg(emu, "a3");
                long ra = reg(emu, "ra");
                require(a2 == 8 && a3 == 0,
                    String.format("unexpected module-0x10 copy request: len=%d flag=%d", a2, a3));
                boolean eqBandCopy = a0 >= STATE + 0x94 && a0 + a2 <= STATE + 0x114
                    && a1 >= SOURCE + 0x10 && a1 + a2 <= SOURCE + 0x90;
                boolean drcRecordCopy = a0 >= STATE + 0x74 && a0 + a2 <= STATE + 0x8c
                    && a1 >= SOURCE + 0xa2 && a1 + a2 <= SOURCE + 0xba;
                require(eqBandCopy || drcRecordCopy,
                    String.format("module-0x10 copy outside known EQ/DRC regions: dest=0x%08x src=0x%08x", a0, a1));
                calls.add(String.format(
                    "a0=0x%08x a1=0x%08x a2=0x%08x a3=0x%08x ra=0x%08x",
                    a0, a1, a2, a3, ra
                ));
                // Candidate module-0x10 memcpy semantics, inferred from call shape.
                byte[] copied = emu.readMemory(addr(a1), (int)a2);
                emu.writeMemory(addr(a0), copied);
                require(Arrays.equals(copied, emu.readMemory(addr(a0), (int)a2)),
                    "candidate module-0x10 shim write did not reach emulator memory");
                emu.writeRegister("v0", 0);
                emu.writeRegister("pc", ra & ~1L);
                continue;
            }
            if (!emu.step(monitor)) {
                throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
            }
        }
        throw new Exception("aud_dev EQ entry exceeded instruction limit");
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 1 || getScriptArgs().length > 3)
            throw new Exception("usage: RunAuddevEqCopyProbe.java AUD_DEV.DRV [EQ_STRUCTURE.bin [neutral|decoded-adjustments|decoded-groups|observe]]");
        byte[] driver = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        if (driver.length < 0x2500) throw new Exception("aud_dev.drv is truncated before bank 5");

        Memory memory = currentProgram.getMemory();
        MemoryBlock code = memory.getBlock(addr(ENTRY));
        if (code == null) throw new Exception("mapped bank-5 code is missing");
        byte[] mappedCode = new byte[0x600];
        memory.getBytes(addr(ENTRY), mappedCode);
        if (!Arrays.equals(mappedCode, Arrays.copyOfRange(driver, 0x1f00, 0x2500)))
            throw new Exception("loaded 0x9400 code does not match input file bank-5 candidate");
        if (fileWord(driver, 0x23fc) != 0xbfc09f59L
                || fileWord(driver, 0x23f8) != 0x9fc34000L)
            throw new Exception("candidate bank-5 pointer words do not match expected image values");
        code.setWrite(true);
        putWord(memory, 0x98fc, COPY_STUB | 1L);

        MemoryBlock ram = memory.createInitializedBlock("auddev_eq_probe_ram", addr(RAM),
            new ByteArrayInputStream(new byte[0x5000]), 0x5000, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        MemoryBlock state = memory.createInitializedBlock("auddev_eq_probe_state", addr(STATE),
            new ByteArrayInputStream(new byte[0x200]), 0x200, monitor, false);
        state.setRead(true);
        state.setWrite(true);

        byte[] input;
        if (getScriptArgs().length >= 2) {
            input = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(getScriptArgs()[1]));
            require(input.length == 0xf0,
                String.format("EQ structure must be 0xf0 bytes, got 0x%x", input.length));
            require(input[7] == 1, "EQ structure ready byte +7 must be 1");
        } else {
            // Synthetic fallback: one active structure with distinct bands.
            input = new byte[0xf0];
            input[7] = 1;
            for (int band = 0; band < BANDS; band++) {
                for (int j = 0; j < 8; j++)
                    input[0x10 + band * 8 + j] = (byte)(band * 8 + j + 1);
            }
        }
        boolean neutralAdjustments = true;
        for (int offset = 0xc0; offset < 0xcc; offset++)
            neutralAdjustments &= input[offset] == 0;
        String expectation = getScriptArgs().length == 3 ? getScriptArgs()[2] : "observe";
        require(expectation.equals("neutral") || expectation.equals("decoded-adjustments")
                || expectation.equals("decoded-groups") || expectation.equals("disabled-groups")
                || expectation.equals("observe"),
            "unknown adjustment expectation: " + expectation);
        if (expectation.equals("neutral"))
            require(neutralAdjustments, "neutral expectation requires zero adjustment words");
        memory.setBytes(addr(SOURCE), input);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            int steps = execute(emu);
            int expectedCalls = BANDS + (expectation.equals("decoded-groups") ? 3 : 0);
            require(calls.size() == expectedCalls,
                "expected " + expectedCalls + " module-0x10 calls, got " + calls.size());
            for (int band = 0; band < BANDS; band++) {
                String expected = String.format(
                    "a0=0x%08x a1=0x%08x a2=0x00000008 a3=0x00000000",
                    STATE + 0x94 + band * 8L, SOURCE + 0x10 + band * 8L
                );
                require(calls.get(band).startsWith(expected),
                    "band " + band + " call mismatch: " + calls.get(band));
                byte[] sourceBand = emu.readMemory(addr(SOURCE + 0x10 + band * 8L), 8);
                byte[] destinationBand = emu.readMemory(addr(STATE + 0x94 + band * 8L), 8);
                if (!expectation.equals("observe")) {
                    byte[] expectedBytes = expectedBand(sourceBand, input, expectation);
                    require(Arrays.equals(destinationBand, expectedBytes),
                        "band " + band + " final bytes differ: expected=" + hex(expectedBytes)
                            + " actual=" + hex(destinationBand) + " expectation=" + expectation);
                }
                boolean unchangedAfterHandler = Arrays.equals(destinationBand, sourceBand);
                println(String.format("AUDDEV EQ COPY band=%02d after-handler=%s source=%s destination=%s %s",
                    band, unchangedAfterHandler ? "same" : "adjusted",
                    hex(sourceBand), hex(destinationBand), calls.get(band)));
            }
            if (expectation.equals("decoded-groups")) {
                verifyDecodedGroups(emu, input);
                println("AUDDEV EQ GROUPS P-CODE PASS flags=+0xc,+7,+9,+0xa,+0xb,+0xd records=3");
            } else if (expectation.equals("disabled-groups")) {
                verifyDisabledDrcGroup(emu, input);
                println("AUDDEV EQ GROUPS P-CODE PASS flag=+0xc value=0 drc-writes=0");
            }
            println(String.format(
                "AUDDEV EQ COPY P-CODE PASS entry=0x%08x steps=%d module0x10-calls=%d copy-writes-verified=%d expectation=%s input=%s module0x10=synthetic-memcpy-candidate",
                ENTRY, steps, calls.size(), calls.size(),
                expectation,
                getScriptArgs().length >= 2 ? "provided-0xf0-structure" : "synthetic-pattern"
            ));
        }
        finally {
            emu.dispose();
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}

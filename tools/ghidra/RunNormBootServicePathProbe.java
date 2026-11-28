// Execute the real NORMBREC MIPS16 routine through synthetic ROM pointer slots.
// The slot targets and their effects are fixtures, not recovered ROM code.
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.exec.PcodeUseropLibrary;
import ghidra.pcode.exec.SleighProgramCompiler;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class RunNormBootServicePathProbe extends GhidraScript {
    private static final long BASE = 0xbfc08000L;
    private static final long ENTRY = 0xbfc08028L;
    private static final long STOP = 0x00704000L;
    private static final long SLOT_A8 = 0xbfc002a8L;
    private static final long SLOT_A0 = 0xbfc002a0L;
    private static final long SLOT_94 = 0xbfc00294L;
    private static final long SLOT_A4 = 0xbfc002a4L;
    private static final long TARGET_A8 = 0x00703000L;
    private static final long TARGET_94 = 0x00703020L;
    private static final long TARGET_A0 = 0x00703040L;
    private static final long TARGET_A4 = 0x00703060L;
    private static final long BUFFER = 0xbfc10000L;
    private static final long STACK = 0x9fc21000L;
    private static final int MAX_STEPS = 4000;
    private static final String EXPECTED_SHA256 =
        "dacb549733c6af1727ed2dd8efe091ff6386ef3a111329f5407475ee620c5ace";

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private Address addr(AddressSpace space, long value) {
        return space.getAddress(value & 0xffffffffL);
    }

    private long reg(PcodeThread<byte[]> thread, Register register,
            SleighLanguage language) {
        byte[] value = thread.getState().getVar(register, Reason.INSPECT);
        return Utils.bytesToLong(value, register.getMinimumByteSize(),
            language.isBigEndian()) & 0xffffffffL;
    }

    private long mode(PcodeThread<byte[]> thread, Register register) {
        RegisterValue value = thread.getContext().getRegisterValue(register);
        return value.getUnsignedValueIgnoreMask().longValue() & 1L;
    }

    private void putWord(PcodeEmulator emulator, AddressSpace space,
            long address, long value) {
        byte[] bytes = new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        };
        emulator.getSharedState().setVar(space, address, bytes.length, true, bytes);
    }

    private void putBytes(PcodeEmulator emulator, AddressSpace space,
            long address, byte[] bytes) {
        emulator.getSharedState().setVar(space, address, bytes.length, true, bytes);
    }

    private static final class FixtureResult {
        final List<String> calls;
        final int instructions;

        FixtureResult(List<String> calls, int instructions) {
            this.calls = calls;
            this.instructions = instructions;
        }
    }

    private FixtureResult executeFixture(SleighLanguage language, AddressSpace space,
            byte[] image, int blockCount, long firstA8Return) throws Exception {
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register ra = language.getRegister("ra");
        Register v0 = language.getRegister("v0");
        Register a0 = language.getRegister("a0");
        Register a1 = language.getRegister("a1");
        Register a2 = language.getRegister("a2");
        Register a3 = language.getRegister("a3");

        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, BASE, image.length, true, image);
        putWord(emulator, space, SLOT_A8, TARGET_A8 | 1L);
        putWord(emulator, space, SLOT_A0, TARGET_A0 | 1L);
        putWord(emulator, space, SLOT_94, TARGET_94 | 1L);
        putWord(emulator, space, SLOT_A4, TARGET_A4 | 1L);
        putBytes(emulator, space, BUFFER, new byte[] {
            0, 0, 0, 0, (byte)blockCount, (byte)(blockCount >>> 8), 0, 0
        });
        putBytes(emulator, space, STACK - 0x1000, new byte[0x2000]);

        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(
            SleighProgramCompiler.compileProgram(language, "normboot-service-init",
                "sp = 0x9fc21000; s1 = 0; ra = 0x00704001; "
                    + "a0 = 0; a1 = 0; a2 = 0; a3 = 0; v0 = 0;", library), library);
        RegisterValue mips16 = new RegisterValue(isa, BigInteger.ONE)
            .assign(relp, new RegisterValue(relp, BigInteger.ONE));
        thread.overrideContext(mips16);
        thread.overrideCounter(addr(space, ENTRY));

        long[] targets = {TARGET_A8, TARGET_A0, TARGET_94, TARGET_A4};
        long[] slots = {SLOT_A8, SLOT_A0, SLOT_94, SLOT_A4};
        List<String> calls = new ArrayList<>();
        boolean stopped = false;
        int iterations = 0;
        int instructions = 0;
        int a8Calls = 0;
        for (; iterations < MAX_STEPS; iterations++) {
            long pc = thread.getCounter().getOffset() & 0xffffffffL;
            if (pc == STOP) {
                stopped = true;
                break;
            }
            int targetIndex = -1;
            for (int i = 0; i < targets.length; i++)
                if (pc == targets[i]) targetIndex = i;
            if (targetIndex >= 0) {
                require(mode(thread, isa) == 1 && mode(thread, relp) == 1,
                    String.format("expected MIPS16e at synthetic slot 0x%08x", slots[targetIndex]));
                long returnAddress = reg(thread, ra, language);
                require((returnAddress & 1L) == 1,
                    String.format("MIPS16e return address lacks mode tag: 0x%08x", returnAddress));
                calls.add(String.format(
                    "slot=0x%08x target=0x%08x args=%08x,%08x,%08x,%08x",
                    slots[targetIndex], pc, reg(thread, a0, language),
                    reg(thread, a1, language), reg(thread, a2, language),
                    reg(thread, a3, language)));
                long callbackReturn = targetIndex == 0 && a8Calls++ == 0
                    ? firstA8Return : 0;
                thread.getState().setVar(v0, emulator.getArithmetic().fromConst(
                    callbackReturn, v0.getMinimumByteSize()));
                thread.overrideContext(mips16);
                thread.overrideCounter(addr(space, returnAddress & ~1L));
                continue;
            }
            thread.stepInstruction();
            instructions++;
        }

        require(stopped, String.format(
            "NORMBREC routine did not return within the step limit (blockCount=%d)",
            blockCount));
        return new FixtureResult(calls, instructions);
    }

    private void requireCall(FixtureResult result, int index, long slot,
            long target, String args) throws Exception {
        String expected = String.format("slot=0x%08x target=0x%08x args=%s",
            slot, target, args);
        require(index < result.calls.size(),
            "missing expected call " + expected + " in " + result.calls);
        require(result.calls.get(index).equals(expected),
            "unexpected call at index " + index + ": " + result.calls.get(index)
                + " expected " + expected);
    }

    private void requireCallCount(FixtureResult result, int count, String name)
            throws Exception {
        require(result.calls.size() == count,
            name + " fixture call count was " + result.calls.size() + ": " + result.calls);
    }

    private void printFixture(String name, FixtureResult result) {
        for (String call : result.calls)
            println("NORMBREC SERVICE EDGE case=" + name + " " + call);
        println(String.format("NORMBREC SERVICE CASE PASS case=%s instructions=%d calls=%d",
            name, result.instructions, result.calls.size()));
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunNormBootServicePathProbe.java NORMBREC.BIN");
        byte[] image = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        require(image.length == 0x200,
            String.format("unexpected NORMBREC size: 0x%x", image.length));
        String hash = java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(image));
        require(EXPECTED_SHA256.equals(hash), "NORMBREC SHA-256 mismatch: " + hash);

        SleighLanguage language = (SleighLanguage)currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        require(language.getRegister("ISA_MODE") != null
                && language.getRegister("RELP") != null
                && language.getRegister("ra") != null
                && language.getRegister("v0") != null
                && language.getRegister("a0") != null
                && language.getRegister("a1") != null
                && language.getRegister("a2") != null
                && language.getRegister("a3") != null,
            "MIPS language is missing a required register");

        FixtureResult small = executeFixture(language, space, image, 4, 0);
        requireCallCount(small, 3, "small/success");
        requireCall(small, 0, SLOT_A8, TARGET_A8,
            "00000000,00001800,bfc10000,00000000");
        requireCall(small, 1, SLOT_94, TARGET_94,
            "bfc10000,00000800,bfc10000,00000000");
        requireCall(small, 2, SLOT_A4, TARGET_A4,
            "bfc10100,00703041,00000000,00000000");
        printFixture("small-zero-return", small);

        FixtureResult large = executeFixture(language, space, image, 13, 0);
        requireCallCount(large, 4, "large/success");
        requireCall(large, 0, SLOT_A8, TARGET_A8,
            "00000000,00001800,bfc10000,00000000");
        requireCall(large, 1, SLOT_A8, TARGET_A8,
            "00001800,00000200,bfc11800,00000000");
        requireCall(large, 2, SLOT_94, TARGET_94,
            "bfc10000,00001a00,bfc11800,00000000");
        requireCall(large, 3, SLOT_A4, TARGET_A4,
            "bfc10100,00703041,00000000,00000000");
        printFixture("large-zero-return", large);

        FixtureResult error = executeFixture(language, space, image, 4, 1);
        requireCallCount(error, 4, "small/error-return");
        requireCall(error, 0, SLOT_A8, TARGET_A8,
            "00000000,00001800,bfc10000,00000000");
        requireCall(error, 1, SLOT_A0, TARGET_A0,
            "00000000,00001800,bfc10000,00000000");
        requireCall(error, 2, SLOT_94, TARGET_94,
            "bfc10000,00000800,bfc10000,00000000");
        requireCall(error, 3, SLOT_A4, TARGET_A4,
            "bfc10100,00703041,00000000,00000000");
        printFixture("small-nonzero-return", error);
        println(String.format(
            "NORMBREC SERVICE P-CODE PASS entry=0x%08x cases=3 small/large/error", ENTRY));
        println("NORMBREC SERVICE LIMIT synthetic-slot-targets no-ROM-or-boot-claim");
    }
}

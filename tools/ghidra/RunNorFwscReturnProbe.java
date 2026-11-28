// Execute the real NORFWSC conditional return tail with a seeded helper result.
// The preceding helper bodies and the other branch are deliberately not modeled.
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.exec.PcodeUseropLibrary;
import ghidra.pcode.exec.SleighProgramCompiler;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import java.math.BigInteger;

public class RunNorFwscReturnProbe extends GhidraScript {
    private static final long BASE = 0xbfc1e000L;
    private static final int IMAGE_SIZE = 0x2a00;
    private static final long START = 0xbfc1e2a8L;
    private static final long STOP = 0x00703000L;
    private static final long RESULT = 0x9fc24cfcL;
    private static final long DATA = 0x9fc24ca4L;
    private static final long STACK = 0x9fc30fd8L;
    private static final int MAX_STEPS = 64;

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long reg(PcodeThread<byte[]> thread, Register register,
            SleighLanguage language) {
        byte[] value = thread.getState().getVar(register, Reason.INSPECT);
        return Utils.bytesToLong(value, register.getMinimumByteSize(),
            language.isBigEndian()) & 0xffffffffL;
    }

    private long word(PcodeThread<byte[]> thread, AddressSpace space, long address,
            SleighLanguage language) {
        byte[] bytes = thread.getState().getVar(space, address, 4, true,
            Reason.INSPECT);
        return Utils.bytesToLong(bytes, 4, language.isBigEndian()) & 0xffffffffL;
    }

    private void putWord(PcodeEmulator emulator, AddressSpace space,
            long address, long value) {
        byte[] bytes = new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        };
        emulator.getSharedState().setVar(space, address, bytes.length, true, bytes);
    }

    @Override
    public void run() throws Exception {
        SleighLanguage language = (SleighLanguage) currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register sp = language.getRegister("sp");
        Register ra = language.getRegister("ra");
        Register v0 = language.getRegister("v0");
        Register s0 = language.getRegister("s0");
        Register s1 = language.getRegister("s1");
        Register s2 = language.getRegister("s2");
        require(isa != null && relp != null && sp != null && ra != null
                && v0 != null && s0 != null && s1 != null && s2 != null,
            "MIPS language is missing a required register");

        byte[] image = new byte[IMAGE_SIZE];
        currentProgram.getMemory().getBytes(space.getAddress(BASE), image);
        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, BASE, image.length, true, image);
        putWord(emulator, space, STACK + 24, DATA);
        putWord(emulator, space, STACK + 28, 1);
        putWord(emulator, space, STACK + 32, 0x12345678L);
        putWord(emulator, space, STACK + 36, STOP);
        putWord(emulator, space, DATA + 24, 0);

        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(
            SleighProgramCompiler.compileProgram(language, "norfwsc-return-init",
                "sp = 0x" + Long.toHexString(STACK)
                    + "; ra = 0x" + Long.toHexString(STOP)
                    + "; s0 = 0x" + Long.toHexString(DATA)
                    + "; s1 = 1; s2 = 0x12345678; v0 = 0;", library), library);
        thread.overrideContext(new RegisterValue(isa, BigInteger.ZERO)
            .assign(relp, new RegisterValue(relp, BigInteger.ZERO)));
        thread.overrideCounter(space.getAddress(START));

        boolean stopped = false;
        int steps = 0;
        for (; steps < MAX_STEPS; steps++) {
            long pc = thread.getCounter().getOffset() & 0xffffffffL;
            if (pc == STOP) { stopped = true; break; }
            thread.stepInstruction();
        }
        require(stopped, "NORFWSC return tail did not reach the seeded return address");
        require(reg(thread, v0, language) == RESULT,
            String.format("returned descriptor pointer 0x%08x != 0x%08x",
                reg(thread, v0, language), RESULT));
        require(word(thread, space, RESULT, language) == DATA,
            String.format("result data pointer 0x%08x != 0x%08x",
                word(thread, space, RESULT, language), DATA));
        require(word(thread, space, RESULT + 4, language) == 0x58L,
            String.format("result size 0x%08x != 0x58",
                word(thread, space, RESULT + 4, language)));
        require(word(thread, space, DATA + 24, language) == 1,
            "real return tail did not write the selected helper result");
        println(String.format(
            "NORFWSC RETURN P-CODE PASS branch=nonzero-helper-result steps=%d result=0x%08x data=0x%08x size=0x58 helper-body=stubbed",
            steps, RESULT, DATA));
    }
}

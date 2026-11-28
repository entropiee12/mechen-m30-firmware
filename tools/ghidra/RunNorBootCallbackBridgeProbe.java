// Execute the extracted MIPS16 callback bridge with synthetic ROM state.
// The callback target is intercepted at its real indirect-call boundary.
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

public class RunNorBootCallbackBridgeProbe extends GhidraScript {
    private static final long CODE_BASE = 0xbfc31f00L;
    private static final int CODE_SIZE = 0x28a00;
    private static final long ENTRY = 0xbfc32058L;
    private static final long CALLBACK_SLOT = 0xbfc02608L;
    private static final long CALLBACK = 0x00701000L;
    private static final long STOP = 0x00702000L;
    private static final long EXTERNAL_ROOT = 0xbfc10118L;
    private static final long CONTEXT = 0x9fc11000L;
    private static final long BUFFER = 0x9fc13000L;
    private static final int MAX_STEPS = 500;

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long reg(PcodeThread<byte[]> thread, Register register,
            SleighLanguage language) {
        byte[] value = thread.getState().getVar(register, Reason.INSPECT);
        return Utils.bytesToLong(value, register.getMinimumByteSize(),
            language.isBigEndian()) & 0xffffffffL;
    }

    private long mode(PcodeThread<byte[]> thread, Register isa) {
        RegisterValue value = thread.getContext().getRegisterValue(isa);
        return value.getUnsignedValueIgnoreMask().longValue() & 1L;
    }

    private void putWord(PcodeEmulator emulator, AddressSpace space,
            long address, long value) {
        byte[] bytes = new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        };
        emulator.getSharedState().setVar(space, address, bytes.length, true, bytes);
    }

    private void putHalf(PcodeEmulator emulator, AddressSpace space,
            long address, int value) {
        byte[] bytes = new byte[] { (byte)value, (byte)(value >>> 8) };
        emulator.getSharedState().setVar(space, address, bytes.length, true, bytes);
    }

    private void runCase(SleighLanguage language, AddressSpace space,
            Register isa, Register relp, Register ra, Register a0, Register a1,
            Register a2, Register v0, byte[] image, String label, long length,
            boolean expectCallback) throws Exception {
        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, CODE_BASE, image.length, true, image);
        putWord(emulator, space, EXTERNAL_ROOT, 0x20L);
        putWord(emulator, space, EXTERNAL_ROOT + 4, CONTEXT);
        putHalf(emulator, space, CONTEXT + 12, 7);
        putWord(emulator, space, CALLBACK_SLOT, CALLBACK | 1L);

        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(
            SleighProgramCompiler.compileProgram(language, "norboot-callback-init-" + label,
                "sp = 0x9fc3f000; ra = 0x" + Long.toHexString(STOP | 1L)
                    + "; a0 = 0x" + Long.toHexString(BUFFER)
                    + "; a1 = 3; a2 = 0x" + Long.toHexString(length)
                    + "; v0 = 0;", library), library);
        RegisterValue mips16 = new RegisterValue(isa, BigInteger.ONE)
            .assign(relp, new RegisterValue(relp, BigInteger.ONE));
        thread.overrideContext(mips16);
        thread.overrideCounter(space.getAddress(ENTRY));

        boolean returned = false;
        int callbackCount = 0;
        long seenA0 = 0, seenA1 = 0, seenA2 = 0;
        int steps = 0;
        for (; steps < MAX_STEPS; steps++) {
            long pc = thread.getCounter().getOffset() & 0xffffffffL;
            if (pc == STOP) {
                returned = true;
                break;
            }
            if (pc == CALLBACK) {
                require(mode(thread, isa) == 1,
                    "callback indirect edge did not retain MIPS16 mode");
                long returnAddress = reg(thread, ra, language);
                require(returnAddress == 0xbfc32085L,
                    String.format("unexpected callback return address: 0x%08x",
                        returnAddress));
                callbackCount++;
                seenA0 = reg(thread, a0, language);
                seenA1 = reg(thread, a1, language);
                seenA2 = reg(thread, a2, language);
                thread.getState().setVar(v0,
                    emulator.getArithmetic().fromConst(0, v0.getMinimumByteSize()));
                thread.overrideContext(mips16);
                thread.overrideCounter(space.getAddress(returnAddress & ~1L));
                continue;
            }
            thread.stepInstruction();
        }

        require(returned, label + " did not return within the step limit");
        require(mode(thread, isa) == 1, label + " did not return in MIPS16 mode");
        require(callbackCount == (expectCallback ? 1 : 0),
            label + " callback count=" + callbackCount);
        if (expectCallback) {
            require(seenA0 == 0x5400L && seenA1 == BUFFER && seenA2 == 0x5000L,
                String.format("callback arguments differ: %08x,%08x,%08x",
                    seenA0, seenA1, seenA2));
        }
        println(String.format(
            "NORBOOT CALLBACK BRIDGE CASE label=%s steps=%d callback-count=%d target=0x%08x args=%08x,%08x,%08x",
            label, steps, callbackCount, CALLBACK, seenA0, seenA1, seenA2));
    }

    @Override
    public void run() throws Exception {
        SleighLanguage language = (SleighLanguage) currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register ra = language.getRegister("ra");
        Register a0 = language.getRegister("a0");
        Register a1 = language.getRegister("a1");
        Register a2 = language.getRegister("a2");
        Register v0 = language.getRegister("v0");
        require(isa != null && relp != null && ra != null && a0 != null
                && a1 != null && a2 != null && v0 != null,
            "MIPS language is missing a required register");

        byte[] image = new byte[CODE_SIZE];
        currentProgram.getMemory().getBytes(space.getAddress(CODE_BASE), image);
        runCase(language, space, isa, relp, ra, a0, a1, a2, v0, image,
            "below-threshold", 0x28L, true);
        runCase(language, space, isa, relp, ra, a0, a1, a2, v0, image,
            "at-threshold", 0x29L, false);
        println("NORBOOT CALLBACK BRIDGE PASS function=0xbfc32058 slot=0xbfc02608 "
            + "a2-0x28-calls=1 a2-0x29-calls=0 callback-body=stubbed");
    }
}

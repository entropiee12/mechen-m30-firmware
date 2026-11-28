// Execute the UDisk AP caller through a synthetic pointer loaded from its fixed ROM slot.
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
import ghidra.program.model.mem.MemoryBlock;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class RunUdiskFixedCallbackPcodeProbe extends GhidraScript {
    private static final long ENTRY = 0x40014eccL;
    private static final long HELPER_A = 0xbfc1640cL;
    private static final long HELPER_B = 0xbfc16400L;
    private static final long ROM_SLOT = 0xbfc002a0L;
    private static final long SYNTHETIC_CALLBACK = 0x00704001L;
    private static final long STOP = 0x00703000L;
    private static final long STACK = 0x9fc1f000L;
    private static final long CALLBACK_SLOT = 0x9fc1564cL;
    private static final long MMIO_WORD = 0xc0020000L;
    private static final int MAX_STEPS = 1000;

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

    private byte[] le32(long value) {
        return new byte[] {(byte)value, (byte)(value >>> 8),
            (byte)(value >>> 16), (byte)(value >>> 24)};
    }

    private void loadProgram(PcodeEmulator emulator, AddressSpace space)
            throws Exception {
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (block.getSize() > Integer.MAX_VALUE)
                throw new Exception("unexpected oversized AP memory block: " + block.getName());
            byte[] bytes = new byte[(int)block.getSize()];
            currentProgram.getMemory().getBytes(block.getStart(), bytes);
            emulator.getSharedState().setVar(space, block.getStart().getOffset(),
                bytes.length, true, bytes);
        }
    }

    @Override
    public void run() throws Exception {
        SleighLanguage language = (SleighLanguage)currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register ra = language.getRegister("ra");
        Register a0 = language.getRegister("a0");
        Register a1 = language.getRegister("a1");
        Register a2 = language.getRegister("a2");
        Register a3 = language.getRegister("a3");
        Register v0 = language.getRegister("v0");
        Register v1 = language.getRegister("v1");
        require(isa != null && relp != null && ra != null && a0 != null
                && a1 != null && a2 != null && a3 != null && v0 != null
                && v1 != null, "MIPS language is missing a required register");

        PcodeEmulator emulator = new PcodeEmulator(language);
        loadProgram(emulator, space);
        emulator.getSharedState().setVar(space, ROM_SLOT, 4, true,
            le32(SYNTHETIC_CALLBACK));
        emulator.getSharedState().setVar(space, MMIO_WORD, 4, true,
            le32(0xa5a5005fL));
        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(
            SleighProgramCompiler.compileProgram(language, "udisk-callback-init",
                "sp = 0x" + Long.toHexString(STACK)
                    + "; ra = 0x" + Long.toHexString(STOP | 1L)
                    + "; a0 = 0; a1 = 0; a2 = 0; a3 = 0;"
                    + " s0 = 0; s1 = 0; v0 = 0; v1 = 0;",
                library), library);
        RegisterValue mips16 = new RegisterValue(isa, BigInteger.ONE)
            .assign(relp, new RegisterValue(relp, BigInteger.ONE));
        thread.overrideContext(mips16);
        thread.overrideCounter(space.getAddress(ENTRY));

        List<String> calls = new ArrayList<>();
        boolean reachedCallback = false;
        long callbackRa = 0;
        long callbackV0 = 0;
        long callbackV1 = 0;
        long callbackMode = 0;
        int steps = 0;
        int apInstructions = 0;
        for (; steps < MAX_STEPS; steps++) {
            long pc = thread.getCounter().getOffset() & 0xffffffffL;
            long normalizedPc = pc & ~1L;
            if (normalizedPc == (SYNTHETIC_CALLBACK & ~1L)) {
                reachedCallback = true;
                callbackRa = reg(thread, ra, language);
                callbackV0 = reg(thread, v0, language);
                callbackV1 = reg(thread, v1, language);
                callbackMode = thread.getContext().getRegisterValue(isa)
                    .getUnsignedValueIgnoreMask().longValue() & 1L;
                break;
            }
            if (normalizedPc == HELPER_A || normalizedPc == HELPER_B) {
                long returnAddress = reg(thread, ra, language);
                require((returnAddress & 1L) == 1L,
                    String.format("MIPS16 helper return lacks mode tag: 0x%08x", returnAddress));
                calls.add(String.format(
                    "target=0x%08x args=%08x,%08x,%08x,%08x ra=0x%08x",
                    normalizedPc, reg(thread, a0, language), reg(thread, a1, language),
                    reg(thread, a2, language), reg(thread, a3, language), returnAddress));
                thread.getState().setVar(v0,
                    emulator.getArithmetic().fromConst(0, v0.getMinimumByteSize()));
                thread.overrideContext(mips16);
                thread.overrideCounter(space.getAddress(returnAddress & ~1L));
                continue;
            }
            thread.stepInstruction();
            apInstructions++;
        }

        require(reachedCallback, "did not reach the fixed callback within the step limit");
        require(calls.size() == 2, "unexpected helper-call sequence: " + calls);
        require(calls.get(0).startsWith(
            "target=0xbfc1640c args=0000000a,00000007,00000000,00000005 "),
            "first helper edge differs: " + calls.get(0));
        require(calls.get(1).startsWith(
            "target=0xbfc16400 args=00000003,00000000,00000000,0000001d "),
            "second helper edge differs: " + calls.get(1));
        require(word(thread, space, CALLBACK_SLOT, language) == SYNTHETIC_CALLBACK,
            String.format("fixed callback slot contains 0x%08x",
                word(thread, space, CALLBACK_SLOT, language)));
        require(callbackRa == 0x40014f21L,
            String.format("callback returned to unexpected RA 0x%08x", callbackRa));
        require(word(thread, space, MMIO_WORD, language) == 0xa5a5006aL,
            String.format("MMIO masked update produced 0x%08x",
                word(thread, space, MMIO_WORD, language)));
        require(callbackMode == (SYNTHETIC_CALLBACK & 1L),
            String.format("synthetic callback entered with unexpected ISA_MODE=%d", callbackMode));
        for (String call : calls) println("UDISK FIXED CALLBACK EDGE " + call);
        println(String.format(
            "UDISK FIXED CALLBACK STATE rom-slot[0xbfc002a0]=0x%08x global-slot[0x9fc1564c]=0x%08x mmio[0xc0020000]=0x%08x synthetic-callback=0x%08x callback-ra=0x%08x callback-v0=0x%08x callback-v1=0x%08x callback-isa-mode=%d iterations=%d ap-instructions=%d helper-return=synthetic-zero callback-body=not-executed",
            word(thread, space, ROM_SLOT, language),
            word(thread, space, CALLBACK_SLOT, language),
            word(thread, space, MMIO_WORD, language), SYNTHETIC_CALLBACK, callbackRa,
            callbackV0, callbackV1, callbackMode, steps, apInstructions));
        println("UDISK FIXED CALLBACK P-CODE PASS scope=caller-only ROM-behavior=not-modeled");
    }
}

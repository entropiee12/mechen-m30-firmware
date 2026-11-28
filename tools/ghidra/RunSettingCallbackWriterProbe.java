// Execute setting.ap's conditional SYSRAM callback-slot writer with native MIPS p-code.
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

public class RunSettingCallbackWriterProbe extends GhidraScript {
    private static final long ENTRY = 0x48494600L;
    private static final long API = 0xbfc16585L;
    private static final long STOP = 0x700300L;
    private static final long SYSRAM = 0x9fc00000L;
    private static final long STACK = 0x9fc1f000L;
    private static final long DESCRIPTOR = SYSRAM + 0x18000L;
    private static final long CALLBACK_SLOT = SYSRAM + 0x15608L;
    private static final long DESCRIPTOR_SLOT = SYSRAM + 0x15600L;
    private static final long SENTINEL = 0xdeadbeefL;
    private static final long CALLBACK = 0x40054d79L;
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

    private long mode(PcodeThread<byte[]> thread, Register isa) {
        RegisterValue value = thread.getContext().getRegisterValue(isa);
        return value.getUnsignedValueIgnoreMask().longValue() & 1L;
    }

    private void put(PcodeEmulator emulator, AddressSpace space, long address,
            byte[] bytes) {
        emulator.getSharedState().setVar(space, address, bytes.length, true, bytes);
    }

    private byte[] le32(long value) {
        return new byte[] {(byte)value, (byte)(value >>> 8),
            (byte)(value >>> 16), (byte)(value >>> 24)};
    }

    private void loadProgram(PcodeEmulator emulator, AddressSpace space)
            throws Exception {
        byte[] bytes;
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (block.getSize() > Integer.MAX_VALUE)
                throw new Exception("unexpected oversized AP memory block: " + block.getName());
            bytes = new byte[(int)block.getSize()];
            currentProgram.getMemory().getBytes(block.getStart(), bytes);
            emulator.getSharedState().setVar(space, block.getStart().getOffset(),
                bytes.length, true, bytes);
        }
    }

    private void runCase(SleighLanguage language, AddressSpace space,
            Register isa, Register relp, Register ra, Register a0, Register a1,
            Register a2, Register a3, Register v0, int gate) throws Exception {
        PcodeEmulator emulator = new PcodeEmulator(language);
        loadProgram(emulator, space);
        put(emulator, space, DESCRIPTOR_SLOT, le32(DESCRIPTOR));
        put(emulator, space, CALLBACK_SLOT, le32(SENTINEL));
        put(emulator, space, DESCRIPTOR + 2, new byte[] {(byte)gate, 9});

        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(
            SleighProgramCompiler.compileProgram(language, "setting-callback-init-" + gate,
                "sp = 0x" + Long.toHexString(STACK)
                    + "; ra = 0x" + Long.toHexString(STOP | 1L)
                    + "; a0 = 0x" + Long.toHexString(CALLBACK)
                    + "; a1 = 0; a2 = 0; a3 = 0; s0 = 0; s1 = 0; v0 = 0;",
                library), library);
        RegisterValue mips16 = new RegisterValue(isa, BigInteger.ONE)
            .assign(relp, new RegisterValue(relp, BigInteger.ONE));
        thread.overrideContext(mips16);
        thread.overrideCounter(space.getAddress(ENTRY));

        int serviceCalls = 0;
        boolean returned = false;
        int steps = 0;
        for (; steps < MAX_STEPS; steps++) {
            long pc = thread.getCounter().getOffset() & 0xffffffffL;
            require(mode(thread, isa) == 1,
                String.format("unexpected ISA mode %d at 0x%08x", mode(thread, isa), pc));
            if (pc == STOP) {
                returned = true;
                break;
            }
            if (pc == (API & ~1L)) {
                long a0Value = reg(thread, a0, language);
                long a1Value = reg(thread, a1, language);
                long a2Value = reg(thread, a2, language);
                long a3Value = reg(thread, a3, language);
                long expectedA0 = gate == 1 && serviceCalls == 0 ? 6 : 9;
                require(a0Value == expectedA0 && a1Value == 0 && a2Value == 0
                        && a3Value == 0x1a,
                    String.format("unexpected selector-0x1a call gate=%d call=%d args=%08x,%08x,%08x,%08x",
                        gate, serviceCalls, a0Value, a1Value, a2Value, a3Value));
                long returnAddress = reg(thread, ra, language);
                require((returnAddress & 1L) == 1L,
                    String.format("MIPS16 service return address lacks mode bit: 0x%08x",
                        returnAddress));
                serviceCalls++;
                thread.getState().setVar(v0,
                    emulator.getArithmetic().fromConst(0, v0.getMinimumByteSize()));
                thread.overrideContext(mips16);
                thread.overrideCounter(space.getAddress(returnAddress & ~1L));
                continue;
            }
            thread.stepInstruction();
        }

        require(returned, String.format("gate=%d did not return within %d steps", gate, MAX_STEPS));
        int expectedCalls = gate == 1 ? 2 : 1;
        long expectedSlot = gate == 1 ? CALLBACK : SENTINEL;
        require(serviceCalls == expectedCalls,
            String.format("gate=%d service-call count=%d", gate, serviceCalls));
        require(word(thread, space, CALLBACK_SLOT, language) == expectedSlot,
            String.format("gate=%d callback slot mismatch: 0x%08x", gate,
                word(thread, space, CALLBACK_SLOT, language)));
        require(mode(thread, isa) == 1, "function did not return in MIPS16 mode");
        println(String.format(
            "SETTING CALLBACK WRITER CASE PASS gate=%d service-calls=%d service=intercepted-v0-0 callback-slot=0x%08x steps=%d native-mips-mode=true",
            gate, serviceCalls, expectedSlot, steps));
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
        require(isa != null && relp != null && ra != null && a0 != null
                && a1 != null && a2 != null && a3 != null && v0 != null,
            "MIPS language is missing a required register");

        runCase(language, space, isa, relp, ra, a0, a1, a2, a3, v0, 0);
        runCase(language, space, isa, relp, ra, a0, a1, a2, a3, v0, 1);
        println("SETTING CALLBACK WRITER PASS function=0x48494600 slot=0x9fc15608 "
            + "callback=0x40054d79 service=intercepted-v0-0 native-mips-mode=true descriptor=synthetic");
    }
}

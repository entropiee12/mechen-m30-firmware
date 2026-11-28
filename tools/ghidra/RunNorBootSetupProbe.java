// Execute the extracted MIPS16 setup routine with Ghidra's native MIPS p-code
// userops. External ROM targets are synthetic stubs; their behavior is not modeled.
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
import java.util.ArrayList;
import java.util.List;

public class RunNorBootSetupProbe extends GhidraScript {
    private static final long CODE_BASE = 0xbfc31f00L;
    private static final int CODE_SIZE = 0x28a00;
    private static final long ENTRY = 0xbfc32a48L;
    private static final long ROM_GATE = 0xbfc10a6cL;
    private static final long ROM_COPY_CANDIDATE = 0xbfc108d8L;
    private static final long STOP = 0x00701000L;
    private static final long DESCRIPTOR = 0xbfc10124L;
    private static final long OUTPUT = 0x9fc12000L;
    private static final int MAX_STEPS = 2000;

    private Address addr(AddressSpace space, long value) {
        return space.getAddress(value & 0xffffffffL);
    }

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
        require(isa != null && relp != null && sp != null && ra != null && v0 != null,
            "MIPS language is missing a required register");

        byte[] image = new byte[CODE_SIZE];
        currentProgram.getMemory().getBytes(addr(space, CODE_BASE), image);
        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, CODE_BASE, image.length, true, image);
        putWord(emulator, space, 0xbfc02608L, 0x11223344L);
        putWord(emulator, space, 0xbfc0260cL, 0x55667788L);
        putWord(emulator, space, 0xbfc02610L, 0x99aabbccL);
        putWord(emulator, space, 0xbfc02618L, 0xddeeff00L);
        putWord(emulator, space, 0xbfc10120L, 0x12345678L);
        for (int offset = 0; offset < 20; offset += 4)
            putWord(emulator, space, DESCRIPTOR + offset, 0xccccccccL);

        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(
            SleighProgramCompiler.compileProgram(language, "norboot-setup-init",
                "sp = 0x9fc21000; ra = 0x00701001; v0 = 0;", library), library);
        RegisterValue mips16 = new RegisterValue(isa, BigInteger.ONE)
            .assign(relp, new RegisterValue(relp, BigInteger.ONE));
        thread.overrideContext(mips16);
        thread.overrideCounter(addr(space, ENTRY));

        List<String> calls = new ArrayList<>();
        boolean stopped = false;
        int steps = 0;
        for (; steps < MAX_STEPS; steps++) {
            long pc = thread.getCounter().getOffset() & 0xffffffffL;
            if (pc == STOP) {
                stopped = true;
                break;
            }
            if (pc == ROM_GATE || pc == ROM_COPY_CANDIDATE) {
                require(mode(thread, isa) == 1,
                    String.format("expected MIPS16 at synthetic edge 0x%08x", pc));
                long returnAddress = reg(thread, ra, language);
                require((returnAddress & 1L) == 1,
                    String.format("MIPS16 return address lacks mode bit: 0x%08x",
                        returnAddress));
                calls.add(String.format("target=0x%08x args=%08x,%08x,%08x",
                    pc, reg(thread, language.getRegister("a0"), language),
                    reg(thread, language.getRegister("a1"), language),
                    reg(thread, language.getRegister("a2"), language)));
                thread.getState().setVar(v0,
                    emulator.getArithmetic().fromConst(0, v0.getMinimumByteSize()));
                thread.overrideContext(mips16);
                thread.overrideCounter(addr(space, returnAddress & ~1L));
                continue;
            }
            thread.stepInstruction();
        }

        require(stopped, "setup routine did not return within the step limit");
        require(calls.size() == 3, "unexpected ROM-call count: " + calls);
        require(calls.get(0).startsWith("target=0xbfc10a6c "),
            "first call was not the ROM gate: " + calls.get(0));
        require(calls.get(1).equals(
            "target=0xbfc108d8 args=9fc12000,bfc10124,00000014"),
            "first setup-helper arguments differ: " + calls.get(1));
        require(calls.get(2).equals(
            "target=0xbfc108d8 args=9fc12014,bfc10138,00000020"),
            "second setup-helper arguments differ: " + calls.get(2));
        require(word(thread, space, DESCRIPTOR, language) == 0xbfc08da1L
                && word(thread, space, DESCRIPTOR + 4, language) == 0x55667788L
                && word(thread, space, DESCRIPTOR + 8, language) == 0x99aabbccL
                && word(thread, space, DESCRIPTOR + 12, language) == 0xbfc08f3dL
                && word(thread, space, DESCRIPTOR + 16, language) == 0xccccccccL,
            "setup descriptor writes mismatch");
        require(word(thread, space, OUTPUT + 0x200, language) == 0xddeeff00L
                && word(thread, space, OUTPUT + 0x204, language) == 0x12345678L,
            "setup output metadata writes mismatch");
        for (String call : calls) println("NORBOOT ROM EDGE " + call);
        println(String.format(
            "NORBOOT SETUP P-CODE PASS entry=0x%08x steps=%d native-mips-mode=true synthetic-rom-returns=3 copy-semantics=not-modeled",
            ENTRY, steps));
    }
}

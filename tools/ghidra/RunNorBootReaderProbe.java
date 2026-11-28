// Execute the extracted MIPS16 NOR read wrapper with Ghidra's native MIPS
// p-code userops. External reader/ROM targets remain synthetic stubs.
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
import ghidra.program.model.mem.Memory;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class RunNorBootReaderProbe extends GhidraScript {
    private static final long CODE_BASE = 0xbfc31f00L;
    private static final int CODE_SIZE = 0x28a00;
    private static final long ENTRY = 0xbfc3292cL;
    private static final long READ_TARGET = 0x00702000L;
    private static final long ROM_TRANSFORM = 0xbfc108d8L;
    private static final long STOP = 0x00702040L;
    private static final long SCRATCH = 0x9fc27000L;
    private static final long TABLE = 0xbfc02600L;
    private static final long SECTOR_WORD = 0xbfc10138L;
    private static final int MAX_STEPS = 1000;

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

    @Override
    public void run() throws Exception {
        SleighLanguage language = (SleighLanguage) currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register sp = language.getRegister("sp");
        Register ra = language.getRegister("ra");
        Register a0 = language.getRegister("a0");
        Register a1 = language.getRegister("a1");
        Register a2 = language.getRegister("a2");
        Register v0 = language.getRegister("v0");
        require(isa != null && relp != null && sp != null && ra != null
                && a0 != null && a1 != null && a2 != null && v0 != null,
            "MIPS language is missing a required register");

        byte[] image = new byte[CODE_SIZE];
        currentProgram.getMemory().getBytes(addr(space, CODE_BASE), image);
        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, CODE_BASE, image.length, true, image);
        putWord(emulator, space, TABLE, READ_TARGET | 1L);
        putWord(emulator, space, SECTOR_WORD, 0x2800L);
        putWord(emulator, space, SCRATCH, 0);

        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(
            SleighProgramCompiler.compileProgram(language, "norboot-reader-init",
                "sp = 0x9fc31000; ra = 0x00702041; "
                    + "a0 = 0x9fc13000; a1 = 5; a2 = 0x601; "
                    + "s0 = 0; s1 = 0; v0 = 0;", library), library);
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
            if (pc == READ_TARGET || pc == ROM_TRANSFORM) {
                require(mode(thread, isa) == 1,
                    String.format("expected MIPS16 at synthetic edge 0x%08x", pc));
                long returnAddress = reg(thread, ra, language);
                require((returnAddress & 1L) == 1,
                    String.format("MIPS16 return address lacks mode bit: 0x%08x",
                        returnAddress));
                calls.add(String.format("target=0x%08x args=%08x,%08x,%08x",
                    pc, reg(thread, a0, language), reg(thread, a1, language),
                    reg(thread, a2, language)));
                thread.getState().setVar(v0,
                    emulator.getArithmetic().fromConst(0, v0.getMinimumByteSize()));
                thread.overrideContext(mips16);
                thread.overrideCounter(addr(space, returnAddress & ~1L));
                continue;
            }
            thread.stepInstruction();
        }

        require(stopped, "reader wrapper did not return within the step limit");
        require(calls.size() == 2, "unexpected helper-call count: " + calls);
        require(calls.get(0).equals(
            "target=0x00702000 args=00000019,9fc27000,00000004"),
            "block-reader arguments differ: " + calls.get(0));
        require(calls.get(1).equals(
            "target=0xbfc108d8 args=9fc13000,9fc27000,00000601"),
            "ROM-helper arguments differ: " + calls.get(1));
        for (String call : calls) println("NORBOOT READER EDGE " + call);
        println(String.format(
            "NORBOOT READER P-CODE PASS entry=0x%08x steps=%d native-mips-mode=true length=0x601 rounded-blocks=4 block-size=512 helper-semantics=not-modeled",
            ENTRY, steps));
    }
}

// Exercise Ghidra's native MIPS userop library across MIPS16 <-> MIPS32 flow.
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.exec.PcodeUseropLibrary;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import java.math.BigInteger;

public class RunMipsNativeModeProbe extends GhidraScript {
    private static final long BASE = 0x1000L;
    private static final byte[] CODE = new byte[] {
        0x00, 0x1c, 0x02, 0x04, // MIPS16 jalx 0x1008
        0x00, 0x65,             // MIPS16 delay-slot nop
        0x00, 0x65,             // MIPS16 nop after the call
        0x34, 0x12, 0x02, 0x24, // MIPS32 addiu v0,zero,0x1234
        0x08, 0x00, (byte)0xe0, 0x03, // MIPS32 jr ra
        0x00, 0x00, 0x00, 0x00  // MIPS32 delay-slot nop
    };

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long readRegister(PcodeThread<byte[]> thread, Register register,
            SleighLanguage language) {
        byte[] value = thread.getState().getVar(register, Reason.INSPECT);
        return Utils.bytesToLong(value, register.getMinimumByteSize(),
            language.isBigEndian());
    }

    private long isaMode(PcodeThread<byte[]> thread, Register isa) {
        RegisterValue context = thread.getContext().getRegisterValue(isa);
        return context.getUnsignedValueIgnoreMask().longValue() & 1L;
    }

    @Override
    public void run() throws Exception {
        SleighLanguage language = (SleighLanguage) currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        Address entry = space.getAddress(BASE);
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register ra = language.getRegister("ra");
        Register v0 = language.getRegister("v0");
        require(isa != null && relp != null && ra != null && v0 != null,
            "MIPS language is missing ISA_MODE, RELP, ra, or v0");

        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, BASE, CODE.length, true, CODE);
        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");

        thread.getExecutor().execute(
            ghidra.pcode.exec.SleighProgramCompiler.compileProgram(
                language, "mips-mode-init",
                "sp = 0x3000; ra = 0x4001; v0 = 0;", library), library);
        RegisterValue mips16Context = new RegisterValue(isa, BigInteger.ONE)
            .assign(relp, new RegisterValue(relp, BigInteger.ONE));
        thread.overrideContext(mips16Context);
        thread.overrideCounter(entry);

        require(isaMode(thread, isa) == 1,
            "fixture did not start in MIPS16 mode");
        thread.stepInstruction();
        require(thread.getCounter().getOffset() == BASE + 8,
            String.format("jalx did not reach MIPS32 body: pc=0x%x",
                thread.getCounter().getOffset()));
        require(isaMode(thread, isa) == 0,
            "native jalx did not switch to MIPS32 mode");

        thread.stepInstruction();
        require(readRegister(thread, v0, language) == 0x1234,
            "MIPS32 body did not execute its register write");
        require(readRegister(thread, ra, language) == BASE + 7,
            String.format("jalx did not leave the MIPS16-tagged return address: 0x%x",
                readRegister(thread, ra, language)));
        thread.stepInstruction();
        long returnPc = thread.getCounter().getOffset();
        require(isaMode(thread, isa) == 1,
            "native jr ra did not restore MIPS16 mode");
        require(returnPc == BASE + 6,
            String.format("unexpected MIPS16 return PC: 0x%x", returnPc));

        println(String.format(
            "MIPS NATIVE MODE PASS jalx=0x%08x mips32=0x%08x jr-ra-return=0x%08x mode=16-bit v0=0x%08x",
            BASE, BASE + 8, returnPc,
            readRegister(thread, v0, language)));
    }
}

// Execute the candidate NORMBREC entry prefix with native MIPS mode changes.
// This is an offline p-code check; the candidate load address is not proven.
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

public class RunNormBootPathProbe extends GhidraScript {
    private static final long BASE = 0xbfc08000L;
    private static final long ENTRY = BASE + 0x10;
    private static final long MIPS16_ENTRY = BASE + 0x28;
    private static final long EXPECTED_STACK = 0x9fc0c000L;
    private static final long RETURN_SENTINEL = 0x00700001L;
    private static final String EXPECTED_SHA256 =
        "dacb549733c6af1727ed2dd8efe091ff6386ef3a111329f5407475ee620c5ace";

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long reg(PcodeThread<byte[]> thread, Register register,
            SleighLanguage language) {
        byte[] value = thread.getState().getVar(register, Reason.INSPECT);
        return Utils.bytesToLong(value, register.getMinimumByteSize(),
            language.isBigEndian()) & 0xffffffffL;
    }

    private long pc(PcodeThread<byte[]> thread) {
        return thread.getCounter().getOffset() & 0xffffffffL;
    }

    private long mode(PcodeThread<byte[]> thread, Register register) {
        RegisterValue value = thread.getContext().getRegisterValue(register);
        return value.getUnsignedValueIgnoreMask().longValue() & 1L;
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunNormBootPathProbe.java NORMBREC.BIN");
        byte[] image = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        require(image.length == 0x200,
            String.format("unexpected NORMBREC size: 0x%x", image.length));
        String hash = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(image));
        require(EXPECTED_SHA256.equals(hash),
            "NORMBREC SHA-256 mismatch: " + hash);

        SleighLanguage language = (SleighLanguage)currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register sp = language.getRegister("sp");
        Register ra = language.getRegister("ra");
        require(isa != null && relp != null && sp != null && ra != null,
            "MIPS language is missing ISA_MODE, RELP, sp, or ra");

        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, BASE, image.length, true, image);
        PcodeThread<byte[]> thread = emulator.newThread();
        PcodeUseropLibrary<byte[]> library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(
            SleighProgramCompiler.compileProgram(language, "normboot-init",
                "sp = 0; ra = 0x00700001;", library), library);
        RegisterValue mips32e = new RegisterValue(isa, BigInteger.ZERO)
            .assign(relp, new RegisterValue(relp, BigInteger.ONE));
        thread.overrideContext(mips32e);
        thread.overrideCounter(space.getAddress(ENTRY));

        require(mode(thread, isa) == 0 && mode(thread, relp) == 1,
            "probe did not begin in MIPS32 with the MIPS16e context enabled");
        thread.stepInstruction();
        require(pc(thread) == BASE + 0x18 && mode(thread, isa) == 0,
            String.format("entry jump mismatch: pc=0x%08x mode=%d",
                pc(thread), mode(thread, isa)));

        thread.stepInstruction();
        thread.stepInstruction();
        require(pc(thread) == BASE + 0x20 && reg(thread, sp, language) == EXPECTED_STACK,
            String.format("stack setup mismatch: pc=0x%08x sp=0x%08x",
                pc(thread), reg(thread, sp, language)));

        thread.stepInstruction();
        require(pc(thread) == MIPS16_ENTRY && mode(thread, isa) == 1
                && mode(thread, relp) == 1,
            String.format("jalx transition mismatch: pc=0x%08x isa=%d relp=%d",
                pc(thread), mode(thread, isa), mode(thread, relp)));
        require(reg(thread, ra, language) != RETURN_SENTINEL,
            "jalx did not update the return address");

        println(String.format(
            "NORMBREC PATH P-CODE PASS hash=%s candidate-base=0x%08x entry=0x%08x stack=0x%08x jalx-target=0x%08x isa-mode=16 relp=1",
            hash, BASE, ENTRY, reg(thread, sp, language), pc(thread)));
        println("NORMBREC PATH LIMIT no-AFI-load-address no-bootloader-or-device-claim");
    }
}

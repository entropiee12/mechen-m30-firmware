// Sweep caller sensitivity to the absent fixed-ROM copy helper's return value.
// Copy effects and MIPS16 return bodies are synthetic and explicitly modeled.
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.exec.SleighProgramCompiler;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunKernelInitCopyReturnProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc322f8L;
    private static final long STOP_CALLBACK = 0x9fc27f00L;
    private static final long RAM_BASE = 0x9fc10000L;
    private static final long RAM_END = 0x9fc30000L;
    private static final long ROM_CLEAR = 0xbfc045f4L;
    private static final long ROM_COPY = 0xbfc04634L;
    private static final long CLEAR_START = 0x9fc1a48cL;
    private static final int CLEAR_LENGTH = 0x7f4;
    private static final int MAX_STEPS = 12000;
    private static final byte[] MIPS16_RETURN_STUB = {(byte)0xa0, (byte)0xe8};

    private static final class CaseResult {
        private final String label;
        private final List<String> calls;
        private final long callbackA0;
        private final long callbackA1;
        private final long callbackA2;
        private final long callbackA3;
        private final String copiedBytesSha256;
        private final int steps;

        private CaseResult(String label, List<String> calls, long callbackA0,
                long callbackA1, long callbackA2, long callbackA3,
                String copiedBytesSha256, int steps) {
            this.label = label;
            this.calls = calls;
            this.callbackA0 = callbackA0;
            this.callbackA1 = callbackA1;
            this.callbackA2 = callbackA2;
            this.callbackA3 = callbackA3;
            this.copiedBytesSha256 = copiedBytesSha256;
            this.steps = steps;
        }

        private String observable() {
            return calls + ";callback=" + String.format("%08x,%08x,%08x,%08x",
                callbackA0, callbackA1, callbackA2, callbackA3)
                + ";copied-sha256=" + copiedBytesSha256 + ";steps=" + steps;
        }
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
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

    private void fill(PcodeEmulator emulator, AddressSpace space,
            long address, int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        emulator.getSharedState().setVar(space, address, length, true, bytes);
    }

    private void copy(PcodeEmulator emulator, AddressSpace space,
            long destination, long source, int length) {
        byte[] bytes = emulator.getSharedState().getVar(space, source,
            length, true, Reason.INSPECT);
        emulator.getSharedState().setVar(space, destination,
            length, true, bytes);
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private CaseResult runCase(byte[] image, String label,
            int selectedCopyCall, long selectedReturn) throws Exception {
        SleighLanguage language = (SleighLanguage)currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register ra = language.getRegister("ra");
        Register v0 = language.getRegister("v0");
        require(isa != null && relp != null && ra != null && v0 != null,
            "MIPS language is missing required registers");

        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, 0xbfc32000L,
            image.length, true, image);
        emulator.getSharedState().setVar(space, RAM_BASE,
            (int)(RAM_END - RAM_BASE), true, new byte[(int)(RAM_END - RAM_BASE)]);
        fill(emulator, space, CLEAR_START, CLEAR_LENGTH, (byte)0xa5);
        emulator.getSharedState().setVar(space, ROM_CLEAR,
            MIPS16_RETURN_STUB.length, true, MIPS16_RETURN_STUB);
        emulator.getSharedState().setVar(space, ROM_COPY,
            MIPS16_RETURN_STUB.length, true, MIPS16_RETURN_STUB);

        byte[] source = new byte[0x208];
        Arrays.fill(source, (byte)0x5a);
        source[0] = 0x00;
        source[1] = 0x7f;
        source[2] = (byte)0xc2;
        source[3] = (byte)0x9f;
        emulator.getSharedState().setVar(space, 0x9fc12000L,
            source.length, true, source);

        PcodeThread<byte[]> thread = emulator.newThread();
        var library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(SleighProgramCompiler.compileProgram(language,
            "kernel-init-copy-return-sweep",
            "sp = 0x9fc2ff00; s8 = 0x9fc2fe00; a0 = 0x01010101; "
                + "a1 = 0x02020202; a2 = 0x03030303; a3 = 0x04040404; "
                + "ra = 0x00700100; v0 = 0;", library), library);
        thread.overrideContext(new RegisterValue(isa, java.math.BigInteger.ZERO)
            .assign(relp, new RegisterValue(relp, java.math.BigInteger.ONE)));
        thread.overrideCounter(space.getAddress(ENTRY));

        List<String> calls = new ArrayList<>();
        int copyCall = 0;
        long callbackA0 = 0;
        long callbackA1 = 0;
        long callbackA2 = 0;
        long callbackA3 = 0;
        int steps = 0;
        for (; steps < MAX_STEPS; steps++) {
            long address = thread.getCounter().getOffset() & 0xffffffffL;
            if (address == STOP_CALLBACK) {
                require(mode(thread, isa) == 0 && mode(thread, relp) == 1,
                    String.format("callback mode mismatch ISA=%d RELP=%d",
                        mode(thread, isa), mode(thread, relp)));
                callbackA0 = reg(thread, language.getRegister("a0"), language);
                callbackA1 = reg(thread, language.getRegister("a1"), language);
                callbackA2 = reg(thread, language.getRegister("a2"), language);
                callbackA3 = reg(thread, language.getRegister("a3"), language);
                break;
            }

            if (address == ROM_CLEAR || address == ROM_COPY) {
                require(mode(thread, isa) == 1 && mode(thread, relp) == 1,
                    String.format("ROM helper mode mismatch at %08x", address));
                long returnAddress = reg(thread, ra, language);
                long a0 = reg(thread, language.getRegister("a0"), language);
                long a1 = reg(thread, language.getRegister("a1"), language);
                long a2 = reg(thread, language.getRegister("a2"), language);
                long a3 = reg(thread, language.getRegister("a3"), language);
                require((returnAddress & 3L) == 0,
                    String.format("unaligned helper return address %08x", returnAddress));
                calls.add(String.format("%08x:ra=%08x:%08x,%08x,%08x,%08x",
                    address, returnAddress, a0, a1, a2, a3));

                long helperReturn = 0;
                if (address == ROM_CLEAR) {
                    require(a0 == CLEAR_START && a1 == 0 && a2 == 0x7f4,
                        String.format("unexpected clear args %08x,%08x,%08x",
                            a0, a1, a2));
                    fill(emulator, space, a0, (int)a2, (byte)a1);
                } else {
                    copyCall++;
                    require(copyCall <= 3 && a2 <= 0x1000,
                        String.format("unexpected copy call=%d len=%08x", copyCall, a2));
                    copy(emulator, space, a0, a1, (int)a2);
                    if (copyCall == selectedCopyCall) {
                        helperReturn = selectedReturn;
                    }
                }

                thread.getState().setVar(v0, emulator.getArithmetic().fromConst(
                    helperReturn, v0.getMinimumByteSize()));
                thread.stepInstruction();
                require(thread.getCounter().getOffset() == returnAddress
                        && mode(thread, isa) == 0 && mode(thread, relp) == 1,
                    String.format("synthetic MIPS16 return mismatch at %08x", address));
                continue;
            }

            if (address < ENTRY || address >= 0xbfc33000L
                    || (address >= RAM_BASE && address < RAM_END)) {
                throw new Exception(String.format(
                    "unexpected control flow pc=%08x after %s", address, label));
            }
            thread.stepInstruction();
        }

        require(steps < MAX_STEPS, label + " exceeded step limit");
        require(copyCall == 3 && calls.size() == 4,
            label + " did not execute clear + all three copy calls: " + calls);
        require(callbackA0 == 0xfffffff0L && callbackA1 == 0xbfc0855dL
                && callbackA2 == 0 && callbackA3 == 0x04040404L,
            String.format("%s callback args=%08x,%08x,%08x,%08x",
                label, callbackA0, callbackA1, callbackA2, callbackA3));
        require(calls.get(0).startsWith("bfc045f4:ra=bfc32348:9fc1a48c,00000000,000007f4,"),
            label + " initial clear call changed: " + calls.get(0));
        String[] copyExpected = {
            "9fc1aad8,9fc12000,00000014,",
            "9fc1a980,9fc12014,00000020,",
            "bfc080f8,9fc12200,00000004,"
        };
        for (int index = 0; index < copyExpected.length; index++) {
            require(calls.get(index + 1).startsWith("bfc04634:")
                    && calls.get(index + 1).contains(copyExpected[index]),
                label + " copy call " + (index + 1) + " changed: " + calls.get(index + 1));
        }

        byte[] first = emulator.getSharedState().getVar(space, 0x9fc1aad8L,
            0x14, true, Reason.INSPECT);
        byte[] second = emulator.getSharedState().getVar(space, 0x9fc1a980L,
            0x20, true, Reason.INSPECT);
        byte[] third = emulator.getSharedState().getVar(space, 0xbfc080f8L,
            4, true, Reason.INSPECT);
        ByteArrayOutputStream finalCopies = new ByteArrayOutputStream();
        finalCopies.write(first);
        finalCopies.write(second);
        finalCopies.write(third);
        return new CaseResult(label, calls, callbackA0, callbackA1,
            callbackA2, callbackA3, sha256(finalCopies.toByteArray()), steps);
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKernelInitCopyReturnProbe.java KER_INIT.BIN");
        byte[] image = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        require(image.length > 0x800, "ker_init.bin truncated before FUN_bfc322f8");

        CaseResult baseline = runCase(image, "baseline-copy-returns-zero", 0, 0);
        println("COPY RETURN CASE label=" + baseline.label + " steps=" + baseline.steps
            + " observation=" + baseline.observable());
        int cases = 1;
        long[] values = {1, 0xffffffffL, 0xdeadbeefL};
        for (int copyCall = 1; copyCall <= 3; copyCall++) {
            for (long value : values) {
                String label = String.format("copy%d-return-%08x", copyCall, value);
                CaseResult result = runCase(image, label, copyCall, value);
                require(result.observable().equals(baseline.observable()),
                    label + " changed caller-visible path/state\nbaseline="
                        + baseline.observable() + "\nactual=" + result.observable());
                println("COPY RETURN CASE label=" + result.label + " steps=" + result.steps
                    + " observation=" + result.observable());
                cases++;
            }
        }
        println("KERNEL INIT COPY RETURN SWEEP PASS cases=" + cases
            + " isolated-copy-calls=3 return-values=00000001,ffffffff,deadbeef"
            + " synthetic-copy=memcpy synthetic-clear=zero synthetic-return-stub=mips16-jrc-ra");
    }
}

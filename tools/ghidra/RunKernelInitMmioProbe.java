// Execute extracted FUN_bfc32440 against seeded RAM/MMIO state.
// Fixed-ROM services return synthetically; register values are not device observations.
// clobberN-register modes mutate one caller-saved register at a synthetic return.
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
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class RunKernelInitMmioProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc32440L;
    private static final long STOP = 0x00700100L;
    private static final long RAM_BASE = 0x9fc10000L;
    private static final long RAM_END = 0x9fc30000L;
    private static final long ROM_SLEEP = 0xbfc07a50L;
    private static final long ROM_SERVICE_A = 0xbfc04fb8L;
    private static final long ROM_SERVICE_B = 0xbfc04f60L;
    private static final long MMIO_SEED = 0xa5a5a5a5L;
    private static final long RETURN_SENTINEL = 0xdeadbeefL;
    private static final int MAX_STEPS = 8000;
    private static final byte[] MIPS16_RETURN = { (byte)0xa0, (byte)0xe8 };
    private static final long[] MMIO_ADDRESSES = {
        0xc0001000L, 0xc0000104L, 0xc0120000L, 0xc0020000L,
        0xc0020048L, 0xc002004cL, 0xc0020050L, 0xc0020044L,
        0xc0020008L, 0xc0001004L, 0xc0000118L, 0xc000011cL,
        0xc002003cL, 0xc0020038L, 0xc0000114L
    };
    private static final long[] EXPECTED_MMIO = {
        0x00000003L, 0xa5a5a5a7L, 0xa5a5a5acL, 0xa5a1a5c8L,
        0xa5852525L, 0xa5a5a585L, 0xa5a5a5a5L, 0x00000001L,
        0x40942e19L, 0xa525a5a5L, 0xa52da5a5L, 0xa5a5a5e5L,
        0xa58585a5L, 0xa5a5a5c5L, 0xa405df92L
    };

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long reg(PcodeThread<byte[]> thread, Register register,
            SleighLanguage language) {
        byte[] value = thread.getState().getVar(register, Reason.INSPECT);
        return Utils.bytesToLong(value, register.getMinimumByteSize(),
            language.isBigEndian()) & 0xffffffffL;
    }

    private long word(PcodeThread<byte[]> thread, AddressSpace space,
            long at, SleighLanguage language) {
        byte[] bytes = thread.getState().getVar(space, at, 4, true, Reason.INSPECT);
        return Utils.bytesToLong(bytes, 4, language.isBigEndian()) & 0xffffffffL;
    }

    private long halfWord(PcodeThread<byte[]> thread, AddressSpace space,
            long at, SleighLanguage language) {
        byte[] bytes = thread.getState().getVar(space, at, 2, true, Reason.INSPECT);
        return Utils.bytesToLong(bytes, 2, language.isBigEndian()) & 0xffffL;
    }

    private void putWord(PcodeEmulator emulator, AddressSpace space,
            long at, long value) {
        byte[] bytes = new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        };
        emulator.getSharedState().setVar(space, at, 4, true, bytes);
    }

    private String digestHex(MessageDigest digest) {
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest())
            result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 1 || getScriptArgs().length > 2)
            throw new Exception("usage: RunKernelInitMmioProbe.java KER_INIT.BIN [zero|one|minus1|sentinel|call0..call6|clobberN-REGISTER]");
        String returnMode = getScriptArgs().length == 2 ? getScriptArgs()[1] : "zero";
        int isolatedCall = -1;
        String isolatedReturn = "sentinel";
        int clobberCall = -1;
        String clobberRegister = null;
        if (returnMode.startsWith("call")) {
            String[] parts = returnMode.split("-", 2);
            try {
                isolatedCall = Integer.parseInt(parts[0].substring(4));
            }
            catch (NumberFormatException e) {
                throw new Exception("isolated return mode must be call0 through call6");
            }
            if (parts.length == 2) isolatedReturn = parts[1];
        }
        else if (returnMode.startsWith("clobber")) {
            String[] parts = returnMode.split("-", 2);
            require(parts.length == 2, "clobber mode must be clobberN-REGISTER");
            try {
                clobberCall = Integer.parseInt(parts[0].substring(7));
            }
            catch (NumberFormatException e) {
                throw new Exception("clobber call must be clobber0 through clobber6");
            }
            clobberRegister = parts[1];
        }
        boolean validIsolatedReturn = isolatedReturn.equals("sentinel")
            || isolatedReturn.equals("one") || isolatedReturn.equals("minus1");
        require(returnMode.equals("zero") || returnMode.equals("one")
                || returnMode.equals("minus1") || returnMode.equals("sentinel")
                || (isolatedCall >= 0 && isolatedCall < 7 && validIsolatedReturn)
                || (clobberCall >= 0 && clobberCall < 7
                    && isCallerSavedRegister(clobberRegister)),
            "return mode must be zero, one, minus1, sentinel, call0..call6[-one|-minus1], or clobberN-REGISTER");
        byte[] image = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        require(image.length >= 0x828, "ker_init.bin truncated before FUN_bfc32440");
        SleighLanguage language = (SleighLanguage)currentProgram.getLanguage();
        AddressSpace space = language.getDefaultSpace();
        Register isa = language.getRegister("ISA_MODE");
        Register relp = language.getRegister("RELP");
        Register ra = language.getRegister("ra");
        Register v0 = language.getRegister("v0");
        require(isa != null && relp != null && ra != null && v0 != null,
            "MIPS language is missing a required register");

        PcodeEmulator emulator = new PcodeEmulator(language);
        emulator.getSharedState().setVar(space, 0xbfc32000L, image.length, true, image);
        emulator.getSharedState().setVar(space, RAM_BASE,
            (int)(RAM_END - RAM_BASE), true, new byte[(int)(RAM_END - RAM_BASE)]);
        emulator.getSharedState().setVar(space, ROM_SLEEP,
            MIPS16_RETURN.length, true, MIPS16_RETURN);
        emulator.getSharedState().setVar(space, ROM_SERVICE_A,
            MIPS16_RETURN.length, true, MIPS16_RETURN);
        emulator.getSharedState().setVar(space, ROM_SERVICE_B,
            MIPS16_RETURN.length, true, MIPS16_RETURN);
        for (long address : MMIO_ADDRESSES)
            putWord(emulator, space, address, MMIO_SEED);

        PcodeThread<byte[]> thread = emulator.newThread();
        var library = thread.getUseropLibrary();
        require(library.getUserops().containsKey("setISAMode"),
            "native MIPS setISAMode userop was not loaded");
        thread.getExecutor().execute(SleighProgramCompiler.compileProgram(language,
            "kernel-init-mmio", "sp = 0x9fc2ff00; s8 = 0x9fc2fe00; "
                + "a0 = 0x01010101; a1 = 0x02020202; a2 = 0x03030303; "
                + "a3 = 0x04040404; ra = 0x00700100; v0 = 0;",
            library), library);
        RegisterValue mips32 = new RegisterValue(isa, BigInteger.ZERO)
            .assign(relp, new RegisterValue(relp, BigInteger.ONE));
        thread.overrideContext(mips32);
        thread.overrideCounter(space.getAddress(ENTRY));

        List<String> calls = new ArrayList<>();
        MessageDigest traceDigest = MessageDigest.getInstance("SHA-256");
        boolean returned = false;
        int steps = 0;
        for (; steps < MAX_STEPS; steps++) {
            long address = thread.getCounter().getOffset() & 0xffffffffL;
            if (address == STOP) {
                returned = true;
                break;
            }
            for (int shift = 24; shift >= 0; shift -= 8)
                traceDigest.update((byte)(address >>> shift));
            long isaMode = thread.getContext().getRegisterValue(isa)
                .getUnsignedValueIgnoreMask().longValue() & 0xffL;
            long relpMode = thread.getContext().getRegisterValue(relp)
                .getUnsignedValueIgnoreMask().longValue() & 0xffL;
            traceDigest.update((byte)isaMode);
            traceDigest.update((byte)relpMode);
            if (address == ROM_SLEEP || address == ROM_SERVICE_A
                    || address == ROM_SERVICE_B) {
                require((thread.getContext().getRegisterValue(isa)
                            .getUnsignedValueIgnoreMask().longValue() & 1L) == 1L
                        && (thread.getContext().getRegisterValue(relp)
                            .getUnsignedValueIgnoreMask().longValue() & 1L) == 1L,
                    String.format("expected native MIPS16e ROM entry at %08x", address));
                long returnAddress = reg(thread, ra, language);
                require((returnAddress & 3L) == 0,
                    String.format("unaligned ROM return address %08x", returnAddress));
                String call = String.format(
                    "%08x:ra=%08x:a0=%08x,a1=%08x,a2=%08x,a3=%08x",
                    address, returnAddress,
                    reg(thread, language.getRegister("a0"), language),
                    reg(thread, language.getRegister("a1"), language),
                    reg(thread, language.getRegister("a2"), language),
                    reg(thread, language.getRegister("a3"), language));
                calls.add(call);
                println("KERNEL INIT MMIO ROM EDGE " + call);
                int callIndex = calls.size() - 1;
                long returnValue;
                if (returnMode.startsWith("clobber") || returnMode.equals("zero")
                        || (isolatedCall >= 0 && isolatedCall != callIndex)) {
                    returnValue = 0;
                }
                else {
                    String selectedReturn = isolatedCall >= 0 ? isolatedReturn : returnMode;
                    if (selectedReturn.equals("one")) returnValue = 1;
                    else if (selectedReturn.equals("minus1")) returnValue = 0xffffffffL;
                    else returnValue = RETURN_SENTINEL;
                }
                thread.getState().setVar(v0, emulator.getArithmetic().fromConst(
                    returnValue, v0.getMinimumByteSize()));
                if (callIndex == clobberCall) {
                    Register clobbered = language.getRegister(clobberRegister);
                    long poison = 0xd3ad0000L | ((long)callIndex << 8)
                        | (clobbered.getOffset() & 0xffL);
                    thread.getState().setVar(clobbered, emulator.getArithmetic().fromConst(
                        poison, clobbered.getMinimumByteSize()));
                    println(String.format("KERNEL INIT MMIO ROM RETURN CLOBBER call=%d register=%s value=%08x",
                        callIndex, clobberRegister, poison));
                }
                thread.stepInstruction();
                require(thread.getCounter().getOffset() == returnAddress
                        && (thread.getContext().getRegisterValue(isa)
                            .getUnsignedValueIgnoreMask().longValue() & 1L) == 0L,
                    String.format("MIPS16 synthetic return failed for %08x", address));
                continue;
            }
            if (address < ENTRY || address >= 0xbfc32900L
                    || (address >= RAM_BASE && address < RAM_END))
                throw new Exception(String.format("unexpected control flow pc=%08x", address));
            thread.stepInstruction();
        }
        require(returned, "FUN_bfc32440 did not return within step limit");
        require(steps == 250, "unexpected instruction-step count: " + steps);
        require(calls.size() == 7, "expected seven fixed-ROM calls, got " + calls);
        String[] expectedCalls = {
            "bfc07a50:ra=bfc3256c:a0=13b13b12,a1=9fc1aab4,a2=03030303,a3=04040404",
            "bfc07a50:ra=bfc32590:a0=c0120000,a1=9fc1aab4,a2=03030303,a3=04040404",
            "bfc04fb8:ra=bfc325e4:a0=00000001,a1=00000000,a2=00000000,a3=04040404",
            "bfc07a50:ra=bfc32664:a0=c002004c,a1=c0020048,a2=00000000,a3=04040404",
            "bfc07a50:ra=bfc32750:a0=c0020038,a1=c0020048,a2=00000000,a3=04040404",
            "bfc07a50:ra=bfc32774:a0=c0020038,a1=c0020048,a2=00000000,a3=04040404",
            "bfc04f60:ra=bfc32814:a0=00000078,a1=00000000,a2=00000000,a3=04040404"
        };
        for (int i = 0; i < expectedCalls.length; i++) {
            if (clobberCall >= 0) {
                String expectedEdge = expectedCalls[i].split(":a0=", 2)[0];
                String actualEdge = calls.get(i).split(":a0=", 2)[0];
                require(actualEdge.equals(expectedEdge), String.format(
                    "fixed-ROM control edge %d mismatch: got %s expected %s",
                    i, actualEdge, expectedEdge));
            }
            else {
                require(calls.get(i).equals(expectedCalls[i]), String.format(
                    "fixed-ROM edge %d mismatch: got %s expected %s",
                    i, calls.get(i), expectedCalls[i]));
            }
        }
        int sleepCalls = 0, serviceACalls = 0, serviceBCalls = 0;
        for (String call : calls) {
            if (call.startsWith("bfc07a50:")) sleepCalls++;
            else if (call.startsWith("bfc04fb8:")) serviceACalls++;
            else if (call.startsWith("bfc04f60:")) serviceBCalls++;
        }
        require(sleepCalls == 5 && serviceACalls == 1 && serviceBCalls == 1,
            "unexpected fixed-ROM call mix: " + calls);
        if (clobberCall >= 0) {
            for (int i = 0; i < calls.size(); i++)
                println(String.format("KERNEL INIT MMIO ROM EDGE RESULT index=%d %s", i, calls.get(i)));
        }
        for (int i = 0; i < MMIO_ADDRESSES.length; i++) {
            long actual = word(thread, space, MMIO_ADDRESSES[i], language);
            println(String.format("KERNEL INIT MMIO STATE address=%08x initial=%08x final=%08x expected=%08x",
                MMIO_ADDRESSES[i], MMIO_SEED, actual, EXPECTED_MMIO[i]));
            require(actual == EXPECTED_MMIO[i], String.format(
                "MMIO mismatch at %08x: got %08x expected %08x",
                MMIO_ADDRESSES[i], actual, EXPECTED_MMIO[i]));
        }
        long modeByte = emulator.getSharedState().getVar(space, 0x9fc1a8a8L,
            1, true, Reason.INSPECT)[0] & 0xffL;
        long modeHalf = halfWord(thread, space, 0x9fc1a8aaL, language);
        long timingA = word(thread, space, 0x9fc1aab0L, language);
        long timingB = word(thread, space, 0x9fc1aab4L, language);
        long timingC = word(thread, space, 0x9fc1aab8L, language);
        println(String.format("KERNEL INIT MMIO RAM mode-byte=%02x mode-half=%04x timing=%08x,%08x,%08x",
            modeByte, modeHalf, timingA, timingB, timingC));
        require(modeByte == 0x1a && modeHalf == 0x001a,
            String.format("unexpected mode fields byte=%02x half=%04x", modeByte, modeHalf));
        require(timingA != 0 && timingB != 0 && timingC != 0,
            "expected nonzero derived timing fields at 0x9fc1aab0..0x9fc1aab8");
        println(String.format(
            "KERNEL INIT MMIO P-CODE PASS entry=%08x steps=%d fixed-rom-calls=%d "
            + "calls-are-synthetic=1 return-mode=%s initial-register-pattern=%08x",
            ENTRY, steps, calls.size(), returnMode, MMIO_SEED));
        println("KERNEL INIT MMIO TRACE SHA256=" + digestHex(traceDigest));
    }

    private boolean isCallerSavedRegister(String name) {
        if (name == null) return false;
        if (name.equals("v1") || name.matches("a[0-3]")) return true;
        return name.matches("t[0-9]");
    }
}

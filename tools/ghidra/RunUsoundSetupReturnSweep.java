// Sweep isolated synthetic return values through the USB Audio setup callers.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunUsoundSetupReturnSweep extends GhidraScript {
    private static final long ENTRY = 0xbfc322e4L;
    private static final long STOP = 0x700300L;
    private static final long MODULE10 = 0xbfc326a8L;
    private static final long MODULE0F = 0xbfc326acL;
    private static final long DATA = 0x9fc33c00L;
    private static final int DATA_SIZE = 0x154;
    private static final int BSS_SIZE = 0xd4;
    private static final long STACK = 0x9fc35000L;

    private static final class ProfileResult {
        private final String label;
        private final List<String> calls;
        private final int steps;
        private final String rate;
        private final String traceSha256;

        private ProfileResult(String label, List<String> calls, int steps,
                String rate, String traceSha256) {
            this.label = label;
            this.calls = calls;
            this.steps = steps;
            this.rate = rate;
            this.traceSha256 = traceSha256;
        }

        private String observable() {
            return "steps=" + steps + ";calls=" + normalizedCalls()
                + ";rate=" + rate + ";pc-mode-sha256=" + traceSha256;
        }

        private List<String> normalizedCalls() {
            List<String> normalized = new ArrayList<>();
            for (String call : calls)
                normalized.add(call.replaceAll(",ret=[0-9a-f]{8}$", ""));
            return normalized;
        }

        private String report() {
            return "profile=" + label + " " + observable();
        }
    }

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private void resetRegion(Memory memory, long start, int size) throws Exception {
        memory.setBytes(address(start), new byte[size]);
    }

    private ProfileResult runProfile(
        Memory memory,
        byte[] initialData,
        int targetCall,
        long selectedReturn
    ) throws Exception {
        memory.setBytes(address(DATA), initialData);
        resetRegion(memory, STACK, 0x1000);
        resetRegion(memory, 0xc0000000L, 0x3000);
        resetRegion(memory, 0xc0020000L, 0x1000);
        resetRegion(memory, 0xc0050000L, 0x1000);
        resetRegion(memory, 0xc0080000L, 0x5000);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> calls = new ArrayList<>();
        MessageDigest traceDigest = MessageDigest.getInstance("SHA-256");
        int callIndex = 0;
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emulator.writeRegister("sp", STACK + 0x800);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("a0", DATA + 0x20);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 250000; steps++) {
                long rawPc = register(emulator, "pc");
                long pc = rawPc & ~1L;
                if (pc == STOP) {
                    byte[] copiedRate = new byte[3];
                    memory.getBytes(address(DATA + 0xa3), copiedRate);
                    byte[] expectedRate = Arrays.copyOfRange(initialData, 8, 11);
                    if (!Arrays.equals(copiedRate, expectedRate)) {
                        throw new Exception(String.format(
                            "module-0x10 rate copy mismatch: got=%02x%02x%02x expected=%02x%02x%02x",
                            copiedRate[0] & 0xff, copiedRate[1] & 0xff, copiedRate[2] & 0xff,
                            expectedRate[0] & 0xff, expectedRate[1] & 0xff, expectedRate[2] & 0xff
                        ));
                    }
                    if (callIndex != 11)
                        throw new Exception(String.format(
                            "%s stopped after %d intercepted calls; expected 11",
                            profileName(targetCall, selectedReturn), callIndex));
                    if (targetCall > 0 && targetCall > callIndex)
                        throw new Exception(String.format(
                            "%s stopped before selected call %d",
                            profileName(targetCall, selectedReturn), targetCall));
                    return new ProfileResult(profileName(targetCall, selectedReturn),
                        new ArrayList<>(calls), steps,
                        String.format("%02x%02x%02x", copiedRate[0] & 0xff,
                            copiedRate[1] & 0xff, copiedRate[2] & 0xff),
                        digestHex(traceDigest));
                }

                for (int shift = 24; shift >= 0; shift -= 8)
                    traceDigest.update((byte)(rawPc >>> shift));
                traceDigest.update((byte)(register(emulator, "ISA_MODE") & 0xffL));
                traceDigest.update((byte)(register(emulator, "RELP") & 0xffL));

                if (pc == MODULE0F || pc == MODULE10) {
                    long a0 = register(emulator, "a0");
                    long a1 = register(emulator, "a1");
                    long a2 = register(emulator, "a2");
                    long a3 = register(emulator, "a3");
                    long ra = register(emulator, "ra");
                    int module = pc == MODULE0F ? 0x0f : 0x10;
                    callIndex++;
                    long returnValue = callIndex == targetCall ? selectedReturn : 0;
                    if (module == 0x10) {
                        if (a2 != 3 || a3 != 0) {
                            throw new Exception(String.format(
                                "unexpected module-0x10 copy arguments: %08x/%08x/%08x/%08x",
                                a0, a1, a2, a3
                            ));
                        }

                        byte[] source = new byte[(int)a2];
                        memory.getBytes(address(a1), source);
                        memory.setBytes(address(a0), source);
                    }
                    calls.add(String.format(
                        "%d:module=0x%02x,args=%08x/%08x/%08x/%08x,ra=%08x,ret=%08x",
                        callIndex, module, a0, a1, a2, a3, ra, returnValue
                    ));
                    emulator.writeRegister("v0", returnValue);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "%s emulator stopped at %08x after %d calls: %s",
                        profileName(targetCall, selectedReturn), pc, callIndex,
                        emulator.getLastError()));
                }
            }

            throw new Exception(String.format(
                "%s exceeded 250000 emulator steps after %d calls",
                profileName(targetCall, selectedReturn), callIndex));
        }
        finally {
            emulator.dispose();
        }
    }

    private String profileName(int targetCall, long selectedReturn) {
        if (targetCall == 0) {
            return "baseline";
        }

        return String.format("call=%d-return=%08x", targetCall, selectedReturn);
    }

    private String digestHex(MessageDigest digest) {
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest())
            result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: RunUsoundSetupReturnSweep.java USOUND.DRV");
        }

        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        if (driver.length < 0x2a54) {
            throw new Exception("usound.drv is truncated");
        }

        Memory memory = currentProgram.getMemory();
        byte[] data = new byte[DATA_SIZE + BSS_SIZE];
        System.arraycopy(driver, 0x2900, data, 0, DATA_SIZE);
        var dataBlock = memory.createInitializedBlock(
            "usound_setup_sweep_data_bss", address(DATA), new ByteArrayInputStream(data),
            data.length, monitor, false
        );
        dataBlock.setRead(true);
        dataBlock.setWrite(true);

        var stackBlock = memory.createInitializedBlock(
            "usound_setup_sweep_stack", address(STACK),
            new ByteArrayInputStream(new byte[0x1000]), 0x1000, monitor, false
        );
        stackBlock.setRead(true);
        stackBlock.setWrite(true);

        mapWritableRegion(memory, "usound_setup_sweep_mmio0", 0xc0000000L, 0x3000);
        mapWritableRegion(memory, "usound_setup_sweep_mmio2", 0xc0020000L, 0x1000);
        mapWritableRegion(memory, "usound_setup_sweep_mmio5", 0xc0050000L, 0x1000);
        mapWritableRegion(memory, "usound_setup_sweep_mmio8", 0xc0080000L, 0x5000);

        List<ProfileResult> profiles = new ArrayList<>();
        ProfileResult baseline = runProfile(memory, data, 0, 0);
        List<String> expectedCalls = Arrays.asList(
            "1:module=0x0f,args=00000001/00000000/00000000/0000001d,ra=bfc32325",
            "2:module=0x0f,args=00000001/00000000/00000000/0000001d,ra=bfc3233f",
            "3:module=0x0f,args=00000001/00000000/00000000/0000001d,ra=bfc3236f",
            "4:module=0x0f,args=00000001/00000000/00000000/0000001d,ra=bfc32389",
            "5:module=0x0f,args=0000000a/00000000/00000000/0000001d,ra=bfc323b3",
            "6:module=0x0f,args=00000005/00000000/00000000/0000001d,ra=bfc323cd",
            "7:module=0x0f,args=0000000a/bfc31115/00000000/00000014,ra=bfc323f9",
            "8:module=0x0f,args=00000014/bfc3125d/00000000/00000014,ra=bfc32407",
            "9:module=0x0f,args=bfc311a1/00000002/00000000/00000036,ra=bfc324a3",
            "10:module=0x0f,args=9fc357c1/0000004f/00000001/0000001b,ra=bfc3209b",
            "11:module=0x10,args=9fc33ca3/9fc33c08/00000003/00000000,ra=bfc321b7"
        );
        require(baseline.steps == 1368,
            "baseline setup instruction count changed: " + baseline.steps);
        require(baseline.normalizedCalls().equals(expectedCalls),
            "baseline fixed-ROM call trace changed: " + baseline.normalizedCalls());
        require("80bb00".equals(baseline.rate),
            "baseline copied rate bytes changed: " + baseline.rate);
        profiles.add(baseline);
        for (int callIndex = 1; callIndex <= 11; callIndex++) {
            profiles.add(runProfile(memory, data, callIndex, 1));
            profiles.add(runProfile(memory, data, callIndex, 0xffffffffL));
        }

        for (ProfileResult profile : profiles) {
            require(profile.observable().equals(baseline.observable()),
                profile.label + " changed checked caller observations\nbaseline="
                    + baseline.observable() + "\nactual=" + profile.observable());
            println("USOUND SETUP RETURN SWEEP " + profile.report());
        }
        println(String.format(
            "USOUND SETUP RETURN SWEEP PASS profiles=%d isolated-calls=11 return-values=00000001,ffffffff module-0x10-copy=synthetic-and-verified synthetic-module-return=0-except-selected synthetic-mmio=zeroed",
            profiles.size()
        ));
    }

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size
    ) throws Exception {
        var block = memory.createInitializedBlock(
            name, address(start), new ByteArrayInputStream(new byte[size]), size, monitor, false
        );
        block.setRead(true);
        block.setWrite(true);
    }
}

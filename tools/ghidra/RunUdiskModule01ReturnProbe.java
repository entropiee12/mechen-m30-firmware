// Sweep the return from UDisk's only module-0x01 call in the setup path.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class RunUdiskModule01ReturnProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc32abcL;
    private static final long STOP = 0x700300L;
    private static final long SERVICE_TRAPS = 0xbfc32e42L;
    private static final long DATA = 0x9fc33c00L;
    private static final int DATA_SIZE = 0x154;
    private static final int STATE_SIZE = 0x300;
    private static final long STACK = 0x9fc35000L;
    private static final long MMIO0 = 0xc0000000L;
    private static final long MMIO2 = 0xc0020000L;
    private static final long MMIO8 = 0xc0080000L;
    private byte[] baselineState;
    private static final String EXPECTED_SHA256 =
        "afc02a4dcb383d6677eedf243448de848a534f8af67fde1508aec6dd672a14a4";

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private long readWord(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private void resetRegion(Memory memory, long start, int size) throws Exception {
        memory.setBytes(address(start), new byte[size]);
    }

    private String runProfile(Memory memory, byte[] data, long selectedReturn) throws Exception {
        memory.setBytes(address(DATA), data);
        resetRegion(memory, STACK, 0x1000);
        resetRegion(memory, MMIO0, 0x3000);
        resetRegion(memory, MMIO2, 0x1000);
        resetRegion(memory, MMIO8, 0x5000);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> calls = new ArrayList<>();
        List<String> writeTrace = new ArrayList<>();
        int callIndex = 0;
        byte[] previousData = null;
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                java.math.BigInteger.ONE);
            emulator.writeRegister("sp", STACK + 0x800);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("a0", 0);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.writeRegister("pc", ENTRY);

            int steps;
            for (steps = 0; steps < 300000; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    break;
                }

                if (pc == SERVICE_TRAPS || pc == SERVICE_TRAPS + 4
                    || pc == SERVICE_TRAPS + 8 || pc == SERVICE_TRAPS + 12) {
                    long module = register(emulator, "v1");
                    long a0 = register(emulator, "a0");
                    long a1 = register(emulator, "a1");
                    long a2 = register(emulator, "a2");
                    long a3 = register(emulator, "a3");
                    long ra = register(emulator, "ra");
                    callIndex++;

                    boolean selected = callIndex == 11;
                    long returnValue = selected ? selectedReturn : 0;
                    if (selected) {
                        require(module == 1 && a0 == 0 && a1 == 0 && a2 == 0 && a3 == 3,
                            String.format(
                                "unexpected module-0x01 call: module=%x args=%08x/%08x/%08x/%08x",
                                module, a0, a1, a2, a3
                            ));
                    }

                    calls.add(String.format(
                        "%d:%02x:%08x/%08x/%08x/%08x:%08x",
                        callIndex, module, a0, a1, a2, a3, ra
                    ));
                    emulator.writeRegister("v0", returnValue);
                    emulator.writeRegister("pc", ra & ~1L);
                    if (selected) {
                        previousData = emulator.readMemory(address(DATA), STATE_SIZE);
                    }
                    continue;
                }

                if (pc >= 0xbfc30000L && pc < 0xbfc33600L) {
                    emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                        java.math.BigInteger.ONE);
                }

                boolean trackWrites = previousData != null;
                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "UDisk setup stopped at 0x%08x: %s", pc, emulator.getLastError()
                    ));
                }

                if (trackWrites) {
                    byte[] currentData = emulator.readMemory(address(DATA), STATE_SIZE);
                    String changed = changedBytes(previousData, currentData, DATA);
                    if (!"none".equals(changed)) {
                        writeTrace.add(String.format("%08x:%s", pc, changed));
                        previousData = currentData;
                    }
                }
            }

            require(steps < 300000, "UDisk setup exceeded instruction limit");
            require(callIndex == 11, "expected 11 module calls, observed " + callIndex);
            require(calls.get(10).startsWith("11:01:00000000/00000000/00000000/00000003:"),
                "last call was not module-0x01 op 3: " + calls.get(10));

            byte[] finalState = emulator.readMemory(address(DATA), STATE_SIZE);
            String stateChanges = changedBytes(baselineState, finalState, DATA);
            long returnedWord = readWord(emulator, DATA + 0x20);
            long mirroredWord = readWord(emulator, DATA + 0x28);
            long decrementedWord = readWord(emulator, DATA + 0x14);
            long expectedStored = Integer.reverseBytes((int)selectedReturn) & 0xffffffffL;
            long expectedDecremented = Integer.reverseBytes(
                (int)((selectedReturn - 1) & 0xffffffffL)) & 0xffffffffL;
            require(returnedWord == expectedStored && mirroredWord == expectedStored,
                String.format("return copies mismatch: expected=%08x actual=%08x/%08x",
                    expectedStored, returnedWord, mirroredWord));
            require(decrementedWord == expectedDecremented,
                String.format("decremented return mismatch: expected=%08x actual=%08x",
                    expectedDecremented, decrementedWord));
            if (baselineState == null) {
                baselineState = finalState.clone();
                stateChanges = "baseline";
            }

            return String.format(
                "return=%08x stored=%08x/%08x decremented=%08x steps=%d calls=%d call11=%s state-diff=%s write-trace=%s",
                selectedReturn,
                returnedWord,
                mirroredWord,
                decrementedWord,
                steps,
                callIndex,
                calls.get(10),
                stateChanges,
                String.join(";", writeTrace)
            );
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunUdiskModule01ReturnProbe.java UDISK.DRV");
        byte[] driver = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "udisk.drv SHA-256 mismatch: " + sha256);
        require(driver.length == 0x3800, "unexpected udisk.drv size: " + driver.length);

        Memory memory = currentProgram.getMemory();
        byte[] data = new byte[STATE_SIZE];
        System.arraycopy(driver, 0x2900, data, 0, DATA_SIZE);
        mapWritableRegion(memory, "udisk_module01_data_bss", DATA, data.length, data);
        mapWritableRegion(memory, "udisk_module01_stack", STACK, 0x1000, new byte[0x1000]);
        mapWritableRegion(memory, "udisk_module01_mmio0", MMIO0, 0x3000, new byte[0x3000]);
        mapWritableRegion(memory, "udisk_module01_mmio2", MMIO2, 0x1000, new byte[0x1000]);
        mapWritableRegion(memory, "udisk_module01_mmio8", MMIO8, 0x5000, new byte[0x5000]);

        long[] returns = {0, 1, 2, 3, 0xffffffffL, 0xdeadbeefL};
        List<String> results = new ArrayList<>();
        for (long returnValue : returns) {
            results.add(runProfile(memory, data, returnValue));
        }

        for (String result : results) {
            println("UDISK MODULE-01 RETURN PROFILE " + result);
        }
        println("UDISK MODULE-01 RETURN SWEEP COMPLETE profiles=6 target=call11/module01/op3 "
            + "module0f-return=0 synthetic-mmio=zeroed");
    }

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size,
        byte[] bytes
    ) throws Exception {
        var block = memory.createInitializedBlock(
            name, address(start), new ByteArrayInputStream(bytes), size, monitor, false
        );
        block.setRead(true);
        block.setWrite(true);
    }

    private String changedBytes(byte[] baseline, byte[] current, long start) {
        if (baseline == null) {
            return "baseline";
        }

        List<String> changes = new ArrayList<>();
        for (int offset = 0; offset < baseline.length; offset++) {
            if (baseline[offset] != current[offset]) {
                changes.add(String.format("%08x:%02x>%02x",
                    start + offset,
                    baseline[offset] & 0xff,
                    current[offset] & 0xff));
            }
        }

        return changes.isEmpty() ? "none" : String.join(",", changes);
    }
}

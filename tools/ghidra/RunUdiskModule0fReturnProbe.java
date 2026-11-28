// Sweep the returns from UDisk's module-0x0f operations 9 and 10.
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

public class RunUdiskModule0fReturnProbe extends GhidraScript {
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
    private int targetCallIndex;
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
        int callIndex = 0;
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

                    boolean selected = callIndex == targetCallIndex;
                    long returnValue = selected ? selectedReturn : 0;
                    if (selected) {
                        long expectedA2 = targetCallIndex == 10 ? 0xbfc3247cL : 0;
                        long expectedA3 = targetCallIndex == 10 ? 10 : 9;
                        require(module == 0x0f && a0 == 1 && a1 == 0
                                && a2 == expectedA2 && a3 == expectedA3,
                            String.format(
                                "unexpected module-0x0f call: module=%x args=%08x/%08x/%08x/%08x",
                                module, a0, a1, a2, a3
                            ));
                    }

                    calls.add(String.format(
                        "%d:%02x:%08x/%08x/%08x/%08x:%08x",
                        callIndex, module, a0, a1, a2, a3, ra
                    ));
                    emulator.writeRegister("v0", returnValue);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (pc >= 0xbfc30000L && pc < 0xbfc33600L) {
                    emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                        java.math.BigInteger.ONE);
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "UDisk setup stopped at 0x%08x: %s", pc, emulator.getLastError()
                    ));
                }

            }

            require(steps < 300000, "UDisk setup exceeded instruction limit");
            require(callIndex >= targetCallIndex,
                "target module call was not reached: index=" + targetCallIndex);
            int expectedDownstreamCalls = selectedReturn == 0
                ? (targetCallIndex == 9 ? 2 : 1)
                : 0;
            int downstreamCalls = callIndex - targetCallIndex;
            require(downstreamCalls == expectedDownstreamCalls,
                String.format("return %08x reached %d downstream calls; expected %d",
                    selectedReturn, downstreamCalls, expectedDownstreamCalls));

            return String.format(
                "target-call=%d return=%08x steps=%d total-calls=%d downstream-calls=%d target=%s",
                targetCallIndex,
                selectedReturn,
                steps,
                callIndex,
                downstreamCalls,
                calls.get(targetCallIndex - 1)
            );
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 2,
            "usage: RunUdiskModule0fReturnProbe.java UDISK.DRV target-call-index(9|10)");
        byte[] driver = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "udisk.drv SHA-256 mismatch: " + sha256);
        require(driver.length == 0x3800, "unexpected udisk.drv size: " + driver.length);
        targetCallIndex = Integer.parseInt(getScriptArgs()[1]);
        require(targetCallIndex == 9 || targetCallIndex == 10,
            "target call index must be 9 or 10");

        Memory memory = currentProgram.getMemory();
        byte[] data = new byte[STATE_SIZE];
        System.arraycopy(driver, 0x2900, data, 0, DATA_SIZE);
        mapWritableRegion(memory, "udisk_module0f_data_bss", DATA, data.length, data);
        mapWritableRegion(memory, "udisk_module0f_stack", STACK, 0x1000, new byte[0x1000]);
        mapWritableRegion(memory, "udisk_module0f_mmio0", MMIO0, 0x3000, new byte[0x3000]);
        mapWritableRegion(memory, "udisk_module0f_mmio2", MMIO2, 0x1000, new byte[0x1000]);
        mapWritableRegion(memory, "udisk_module0f_mmio8", MMIO8, 0x5000, new byte[0x5000]);

        long[] returns = {0, 1, 2, 0xffffffffL};
        List<String> results = new ArrayList<>();
        for (long returnValue : returns) {
            results.add(runProfile(memory, data, returnValue));
        }

        for (String result : results) {
            println("UDISK MODULE-0F RETURN PROFILE " + result);
        }
        println("UDISK MODULE-0F RETURN SWEEP COMPLETE profiles=4 target-call=" + targetCallIndex + " "
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

}

// Measure the key driver's caller-side contract for module 0x0f operation 24.
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

public class RunKeyModule0fOp24ReturnProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc101f4L;
    private static final long STOP = 0x700300L;
    private static final long SERVICE_TRAP = 0xbfc10006L;
    private static final long STACK = 0x10003f00L;
    private static final long PARAM = 0x10002000L;
    private static final String EXPECTED_SHA256 =
        "858d56ff19106fccb75e35552c9cecca99dce0579c7daac5dce376d742f67ba1";

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

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size,
        byte[] initial
    ) throws Exception {
        var block = memory.createInitializedBlock(
            name, address(start), new ByteArrayInputStream(initial), size, monitor, false
        );
        block.setRead(true);
        block.setWrite(true);
    }

    private String runProfile(Memory memory, byte[] parameter, long syntheticReturn)
        throws Exception {
        memory.setBytes(address(PARAM), parameter);
        memory.setBytes(address(STACK), new byte[0x200]);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> calls = new ArrayList<>();
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                java.math.BigInteger.ONE);
            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", PARAM);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.writeRegister("pc", ENTRY);

            int steps;
            for (steps = 0; steps < 2000; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    break;
                }

                if (pc == SERVICE_TRAP) {
                    long module = register(emulator, "v1");
                    long a0 = register(emulator, "a0");
                    long a1 = register(emulator, "a1");
                    long a2 = register(emulator, "a2");
                    long a3 = register(emulator, "a3");
                    long ra = register(emulator, "ra");
                    require(module == 0x0f && a0 == 6 && a1 == STACK - 16
                            && a2 == 0 && a3 == 24,
                        String.format("unexpected service ABI: module=%x args=%08x/%08x/%08x/%08x",
                            module, a0, a1, a2, a3));
                    require((ra & 1L) == 1L,
                        String.format("module-0x0f op-24 return address lacks MIPS16 tag: %08x", ra));
                    calls.add(String.format("module=%02x args=%08x/%08x/%08x/%08x ra=%08x",
                        module, a0, a1, a2, a3, ra));
                    emulator.writeRegister("v0", syntheticReturn);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (pc >= 0xbfc10000L && pc < 0xbfc10e00L) {
                    emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                        java.math.BigInteger.ONE);
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("key wrapper stopped at 0x%08x: %s",
                        pc, emulator.getLastError()));
                }
            }

            require(steps < 2000, "key wrapper exceeded instruction limit");
            require(calls.size() == 1, "expected one module call, observed " + calls.size());
            long wrapperResult = register(emulator, "v0");
            long expectedResult = syntheticReturn == 0 ? 1 : 0;
            require(wrapperResult == expectedResult,
                String.format("wrapper result=%08x expected=%08x for service return=%08x",
                    wrapperResult, expectedResult, syntheticReturn));

            return String.format("service-return=%08x wrapper-return=%08x steps=%d call={%s}",
                syntheticReturn, wrapperResult, steps, calls.get(0));
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKeyModule0fOp24ReturnProbe.java KEY.DRV");
        byte[] driver = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);

        Memory memory = currentProgram.getMemory();
        mapWritableRegion(memory, "key_op24_param", PARAM, 0x20, new byte[0x20]);
        mapWritableRegion(memory, "key_op24_stack", STACK, 0x200, new byte[0x200]);

        byte[] parameter = new byte[0x20];
        long[] returns = {0, 1, 2, 0xffffffffL, 0xdeadbeefL};
        List<String> results = new ArrayList<>();
        for (long syntheticReturn : returns) {
            results.add(runProfile(memory, parameter, syntheticReturn));
        }

        for (String result : results) {
            println("KEY MODULE-0F OP-24 RETURN PROFILE " + result);
        }
        println("KEY MODULE-0F OP-24 RETURN SWEEP COMPLETE profiles=5 "
            + "synthetic-rom-return=1");
    }
}

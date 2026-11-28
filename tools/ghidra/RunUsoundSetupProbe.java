// Execute the real USB Audio setup function with synthetic MMIO and module stubs.
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
import java.util.ArrayList;
import java.util.List;

public class RunUsoundSetupProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc322e4L;
    private static final long STOP = 0x700300L;
    private static final long MODULE10 = 0xbfc326a8L;
    private static final long MODULE0F = 0xbfc326acL;
    private static final long PRODUCER = 0xbfc30dc4L;
    private static final long DATA = 0x9fc33c00L;
    private static final int DATA_SIZE = 0x154;
    private static final int BSS_SIZE = 0xd4;
    private static final long STACK = 0x9fc35000L;
    private static final List<String> calls = new ArrayList<>();
    private long eventCallback;

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private int execute(EmulatorHelper emulator) throws Exception {
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
            long pc = register(emulator, "pc") & ~1L;
            if (pc == STOP) return steps;
            if (pc == MODULE0F || pc == MODULE10) {
                long a0 = register(emulator, "a0");
                long a1 = register(emulator, "a1");
                long a2 = register(emulator, "a2");
                long a3 = register(emulator, "a3");
                long ra = register(emulator, "ra");
                int module = pc == MODULE0F ? 0x0f : 0x10;
                if (module == 0x0f && a0 == 0x0a && a2 == 0 && a3 == 0x14) {
                    require(eventCallback == 0, "duplicate USB event callback registration");
                    eventCallback = a1;
                }
                calls.add(String.format(
                    "module=0x%02x args=(a0=0x%08x,a1=0x%08x,a2=0x%08x,a3=0x%08x) ra=0x%08x",
                    module, a0, a1, a2, a3, ra
                ));
                emulator.writeRegister("v0", 0);
                emulator.writeRegister("pc", ra & ~1L);
                continue;
            }
            if (!emulator.step(monitor)) {
                throw new Exception(String.format(
                    "setup stopped at 0x%08x: %s", pc, emulator.getLastError()
                ));
            }
        }
        throw new Exception("USB Audio setup exceeded instruction limit");
    }

    private int invokeRegisteredCallback(EmulatorHelper emulator) throws Exception {
        require(eventCallback == 0xbfc31115L,
            String.format("unexpected registered event callback 0x%08x", eventCallback));
        emulator.writeMemory(address(0xc0080191L), new byte[] { 2 });
        emulator.writeRegister("sp", STACK + 0x800);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("a0", 0);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("pc", eventCallback & ~1L);
        for (int steps = 0; steps < 1000; steps++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc == PRODUCER) return steps;
            require(pc != STOP, "event callback returned before producer");
            if (!emulator.step(monitor))
                throw new Exception(String.format(
                    "event callback stopped at 0x%08x: %s", pc, emulator.getLastError()));
        }
        throw new Exception("event callback did not reach producer before step limit");
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunUsoundSetupProbe.java USOUND.DRV");
        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        if (driver.length < 0x2a54) throw new Exception("usound.drv is truncated");

        Memory memory = currentProgram.getMemory();
        byte[] data = new byte[DATA_SIZE + BSS_SIZE];
        System.arraycopy(driver, 0x2900, data, 0, DATA_SIZE);
        var dataBlock = memory.createInitializedBlock(
            "usound_setup_data_bss", address(DATA), new ByteArrayInputStream(data),
            data.length, monitor, false
        );
        dataBlock.setRead(true);
        dataBlock.setWrite(true);

        byte[] stack = new byte[0x1000];
        var stackBlock = memory.createInitializedBlock(
            "usound_setup_stack", address(STACK), new ByteArrayInputStream(stack),
            stack.length, monitor, false
        );
        stackBlock.setRead(true);
        stackBlock.setWrite(true);

        byte[] mmio0 = new byte[0x3000];
        var mmio0Block = memory.createInitializedBlock(
            "usound_setup_mmio0", address(0xc0000000L), new ByteArrayInputStream(mmio0),
            mmio0.length, monitor, false
        );
        mmio0Block.setRead(true);
        mmio0Block.setWrite(true);

        byte[] mmio2 = new byte[0x1000];
        var mmio2Block = memory.createInitializedBlock(
            "usound_setup_mmio2", address(0xc0020000L), new ByteArrayInputStream(mmio2),
            mmio2.length, monitor, false
        );
        mmio2Block.setRead(true);
        mmio2Block.setWrite(true);

        byte[] mmio5 = new byte[0x1000];
        var mmio5Block = memory.createInitializedBlock(
            "usound_setup_mmio5", address(0xc0050000L), new ByteArrayInputStream(mmio5),
            mmio5.length, monitor, false
        );
        mmio5Block.setRead(true);
        mmio5Block.setWrite(true);

        byte[] mmio8 = new byte[0x5000];
        var mmio8Block = memory.createInitializedBlock(
            "usound_setup_mmio8", address(0xc0080000L), new ByteArrayInputStream(mmio8),
            mmio8.length, monitor, false
        );
        mmio8Block.setRead(true);
        mmio8Block.setWrite(true);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            int steps = execute(emulator);
            long module0fCount = calls.stream().filter(s -> s.startsWith("module=0x0f")).count();
            long module10Count = calls.stream().filter(s -> s.startsWith("module=0x10")).count();
            for (String call : calls) println("USOUND SETUP TRAP " + call);
            require(module0fCount == 10, "unexpected module-0x0f call count: " + module0fCount);
            require(module10Count == 1, "unexpected module-0x10 call count: " + module10Count);
            require(calls.stream().anyMatch(s -> s.startsWith(
                "module=0x0f args=(a0=0x00000014,a1=0xbfc3125d,a2=0x00000000,a3=0x00000014)")),
                "setup's callback-pointer tuple was not observed");
            require(calls.stream().anyMatch(s -> s.startsWith(
                "module=0x0f args=(a0=0x0000000a,a1=0xbfc31115,a2=0x00000000,a3=0x00000014)")),
                "setup's second function-pointer tuple was not observed");
            int callbackSteps = invokeRegisteredCallback(emulator);
            println(String.format(
                "USOUND SETUP CALLBACK BRIDGE PASS registered=0x%08x producer=0x%08x callback-steps=%d event-bit=synthetic resident-dispatch=synthetic",
                eventCallback, PRODUCER, callbackSteps));
            println(String.format(
                "USOUND SETUP P-CODE PASS entry=0x%08x steps=%d module0f-calls=%d module10-calls=%d synthetic-mmio=1 synthetic-module-return=0",
                ENTRY, steps, module0fCount, module10Count
            ));
        }
        finally {
            emulator.dispose();
        }
    }
}

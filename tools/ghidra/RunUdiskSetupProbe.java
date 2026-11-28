// Execute the real USB disk setup function with synthetic MMIO and module stubs.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

public class RunUdiskSetupProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc32abcL;
    private static final long STOP = 0x700300L;
    private static final long SERVICE_TRAPS = 0xbfc32e42L;
    private static final long DATA = 0x9fc33c00L;
    private static final int DATA_SIZE = 0x154;
    private static final int BSS_SIZE = 0xd4;
    private static final long STACK = 0x9fc35000L;
    private static final List<String> calls = new ArrayList<>();
    private static final ArrayDeque<String> recent = new ArrayDeque<>();

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private void addBlock(Memory memory, String name, long start, int size, boolean execute)
        throws Exception {
        var block = memory.createInitializedBlock(name, address(start),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(execute);
    }

    private int execute(EmulatorHelper emulator) throws Exception {
        emulator.writeRegister("sp", STACK + 0x800);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("s0", 0);
        emulator.writeRegister("s1", 0);
        emulator.writeRegister("a0", 0);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("pc", ENTRY);
        for (int steps = 0; steps < 300000; steps++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc >= 0xbfc30000L && pc < 0xbfc33600L) {
                emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                    java.math.BigInteger.ONE);
            }
            recent.addLast(String.format("pc=0x%08x v0=0x%08x v1=0x%08x a0=0x%08x a1=0x%08x",
                pc, register(emulator, "v0"), register(emulator, "v1"),
                register(emulator, "a0"), register(emulator, "a1")));
            if (recent.size() > 24) recent.removeFirst();
            if (pc == STOP) return steps;
            if (pc == SERVICE_TRAPS || pc == SERVICE_TRAPS + 4
                || pc == SERVICE_TRAPS + 8 || pc == SERVICE_TRAPS + 12) {
                long a0 = register(emulator, "a0");
                long a1 = register(emulator, "a1");
                long a2 = register(emulator, "a2");
                long a3 = register(emulator, "a3");
                long ra = register(emulator, "ra");
                long module = register(emulator, "v1");
                calls.add(String.format(
                    "module=0x%02x args=(a0=0x%08x,a1=0x%08x,a2=0x%08x,a3=0x%08x) ra=0x%08x",
                    module, a0, a1, a2, a3, ra
                ));
                emulator.writeRegister("v0", 0);
                emulator.writeRegister("pc", ra & ~1L);
                continue;
            }
            if (!emulator.step(monitor)) {
                for (String line : recent) println("UDISK SETUP TRACE " + line);
                throw new Exception(String.format(
                    "setup stopped at 0x%08x: %s", pc, emulator.getLastError()
                ));
            }
        }
        throw new Exception("USB disk setup exceeded instruction limit");
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunUdiskSetupProbe.java UDISK.DRV");
        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        if (driver.length != 0x3800) throw new Exception("unexpected udisk.drv size: " + driver.length);

        Memory memory = currentProgram.getMemory();
        byte[] data = new byte[DATA_SIZE + BSS_SIZE];
        System.arraycopy(driver, 0x2900, data, 0, DATA_SIZE);
        var dataBlock = memory.createInitializedBlock("udisk_setup_data_bss",
            address(DATA), new ByteArrayInputStream(data), data.length, monitor, false);
        dataBlock.setRead(true);
        dataBlock.setWrite(true);
        addBlock(memory, "udisk_setup_stack", STACK, 0x1000, false);
        addBlock(memory, "udisk_setup_mmio0", 0xc0000000L, 0x3000, false);
        addBlock(memory, "udisk_setup_mmio2", 0xc0020000L, 0x1000, false);
        addBlock(memory, "udisk_setup_mmio8", 0xc0080000L, 0x5000, false);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                java.math.BigInteger.ONE);
            int steps = execute(emulator);
            require(calls.size() > 0, "setup did not reach module-0x0f service stub");
            long module0fCount = calls.stream().filter(s -> s.contains("module=0x0f ")).count();
            long module01Count = calls.stream().filter(s -> s.contains("module=0x01 ")).count();
            require(module0fCount == 10, "unexpected module-0x0f call count: " + module0fCount);
            require(module01Count == 1, "unexpected module-0x01 call count: " + module01Count);
            require(calls.stream().anyMatch(s -> s.contains(
                "module=0x0f args=(a0=0x0000000a,a1=0xbfc30d15,a2=0x00000000,a3=0x00000014)")),
                "setup's callback-pointer tuple was not observed");
            for (String call : calls) println("UDISK SETUP TRAP " + call);
            println(String.format(
                "UDISK SETUP P-CODE PASS entry=0x%08x steps=%d module0f-calls=%d module01-calls=%d total-calls=%d synthetic-mmio=1 synthetic-module-return=0",
                ENTRY, steps, module0fCount, module01Count, calls.size()
            ));
        }
        finally {
            emulator.dispose();
        }
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }
}

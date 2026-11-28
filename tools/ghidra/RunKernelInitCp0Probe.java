// Execute the extracted MIPS32 kernel-init entry to its first unresolved ROM edge.
// Earlier fixed-ROM calls are intercepted; the first reset helper is not run.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RunKernelInitCp0Probe extends GhidraScript {
    private static final long ENTRY = 0xbfc32000L;
    private static final long FUNCTION = 0xbfc32224L;
    private static final long STOP_PC = 0xbfc045f5L;
    // Public ADFU tooling reports a Boot-ROM view over the first 96 KiB at
    // this alias. Runtime mapping outside ADFU is unverified, so this bounded
    // probe maps only the higher addresses it uses for its synthetic stack.
    private static final long RAM_BASE = 0x9fc18000L;
    private static final int RAM_SIZE = 0x28000;
    private static final long INTC_BASE = 0xc00a0000L;
    private static final int INTC_SIZE = 0x1000;
    private static final long WD_BASE = 0xc0120000L;
    private static final int WD_SIZE = 0x1000;
    private static final int MAX_STEPS = 20000;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace()
            .getAddress(value & 0xffffffffL);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void mapRam(Memory memory, String name, long base, int size)
        throws Exception {
        var block = memory.createInitializedBlock(name, addr(base),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private long word(EmulatorHelper emu, long at) throws Exception {
        byte[] bytes = emu.readMemory(addr(at), 4);
        return (bytes[0] & 255L) | ((bytes[1] & 255L) << 8)
            | ((bytes[2] & 255L) << 16) | ((bytes[3] & 255L) << 24);
    }

    private long pcodeValue(EmulatorHelper emu, PcodeOpRaw op, int input)
        throws Exception {
        var node = op.getInput(input);
        if (node.isConstant()) return node.getOffset() & 0xffffffffL;
        Register source = currentProgram.getLanguage().getRegister(
            node.getAddress(), node.getSize());
        require(source != null, "unrecognized CP0 callback source: " + node);
        return emu.readRegister(source).longValue() & 0xffffffffL;
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunKernelInitCp0Probe.java KER_INIT.BIN");

        Memory memory = currentProgram.getMemory();
        mapRam(memory, "kernel_cp0_probe_ram", RAM_BASE, RAM_SIZE);
        mapRam(memory, "kernel_cp0_probe_intc", INTC_BASE, INTC_SIZE);
        mapRam(memory, "kernel_cp0_probe_watchdog", WD_BASE, WD_SIZE);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        List<String> romReturns = new ArrayList<>();
        Map<String, Long> cp0Writes = new LinkedHashMap<>();
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.registerCallOtherCallback("setCopReg", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) {
                    try {
                        require(op.getNumInputs() == 4,
                            "unexpected setCopReg operand count: " + op);
                        long cop = pcodeValue(emu, op, 1);
                        Register target = currentProgram.getLanguage().getRegister(
                            op.getInput(2).getAddress(), op.getInput(2).getSize());
                        require(target != null,
                            "unrecognized CP0 destination: " + op.getInput(2));
                        long value = pcodeValue(emu, op, 3);
                        require(cop == 0, "unexpected coprocessor number: " + cop);
                        emu.writeRegister(target, value);
                        cp0Writes.put(target.getName(), value);
                        return true;
                    }
                    catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                }
            });

            emu.writeRegister("sp", 0x9fc29000L);
            emu.writeRegister("s8", 0x9fc27f00L);
            emu.writeRegister("a0", 0x11111111L);
            emu.writeRegister("a1", 0x22222222L);
            emu.writeRegister("ra", 0x00700100L);
            emu.writeRegister("pc", ENTRY);

            boolean returned = false;
            boolean helperReached = false;
            long edgeA0 = 0, edgeA1 = 0, edgeA2 = 0, edgeRa = 0;
            int steps = 0;
            for (; steps < MAX_STEPS; steps++) {
                long pc = reg(emu, "pc");
                if (pc == FUNCTION) helperReached = true;
                if ((pc & ~1L) == (STOP_PC & ~1L)) {
                    returned = true;
                    edgeA0 = reg(emu, "a0");
                    edgeA1 = reg(emu, "a1");
                    edgeA2 = reg(emu, "a2");
                    edgeRa = reg(emu, "ra");
                    break;
                }
                if (pc == 0xbfc05000L || pc == 0xbfc05028L) {
                    long ra = reg(emu, "ra");
                    require(ra >= FUNCTION && ra < FUNCTION + 0x100,
                        String.format("ROM call has unexpected RA 0x%08x", ra));
                    romReturns.add(String.format("0x%08x@0x%08x",
                        pc, ra));
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra);
                    continue;
                }
                require(pc >= 0xbfc32000L && pc < 0xbfc33800L,
                    String.format("unexpected control flow pc=0x%08x", pc));
                if (!emu.step(monitor))
                    throw new Exception(String.format(
                        "p-code stopped at 0x%08x: %s", pc, emu.getLastError()));
            }

            require(returned, "entry did not reach first unresolved ROM edge within step limit");
            require(helperReached, "entry path did not call the CP0 helper");
            require(edgeA0 == 0x9fc13d00L && edgeA1 == 0 && edgeA2 == 0xe0,
                String.format("first reset-helper arguments mismatch: a0=0x%08x a1=0x%08x a2=0x%08x",
                    edgeA0, edgeA1, edgeA2));
            require(edgeRa == 0xbfc32060L,
                String.format("first reset-helper return address mismatch: 0x%08x", edgeRa));
            require(reg(emu, "Status") == 0, "CP0 Status write mismatch");
            require(reg(emu, "Cause") == 0x00800000L,
                "CP0 Cause write mismatch");
            require(reg(emu, "EBase") == 0xbfc08000L,
                "CP0 EBase write mismatch");
            require(reg(emu, "IntCtl") == 0, "CP0 IntCtl write mismatch");
            require(reg(emu, "SRSCtl") == 0, "CP0 SRSCtl write mismatch");
            require(reg(emu, "SRSMap") == 0, "CP0 SRSMap write mismatch");
            require(cp0Writes.size() == 6,
                "unexpected CP0 write count: " + cp0Writes.size());
            require(cp0Writes.containsKey("Status")
                    && cp0Writes.containsKey("IntCtl")
                    && cp0Writes.containsKey("SRSCtl")
                    && cp0Writes.containsKey("SRSMap")
                    && cp0Writes.containsKey("Cause")
                    && cp0Writes.containsKey("EBase"),
                "CP0 write set differs from recovered instructions: " + cp0Writes.keySet());
            for (int index = 0; index < 22; index++)
                require(word(emu, INTC_BASE + index * 4L + 0x4c) == 0x1fc00000L,
                    "interrupt vector slot mismatch at index " + index);
            for (int index = 0; index < 4; index++)
                require(word(emu, INTC_BASE + index * 4L + 0xc0) == 0x1fc00000L,
                    "interrupt vector bank mismatch at index " + index);
            require(word(emu, WD_BASE + 0x1c) == 1,
                "entry watchdog-feed register write mismatch");
            require(romReturns.size() == 2,
                "unexpected fixed-ROM call count: " + romReturns.size());

            StringBuilder writes = new StringBuilder();
            for (Map.Entry<String, Long> item : cp0Writes.entrySet()) {
                if (writes.length() != 0) writes.append(',');
                writes.append(item.getKey()).append('=').append(
                    String.format("0x%08x", item.getValue()));
            }
            println(String.format(
                "KERNEL INIT ENTRY PASS entry=0x%08x helper=0x%08x first-rom-edge=0x%08x stop=0x%08x args=0x%08x,0x%08x,0x%08x ra=0x%08x steps=%d cp0-writes=%s intc-slots=22+4 watchdog=0x%08x fixed-rom-return-stubs=%s result-registers-used=0",
                ENTRY, FUNCTION, STOP_PC, STOP_PC, edgeA0, edgeA1, edgeA2, edgeRa,
                steps, writes, word(emu, WD_BASE + 0x1c),
                String.join(",", romReturns)));
        }
        finally {
            emu.dispose();
        }
    }
}

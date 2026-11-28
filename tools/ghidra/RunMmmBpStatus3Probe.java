// Execute the real MMM_BP state-3 terminal branch with the resident consumer stubbed.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public class RunMmmBpStatus3Probe extends GhidraScript {
    private static final long ENTRY = 0xbfc216a8L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc21b00L;
    private static final int RAM_SIZE = 0x1000;
    private static final long STATUS = RAM + 0x400L;
    private static final long FRAME = RAM + 0x500L;
    private static final long MMIO = 0xc0160000L;
    private static final int MMIO_SIZE = 0x100;
    private static final long RESIDENT_STUB = 0xbfc21a3cL;
    private static final long RING_HELPER = 0xbfc27520L;
    private static final long REFILL = 0xbfc276acL;
    private static final long STATUS_COMPARE = 0xbfc217a2L;
    private static final long TERMINAL_RETURN = 0xbfc217c8L;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long reg(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private long readWord(EmulatorHelper emulator, long address) throws Exception {
        byte[] data = emulator.readMemory(addr(address), 4);
        return (data[0] & 255L) | ((data[1] & 255L) << 8)
            | ((data[2] & 255L) << 16) | ((data[3] & 255L) << 24);
    }

    private void writeWord(EmulatorHelper emulator, long address, long value) throws Exception {
        emulator.writeMemory(addr(address), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private void writeHalf(EmulatorHelper emulator, long address, int value) throws Exception {
        emulator.writeMemory(addr(address), new byte[] { (byte)value, (byte)(value >>> 8) });
    }

    private void returnFromMips16(EmulatorHelper emulator) throws Exception {
        long ra = reg(emulator, "ra");
        require((ra & 1L) == 1L,
            String.format("untagged MIPS16 return address 0x%08x", ra));
        emulator.writeRegister("pc", ra & ~1L);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 0)
            throw new Exception("RunMmmBpStatus3Probe.java takes no arguments");

        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.getBlock(addr(RAM));
        if (ram == null) {
            ram = memory.createInitializedBlock("mmm_bp_status3_ram", addr(RAM),
                new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        }
        require(ram.getStart().equals(addr(RAM)) && ram.getSize() == RAM_SIZE
                && "mmm_bp_status3_ram".equals(ram.getName()),
            "refusing unexpected synthetic RAM block at 0x9fc21b00");
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);
        MemoryBlock mmio = memory.getBlock(addr(MMIO));
        if (mmio == null) {
            mmio = memory.createInitializedBlock("mmm_bp_status3_mmio", addr(MMIO),
                new ByteArrayInputStream(new byte[MMIO_SIZE]), MMIO_SIZE, monitor, false);
        }
        require(mmio.getStart().equals(addr(MMIO)) && mmio.getSize() == MMIO_SIZE
                && "mmm_bp_status3_mmio".equals(mmio.getName()),
            "refusing unexpected synthetic MMIO block at 0xc0160000");
        mmio.setRead(true);
        mmio.setWrite(true);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> calls = new ArrayList<>();
        int[] ringHelperCalls = {0};
        int[] refillCalls = {0};
        int[] statusComparisons = {0};
        int[] terminalReturns = {0};
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                java.math.BigInteger.ONE);

            // The function's actual literal at 0xbfc21890 selects this context.
            // Seed only fields needed to reach and observe the status-3 branch.
            writeWord(emulator, RAM + 0x24, 1);
            writeHalf(emulator, RAM + 0x8a, 0);
            writeWord(emulator, RAM + 0x78, FRAME);
            writeWord(emulator, RAM + 0xd0, STATUS);
            writeWord(emulator, STATUS, 3);
            writeHalf(emulator, FRAME + 2, 0x1234);
            writeHalf(emulator, FRAME + 6, 0x5678);

            emulator.writeRegister("sp", RAM + RAM_SIZE - 0x100L);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("a0", 0);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.enableMemoryWriteTracking(true);
            emulator.writeRegister("pc", ENTRY);

            boolean returned = false;
            int steps = 0;
            for (; steps < 10000; steps++) {
                long pc = reg(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    returned = true;
                    break;
                }
                if (pc == STATUS_COMPARE) {
                    long status = reg(emulator, "s1");
                    require(status == 3,
                        String.format("status compare saw 0x%08x, expected 3", status));
                    statusComparisons[0]++;
                    calls.add(String.format("status-compare s1=%08x", status));
                }
                if (pc == TERMINAL_RETURN) terminalReturns[0]++;
                if (pc == RESIDENT_STUB) {
                    String call = String.format("resident args=%08x,%08x,%08x,%08x",
                        reg(emulator, "a0"), reg(emulator, "a1"),
                        reg(emulator, "a2"), reg(emulator, "a3"));
                    calls.add(call);
                    emulator.writeRegister("v0", 0);
                    returnFromMips16(emulator);
                    continue;
                }
                if (pc == RING_HELPER) {
                    ringHelperCalls[0]++;
                    calls.add(String.format("ring-helper args=%08x,%08x,%08x,%08x",
                        reg(emulator, "a0"), reg(emulator, "a1"),
                        reg(emulator, "a2"), reg(emulator, "a3")));
                    // This is the one deliberately stubbed ring-processing boundary.
                    emulator.writeRegister("v0", 0);
                    returnFromMips16(emulator);
                    continue;
                }
                if (pc == REFILL) {
                    refillCalls[0]++;
                    throw new Exception("status-3 path reached the decoder refill");
                }
                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("stopped at 0x%08x: %s",
                        pc, emulator.getLastError()));
                }
            }

            require(returned, "status-3 path did not return before the step limit");
            require(readWord(emulator, RAM + 0x24) == 2,
                "status-3 branch did not leave context state at 2");
            require((emulator.readMemory(addr(RAM + 0xdc), 2)[0] & 255)
                    == 0x34
                    && (emulator.readMemory(addr(RAM + 0xdc), 2)[1] & 255)
                    == 0x12,
                "status-3 branch did not copy frame field +2 to context +0xdc");
            require((emulator.readMemory(addr(RAM + 0xde), 2)[0] & 255)
                    == 0x78
                    && (emulator.readMemory(addr(RAM + 0xde), 2)[1] & 255)
                    == 0x56,
                "status-3 branch did not copy frame field +6 to context +0xde");
            require(ringHelperCalls[0] == 1,
                "expected the real path to cross the ring-processing boundary once");
            require(statusComparisons[0] == 1,
                "real status-3 comparison was not reached exactly once");
            require(terminalReturns[0] == 1,
                "real status-3 terminal return was not reached exactly once");
            require(refillCalls[0] == 0, "decoder refill was reached after status 3");
            require(readWord(emulator, MMIO + 0x50) == 8,
                "expected the real state-1 pre-consumer MMIO write of 8");
            ghidra.program.model.address.AddressSetView writes =
                emulator.getTrackedMemoryWriteSet();
            require(writes != null && writes.contains(addr(RAM + 0x24))
                    && writes.contains(addr(RAM + 0xdc))
                    && writes.contains(addr(RAM + 0xde))
                    && writes.contains(addr(MMIO + 0x50)),
                "write tracker missed state, status-3 frame, or MMIO writes");

            for (String call : calls) println("MMM BP STATUS3 EDGE " + call);
            println(String.format("MMM BP STATUS3 PCODE PASS entry=0x%08x steps=%d "
                + "status=%d context-state=%d ring-helper-stubbed=%d refill-calls=%d "
                + "mmio-0xc0160050=%d resident-effects=stubbed", ENTRY, steps, readWord(emulator, STATUS),
                readWord(emulator, RAM + 0x24), ringHelperCalls[0], refillCalls[0],
                readWord(emulator, MMIO + 0x50)));
        } finally {
            emulator.dispose();
        }
    }
}

// Execute the extracted alternate exception-stack selector and stop at ROM.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

public class RunKerXsysAlternateStackProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc340a4L;
    private static final long ROM_BOUNDARY = 0xbfc05f90L;
    private static final long STACK_INDEX = 0x9fc1a495L;
    private static final long STACK_COUNT = 0x9fc1a493L;
    private static final long STACK_TOP = 0x9fc1a8a0L;
    private static final long TABLE = 0xbfc19974L;
    private static final long ROM_STACK_COUNT = 0xbfc19930L;
    private static final long STACK_MEMORY = 0x9fc1b000L;
    private static final long STACK_LIMIT = 0x9fc1c000L;
    private static final long STACK_MASK = 0x3ffffL;
    private static final int MAX_STEPS = 64;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private MemoryBlock mapRam(Memory memory, String name, long base, int size)
            throws Exception {
        MemoryBlock block = memory.createInitializedBlock(name, addr(base),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        return block;
    }

    private void putByte(Memory memory, long at, int value) throws Exception {
        memory.setBytes(addr(at), new byte[] {(byte)value});
    }

    private void putWord(Memory memory, long at, long value) throws Exception {
        memory.setBytes(addr(at), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private long readByte(EmulatorHelper emu, long at) throws Exception {
        return emu.readMemory(addr(at), 1)[0] & 0xffL;
    }

    private long readWord(EmulatorHelper emu, long at) throws Exception {
        byte[] bytes = emu.readMemory(addr(at), 4);
        return (bytes[0] & 0xffL) | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16) | ((bytes[3] & 0xffL) << 24);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private void runProfile(Memory memory, String label, long inputSp, long tableLimit,
            boolean expectAlternate) throws Exception {
        putByte(memory, STACK_INDEX, 0);
        putByte(memory, STACK_COUNT, 0);
        putByte(memory, ROM_STACK_COUNT, 0);
        putWord(memory, STACK_TOP, STACK_LIMIT);
        putWord(memory, TABLE, tableLimit);
        putWord(memory, STACK_MEMORY + 0xffc, 0xccccccccL);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("sp", inputSp);
            emu.writeRegister("ra", 0x80001235L);
            emu.writeRegister("pc", ENTRY);

            boolean reachedBoundary = false;
            int steps = 0;
            for (; steps < MAX_STEPS; steps++) {
                long pc = reg(emu, "pc");
                if (pc == ROM_BOUNDARY) {
                    reachedBoundary = true;
                    break;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("%s stopped at 0x%08x: %s",
                        label, pc, emu.getLastError()));
            }
            require(reachedBoundary, label + " did not reach the fixed-ROM boundary");

            long finalSp = reg(emu, "sp");
            long stackIndex = readByte(emu, STACK_INDEX);
            long stackCount = readByte(emu, STACK_COUNT);
            long romStackCount = readByte(emu, ROM_STACK_COUNT);
            long nextStackTop = readWord(emu, STACK_TOP);
            long savedSp = expectAlternate
                ? readWord(emu, STACK_MEMORY + 0xffc) : 0xccccccccL;

            if (expectAlternate) {
                require(finalSp == STACK_MEMORY + 0xffc,
                    label + " did not switch to the alternate stack frame");
                require(savedSp == inputSp,
                    label + " did not preserve the old SP on the alternate stack");
                require(stackIndex == 0 && stackCount == 1 && romStackCount == 1,
                    label + " changed stack index or failed to increment both counters");
                require(nextStackTop == STACK_LIMIT - 184,
                    label + " did not reserve the observed 184-byte stack frame");
            }
            else {
                require(finalSp == inputSp,
                    label + " changed SP on the direct fixed-ROM path");
                require(stackIndex == 0 && stackCount == 0 && romStackCount == 0,
                    label + " changed stack state on the direct path");
                require(nextStackTop == STACK_LIMIT && savedSp == 0xccccccccL,
                    label + " changed alternate-stack memory on the direct path");
            }

            println(String.format(
                "KER XSYS ALTERNATE STACK PROFILE PASS label=%s sp-mask=0x%05x limit=0x%05x path=%s final-sp=0x%08x saved-sp=0x%08x stack-index=%d stack-count=%d rom-count=%d next-top=0x%08x steps=%d rom-handler-executed=0",
                label, inputSp & STACK_MASK, tableLimit,
                expectAlternate ? "alternate" : "direct", finalSp, savedSp,
                stackIndex, stackCount, romStackCount, nextStackTop, steps));
        }
        finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKerXsysAlternateStackProbe.java KER_XSYS.BIN");
        byte[] input = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        require(input.length == 0x800, "unexpected ker_xsys.bin size");

        Memory memory = currentProgram.getMemory();
        MemoryBlock xsys = memory.getBlock(addr(ENTRY));
        require(xsys != null, "ker_xsys segment is not mapped");
        mapRam(memory, "kerxsys_stack_selector_state", 0x9fc1a000L, 0x1000);
        mapRam(memory, "kerxsys_stack_selector_table", 0xbfc19900L, 0x100);
        mapRam(memory, "kerxsys_alternate_stack", STACK_MEMORY, 0x1000);

        currentProgram.getProgramContext().setValue(
            currentProgram.getRegister("ISA_MODE"), addr(ENTRY), addr(0xbfc3414cL),
            BigInteger.ZERO);
        require(disassemble(addr(ENTRY)), "could not disassemble MIPS32 stack selector");
        require(createFunction(addr(ENTRY), null) != null,
            "could not create stack-selector function");

        runProfile(memory, "below", 0x9fc11fffL, 0x12000L, true);
        runProfile(memory, "equal", 0x9fc12000L, 0x12000L, true);
        runProfile(memory, "above", 0x9fc12001L, 0x12000L, false);
        println("KER XSYS ALTERNATE STACK P-CODE PASS table-index=0 mask=0x3ffff frame-size=184 rom-handler-executed=0");
    }
}

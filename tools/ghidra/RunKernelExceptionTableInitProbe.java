// Execute the extracted kernel's real exception-table constructor in p-code.
// Stop after its table setter calls, before unrelated globals and ROM calls.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

public class RunKernelExceptionTableInitProbe extends GhidraScript {
    private static final long CONSTRUCTOR = 0xbfc32bc8L;
    private static final long TABLE_SETUP_END = 0xbfc32c5cL;
    private static final long TABLE = 0x9fc1a9a4L;
    private static final long DEFAULT_HANDLER = 0xbfc19a75L;
    private static final long INDEX0_HANDLER = 0xbfc19624L;
    private static final long INDEX9_HANDLER = 0xbfc06f18L;
    private static final long INDEX10_HANDLER = 0xbfc195d4L;
    private static final long SENTINEL = 0xccccccccL;
    private static final int TABLE_BYTES = 0x80;
    private static final int MAX_STEPS = 20000;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void mapRam(Memory memory, String name, long base, int size) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(name, addr(base),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
    }

    private void putWord(Memory memory, long at, long value) throws Exception {
        memory.setBytes(addr(at), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private long readWord(EmulatorHelper emu, long at) throws Exception {
        byte[] b = emu.readMemory(addr(at), 4);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunKernelExceptionTableInitProbe.java KER_INIT.BIN");

        Memory memory = currentProgram.getMemory();
        mapRam(memory, "exception_table_init_ram", 0x9fc18000L, 0x4000);
        mapRam(memory, "exception_table_init_low_state", 0x00013d00L, 0x100);

        for (int offset = 0; offset < TABLE_BYTES; offset += 4)
            putWord(memory, TABLE + offset, SENTINEL);

        Register isaMode = currentProgram.getProgramContext().getRegister("ISA_MODE");
        currentProgram.getProgramContext().setValue(isaMode,
            addr(0xbfc330a0L), addr(0xbfc330dbL), BigInteger.ONE);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("sp", 0x9fc1bf00L);
            emu.writeRegister("a0", 0);
            emu.writeRegister("a1", 0);
            emu.writeRegister("ra", 0x00700100L);
            emu.writeRegister("pc", CONSTRUCTOR);

            boolean stoppedAtTableBoundary = false;
            int steps = 0;
            for (; steps < MAX_STEPS; steps++) {
                long pc = reg(emu, "pc");
                if (pc == TABLE_SETUP_END) {
                    stoppedAtTableBoundary = true;
                    break;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
            }
            require(stoppedAtTableBoundary, "constructor did not reach the end of table setup");

            for (int index = 0; index <= 13; index++) {
                long expected = DEFAULT_HANDLER;
                if (index == 0) expected = INDEX0_HANDLER;
                if (index == 9) expected = INDEX9_HANDLER;
                if (index == 10) expected = INDEX10_HANDLER;
                require(readWord(emu, TABLE + index * 4L) == expected,
                    "constructor handler mismatch at index " + index);
            }
            for (int index = 14; index < TABLE_BYTES / 4; index++)
                require(readWord(emu, TABLE + index * 4L) == SENTINEL,
                    "setter unexpectedly wrote out-of-range index " + index);

            println(String.format(
                "KERNEL EXCEPTION TABLE INIT PASS constructor=0x%08x stop=0x%08x steps=%d slots0-13=verified slots14-31=sentinel rom-code-executed=0",
                CONSTRUCTOR, TABLE_SETUP_END, steps));
        }
        finally {
            emu.dispose();
        }
    }
}

// Execute the real MIPS32 exception vector and stop at its selected handler.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

public class RunKernelExceptionDispatchProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc08180L;
    private static final long STOP_HANDLER = 0xbfc06f18L;
    private static final long ALT_HANDLER = 0x700100L;
    private static final long TABLE = 0x9fc1a9a4L;
    private static final long STATE = 0x00013d00L;
    private static final long EPC_SAVE = 0x00013dccL;
    private static final long RA_SAVE = 0x00013dd0L;
    private static final long CAUSE_SAVE = 0x00013dd4L;
    private static final int VECTOR_FILE_OFFSET = 0x380;
    private static final int VECTOR_SIZE = 0x20;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
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

    private long runCase(String label, int exceptionCode, long causeValue, long handler)
            throws Exception {
        Memory memory = currentProgram.getMemory();
        putWord(memory, TABLE + (exceptionCode << 2), handler);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("EPC", 0x80001234L);
            emu.writeRegister("Cause", causeValue);
            emu.writeRegister("ra", 0x80005679L);
            emu.writeRegister("pc", ENTRY);
            long stop = -1;
            for (int steps = 0; steps < 128; steps++) {
                long pc = reg(emu, "pc");
                if (pc == handler) {
                    stop = pc;
                    require(readWord(emu, EPC_SAVE) == 0x80001234L,
                        label + " EPC save mismatch");
                    require(readWord(emu, RA_SAVE) == 0x80005679L,
                        label + " RA save mismatch");
                    require(readWord(emu, CAUSE_SAVE) == causeValue,
                        label + " Cause save mismatch");
                    println(String.format(
                        "KERNEL EXCEPTION case=%s exc=%d cause=0x%08x index=0x%02x handler=0x%08x steps=%d",
                        label, exceptionCode, causeValue, causeValue & 0x7c, pc, steps));
                    break;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
            }
            require(stop == handler, label + " did not reach selected handler");
            return stop;
        }
        finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunKernelExceptionDispatchProbe.java KERNEL.DRV");
        byte[] kernel = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        require(kernel.length >= VECTOR_FILE_OFFSET + VECTOR_SIZE,
            "kernel.drv is truncated before exception vector");

        Memory memory = currentProgram.getMemory();
        byte[] vector = new byte[VECTOR_SIZE];
        memory.getBytes(addr(ENTRY), vector);
        byte[] sourceVector = java.util.Arrays.copyOfRange(
            kernel, VECTOR_FILE_OFFSET, VECTOR_FILE_OFFSET + VECTOR_SIZE);
        require(java.util.Arrays.equals(vector, sourceVector),
            "mapped exception-vector bytes do not match kernel.drv file offset 0x380");

        MemoryBlock table = memory.createInitializedBlock("exception_probe_table",
            addr(TABLE), new ByteArrayInputStream(new byte[0x80]), 0x80, monitor, false);
        table.setRead(true);
        table.setWrite(true);
        MemoryBlock state = memory.createInitializedBlock("exception_probe_state",
            addr(STATE), new ByteArrayInputStream(new byte[0x100]), 0x100, monitor, false);
        state.setRead(true);
        state.setWrite(true);
        currentProgram.getProgramContext().setValue(
            currentProgram.getRegister("ISA_MODE"), addr(ENTRY),
            addr(ENTRY + 0x40), BigInteger.ZERO);

        long code9 = runCase("exc9-noisy-cause", 9, 0x80000027L, STOP_HANDLER);
        long code8 = runCase("exc8-noisy-cause", 8, 0x20000023L, ALT_HANDLER);
        require(code9 == STOP_HANDLER && code8 == ALT_HANDLER,
            "exception-vector table selection mismatch");
        println(String.format(
            "KERNEL EXCEPTION DISPATCH P-CODE PASS vector=0x%08x exc9-noisy=0x%08x exc8-noisy=0x%08x synthetic-table=1 rom-handler-executed=0",
            ENTRY, code9, code8));
    }
}

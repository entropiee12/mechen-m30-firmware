// Execute the real browser refresh function with RAM-backed state and
// intercept its indirect row/update callbacks.  This is a dynamic p-code
// probe of the extracted AP, not a full ATJ2127 boot.
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
import java.util.ArrayList;
import java.util.List;

public class RunBrowserRefreshProbe extends GhidraScript {
    private static final long ENTRY = 0x40cd5218L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long CONTROL = RAM + 0x100;
    private static final long PATH = RAM + 0x200;
    private static final long LIST_STATE = RAM + 0x400;
    private static final long ENTRIES = RAM + 0x600;
    private static final long NAMES = RAM + 0x1000;
    private static final long ROW_CALLBACK = RAM + 0x2000;
    private static final long UPDATE_CALLBACK = RAM + 0x2040;
    private static final long MODE_VALUE = RAM + 0x2480;
    private static final long STACK = RAM + 0x3000;
    private static final int ROWS = 7;

    private Address addr(long value) throws Exception {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void putWord(Memory memory, long address, long value) throws Exception {
        memory.setBytes(addr(address), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private void putHalf(Memory memory, long address, int value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value, (byte)(value >>> 8)});
    }

    private void putByte(Memory memory, long address, int value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value});
    }

    private void ramBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            "browser_refresh_probe_ram", addr(RAM),
            new ByteArrayInputStream(new byte[0x5000]), 0x5000, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private String hex(long value) {
        return String.format("0x%08x", value & 0xffffffffL);
    }

    private void runCase(Memory memory, String label, boolean hasContext) throws Exception {
        memory.setBytes(addr(RAM), new byte[0x5000]);

        // param_1 is the browser control.  Mode 3 is the root/list refresh
        // form: FUN_40cd52e0 turns [start=1,end=7] into seven rows.
        putWord(memory, CONTROL + 4, PATH);
        putHalf(memory, CONTROL + 8, 1);
        putHalf(memory, CONTROL + 10, ROWS);
        putByte(memory, CONTROL + 0x14, 3);
        putWord(memory, PATH + 8, hasContext ? RAM + 0x280 : 0);
        putHalf(memory, LIST_STATE + 2, ROWS);

        for (int i = 0; i < ROWS; i++) {
            long entry = ENTRIES + i * 0xc;
            putWord(memory, entry + 8, NAMES + i * 0x40);
            putByte(memory, NAMES + i * 0x40, 0xff);
            putByte(memory, NAMES + i * 0x40 + 1, 0xfe);
        }

        // Relocate the literal pointers used by FUN_40cd5218.  The actual
        // callbacks are intercepted at these RAM addresses below.
        putWord(memory, 0x40cd52ccL, MODE_VALUE);
        putWord(memory, 0x40cd52d0L, LIST_STATE);
        putWord(memory, 0x40cd52d4L, ENTRIES);
        putWord(memory, 0x40cd52d8L, ROW_CALLBACK | 1);
        putWord(memory, 0x40cd52dcL, UPDATE_CALLBACK | 1);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        List<String> events = new ArrayList<>();
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", CONTROL);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s1", 0);
            Register isa = currentProgram.getRegister("ISA_MODE");
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 100000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                long base = pc & ~1L;
                if (base == STOP) {
                    int rows = 0;
                    for (String event : events) if (event.startsWith("row")) rows++;
                    int updates = events.size() - rows;
                    int expectedRows = hasContext ? ROWS : 0;
                    if (rows != expectedRows) {
                        throw new Exception(label + " row callback count=" + rows
                            + " expected=" + expectedRows + " events=" + events);
                    }
                    println(String.format(
                        "BROWSER REFRESH CASE label=%s context=%d steps=%d row-callbacks=%d update-callbacks=%d",
                        label, hasContext ? 1 : 0, steps, rows, updates));
                    for (String event : events) println("  " + event);
                    return;
                }
                if (base == ROW_CALLBACK || base == UPDATE_CALLBACK) {
                    long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                    long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                    long ra = emu.readRegister("ra").longValue() & 0xffffffffL;
                    if (base == ROW_CALLBACK) {
                        events.add(String.format("row a0=%s a1=%s a2=%s", hex(a0), hex(a1), hex(a2)));
                    } else {
                        events.add(String.format("update a0=%s a1=%s", hex(a0), hex(a1)));
                    }
                    emu.writeRegister("v0", 1);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(label + " stopped at " + hex(pc) + ": " + emu.getLastError());
                }
            }
            throw new Exception(label + " exceeded step limit at "
                + hex(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        ramBlock(memory);
        runCase(memory, "no-context", false);
        runCase(memory, "directory-context", true);
        println("BROWSER REFRESH DYNAMIC PASS context-gated-row-refresh=verified");
    }
}

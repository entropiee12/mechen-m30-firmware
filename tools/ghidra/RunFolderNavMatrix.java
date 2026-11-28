// Run the recovered folder handlers against all boundary cases in one Ghidra
// program. Each case gets a fresh EmulatorHelper and RAM state, so the AP is
// imported once while emulator state remains isolated.
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
import java.util.LinkedHashMap;
import java.util.Map;

public class RunFolderNavMatrix extends GhidraScript {
    private static final long NEXT = 0x487144a8L;
    private static final long PREVIOUS = 0x487146ccL;
    private static final long REDRAW = 0x487148f8L;
    private static final long RESET_PAGE = 0x487148b0L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long STATE = 0x10002000L;
    private static final long STACK = 0x10003000L;

    // mode total page start end cursor expected-start expected-end
    // expected-active expected-previous expected-status expected-update
    // expected-redraw expected-reset
    private static final String[][] CASES = {
        {"next", "1", "1", "1", "1", "1", "1", "1", "1", "1", "0", "0", "0", "0"},
        {"previous", "1", "1", "1", "1", "1", "1", "1", "1", "1", "0", "0", "0", "0"},
        {"next", "2", "7", "1", "2", "2", "1", "2", "1", "2", "0", "1", "0", "0"},
        {"previous", "2", "7", "1", "2", "1", "1", "2", "2", "1", "0", "1", "0", "0"},
        {"next", "7", "7", "1", "7", "7", "1", "7", "1", "7", "0", "1", "0", "0"},
        {"previous", "7", "7", "1", "7", "1", "1", "7", "7", "1", "0", "1", "0", "0"},
        {"next", "8", "7", "1", "7", "7", "2", "8", "8", "7", "1", "2", "1", "0"},
        {"previous", "8", "7", "1", "8", "1", "2", "8", "8", "7", "5", "2", "0", "0"},
        {"next", "9", "7", "2", "8", "8", "3", "9", "9", "7", "1", "2", "1", "0"},
        {"next", "10", "7", "4", "10", "10", "1", "7", "1", "1", "4", "2", "0", "0"},
        {"previous", "10", "7", "1", "7", "1", "4", "10", "10", "7", "5", "2", "0", "0"},
        {"previous", "10", "7", "4", "10", "4", "4", "10", "3", "4", "0", "1", "0", "0"},
        {"next", "10", "7", "1", "7", "7", "2", "8", "8", "7", "1", "2", "1", "0"},
        {"previous", "10", "7", "1", "7", "7", "1", "7", "6", "7", "0", "1", "0", "0"}
    };

    private Address addr(long value) throws Exception {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void putHalf(Memory memory, long address, int value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value, (byte)(value >>> 8)});
    }

    private void putByte(Memory memory, long address, int value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value});
    }

    private int half(EmulatorHelper emu, long address) throws Exception {
        byte[] bytes = emu.readMemory(addr(address), 2);
        return (bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8);
    }

    private int one(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xff;
    }

    private void ramBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            "folder_matrix_ram", addr(RAM), new ByteArrayInputStream(new byte[0x4000]),
            0x4000, monitor, false);
        block.setRead(true);
        block.setWrite(true);
    }

    private void runCase(Memory memory, Register isa, String app, String[] c) throws Exception {
        String mode = c[0];
        long entry = "next".equals(mode) ? NEXT : PREVIOUS;
        int total = Integer.decode(c[1]);
        int page = Integer.decode(c[2]);
        int initialStart = Integer.decode(c[3]);
        int initialEnd = Integer.decode(c[4]);
        int cursor = Integer.decode(c[5]);

        memory.setBytes(addr(STATE), new byte[0x40]);
        putByte(memory, STATE + 2, page);
        putHalf(memory, STATE + 8, initialStart);
        putHalf(memory, STATE + 10, initialEnd);
        putHalf(memory, STATE + 12, total);
        putHalf(memory, STATE + 14, cursor);
        putHalf(memory, STATE + 16, cursor);
        putHalf(memory, STATE + 18, cursor);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", 0);
            emu.writeRegister("a1", STATE);
            emu.writeRegister("s1", 0);
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("pc", entry);
            Map<Long,Integer> hits = new LinkedHashMap<>();

            for (int steps = 0; steps < 100000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                if ((pc & 1L) != 0) {
                    pc &= ~1L;
                    emu.writeRegister("pc", pc);
                }
                if (pc == STOP) {
                    int start = half(emu, STATE + 8);
                    int end = half(emu, STATE + 10);
                    int active = half(emu, STATE + 14);
                    int previous = half(emu, STATE + 18);
                    int status = one(emu, STATE + 0x14);
                    int update = one(emu, STATE + 0x15);
                    int redraw = hits.getOrDefault(REDRAW, 0);
                    int reset = hits.getOrDefault(RESET_PAGE, 0);
                    int expectedStart = Integer.decode(c[6]);
                    int expectedEnd = Integer.decode(c[7]);
                    int expectedActive = Integer.decode(c[8]);
                    int expectedPrevious = Integer.decode(c[9]);
                    int expectedStatus = Integer.decode(c[10]);
                    int expectedUpdate = Integer.decode(c[11]);
                    int expectedRedraw = Integer.decode(c[12]);
                    int expectedReset = Integer.decode(c[13]);
                    if (start != expectedStart || end != expectedEnd || active != expectedActive
                        || previous != expectedPrevious || status != expectedStatus
                        || update != expectedUpdate || redraw != expectedRedraw || reset != expectedReset) {
                        throw new Exception(String.format(
                            "matrix mismatch app=%s mode=%s total=%d page=%d initial=%d..%d cursor=%d "
                            + "got=%d,%d,%d,%d,%d,%d,%d,%d expected=%d,%d,%d,%d,%d,%d,%d,%d",
                            app, mode, total, page, initialStart, initialEnd, cursor,
                            start, end, active, previous, status, update, redraw, reset,
                            expectedStart, expectedEnd, expectedActive, expectedPrevious,
                            expectedStatus, expectedUpdate, expectedRedraw, expectedReset));
                    }
                    println(String.format(
                        "FOLDER NAV MATRIX PASS app=%s mode=%s total=%d page=%d initial_start=%d initial_end=%d cursor=%d "
                        + "start=%d end=%d active=%d previous=%d status=%d update=%d redraw=%d reset=%d",
                        app, mode, total, page, initialStart, initialEnd, cursor,
                        start, end, active, previous, status, update, redraw, reset));
                    return;
                }
                if (pc == REDRAW || pc == RESET_PAGE) {
                    long returnPc = emu.readRegister("ra").longValue() & 0xffffffffL;
                    hits.put(pc, hits.getOrDefault(pc, 0) + 1);
                    emu.writeRegister("pc", returnPc);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception("emulator stopped at 0x" + Long.toHexString(pc)
                        + ": " + emu.getLastError());
                }
            }
            throw new Exception("step limit reached at PC 0x"
                + Long.toHexString(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: RunFolderNavMatrix.java browser|music");
        }
        String app = getScriptArgs()[0];
        if (!"browser".equals(app) && !"music".equals(app)) {
            throw new Exception("app must be browser or music");
        }
        Memory memory = currentProgram.getMemory();
        ramBlock(memory);
        Register isa = currentProgram.getRegister("ISA_MODE");
        for (String[] c : CASES) {
            runCase(memory, isa, app, c);
        }
        println("FOLDER NAV MATRIX PASS app=" + app + " cases=" + CASES.length);
    }
}

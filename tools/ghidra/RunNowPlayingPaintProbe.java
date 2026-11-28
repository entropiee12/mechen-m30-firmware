// Execute the real music.ap now-playing paint entry with RAM-backed state and
// intercept its UI dispatcher. This is a trace-only p-code probe: it does not
// patch an AP or generate a flashable image.
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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class RunNowPlayingPaintProbe extends GhidraScript {
    private static final long ENTRY = 0x40154ddcL;
    private static final long STOP = 0x700300L;
    private static final long STUBS = 0x700000L;
    private static final long RAM = 0x10000000L;
    private static final long FLAGS = RAM + 0x100;
    private static final long STACK = RAM + 0x3000;

    private Address addr(long value) throws Exception {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void putWord(Memory memory, long address, long value) throws Exception {
        memory.setBytes(addr(address), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private void expectWord(Memory memory, long address, long expected) throws Exception {
        byte[] b = new byte[4];
        memory.getBytes(addr(address), b);
        long actual = (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
        if (actual != (expected & 0xffffffffL)) {
            throw new Exception(String.format("literal 0x%08x: expected 0x%08x, got 0x%08x",
                address, expected, actual));
        }
    }

    private void ramBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            "now_playing_paint_probe_ram", addr(RAM),
            new ByteArrayInputStream(new byte[0x4000]), 0x4000, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private String hex(long value) {
        return String.format("0x%08x", value & 0xffffffffL);
    }

    private String bytes(EmulatorHelper emu, long address, int length) throws Exception {
        byte[] values = emu.readMemory(addr(address), length);
        StringBuilder result = new StringBuilder();
        for (byte value : values) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private long half(EmulatorHelper emu, long address) throws Exception {
        byte[] values = emu.readMemory(addr(address), 2);
        return (values[0] & 0xffL) | ((values[1] & 0xffL) << 8);
    }

    private boolean isStackAddress(long address) {
        return address >= STACK - 0x100 && address < STACK + 0x100;
    }

    private int dispatchId(EmulatorHelper emu) throws Exception {
        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
        if (!isStackAddress(a0)) {
            return (int)(a0 & 0xffff);
        }
        return (int)half(emu, a0);
    }

    private void verifyClearRect(EmulatorHelper emu, int index) throws Exception {
        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
        long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;
        if (a3 != 0x12L) {
            return;
        }
        if (!isStackAddress(a0)) {
            throw new Exception("clear payload is not stack-backed: " + hex(a0));
        }

        int[] expectedY = {0, 0x1c, 0xb3};
        int[] expectedHeight = {0x1c, 0x97, 0x3d};
        if (index >= expectedY.length
            || half(emu, a0) != 0
            || half(emu, a0 + 2) != expectedY[index]
            || half(emu, a0 + 4) != 0x140
            || half(emu, a0 + 6) != expectedHeight[index]) {
            throw new Exception(String.format(
                "clear rectangle mismatch index=%d a0=%s x=%s y=%s width=%s height=%s",
                index,
                hex(a0),
                hex(half(emu, a0)),
                hex(half(emu, a0 + 2)),
                hex(half(emu, a0 + 4)),
                hex(half(emu, a0 + 6))
            ));
        }
    }

    private String dispatchSummary(EmulatorHelper emu) throws Exception {
        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
        long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
        long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
        long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;
        StringBuilder result = new StringBuilder(String.format(
            "a0=%s a1=%s a2=%s a3=%s",
            hex(a0),
            hex(a1),
            hex(a2),
            hex(a3)
        ));
        if (isStackAddress(a0)) {
            result.append(" a0-bytes=").append(bytes(emu, a0, 0x20));
        }
        if (isStackAddress(a1)) {
            result.append(" a1-bytes=").append(bytes(emu, a1, 0x20));
        }
        return result.toString();
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: RunNowPlayingPaintProbe.java STUBS.BIN");
        }
        Memory memory = currentProgram.getMemory();
        byte[] stubs = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        MemoryBlock stubBlock = memory.createInitializedBlock(
            "now_playing_paint_probe_stubs", addr(STUBS),
            new ByteArrayInputStream(stubs), stubs.length, monitor, false);
        stubBlock.setRead(true); stubBlock.setWrite(false); stubBlock.setExecute(true);
        ramBlock(memory);
        memory.setBytes(addr(RAM), new byte[0x4000]);

        // Only two literal-pool values are redirected. The dispatcher is the
        // known MIPS16 stub at 0x700001; the state flags are a zeroed RAM word.
        expectWord(memory, 0x40155324L, 0xbfc16405L);
        expectWord(memory, 0x40155334L, 0x9fc15418L);
        putWord(memory, 0x40155324L, STUBS + 1);
        putWord(memory, 0x40155334L, FLAGS);
        // Bit 0 is the initial-paint request: it draws the three fixed bands,
        // then the default artwork control through the common state path.
        putWord(memory, FLAGS, 1);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        List<String> events = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        int clearRectCount = 0;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            Register isa = currentProgram.getRegister("ISA_MODE");
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", FLAGS);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 100000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                long base = pc & ~1L;
                if (base == STOP) {
                    if (events.size() != 7) {
                        throw new Exception("paint dispatch count=" + events.size()
                            + " expected=7 events=" + events);
                    }
                    int[] expectedIds = {0x18c3, 0, 0x39e7, 0, 0x18c3, 0, 0x1002};
                    if (ids.size() != expectedIds.length || clearRectCount != 3) {
                        throw new Exception("paint payload count mismatch ids=" + ids
                            + " clear-rects=" + clearRectCount);
                    }
                    for (int index = 0; index < expectedIds.length; index++) {
                        if (ids.get(index) != expectedIds[index]) {
                            throw new Exception("paint IDs=" + ids
                                + " expected=" + java.util.Arrays.toString(expectedIds));
                        }
                    }
                    println("NOW PLAYING PAINT DYNAMIC PASS steps=" + steps
                        + " dispatches=" + events.size() + " clear-rects=" + clearRectCount);
                    for (String event : events) println("  " + event);
                    return;
                }
                if (base == STUBS) {
                    verifyClearRect(emu, clearRectCount);
                    if ((emu.readRegister("a3").longValue() & 0xffffffffL) == 0x12L) {
                        clearRectCount++;
                    }
                    ids.add(dispatchId(emu));
                    events.add(dispatchSummary(emu));
                    emu.writeRegister("v0", 1);
                    long ra = emu.readRegister("ra").longValue() & 0xffffffffL;
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception("emulator stopped at " + hex(pc) + ": " + emu.getLastError());
                }
            }
            throw new Exception("step limit reached at "
                + hex(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }
}

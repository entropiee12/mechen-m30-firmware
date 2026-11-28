// Execute selected real music.ap now-playing state branches with temporary
// RAM-backed state and a traced UI dispatcher. This is a trace-only probe.
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
import java.util.Arrays;
import java.util.List;

public class RunNowPlayingStateProbe extends GhidraScript {
    private static final long ENTRY = 0x40154ddcL;
    private static final long STOP = 0x700300L;
    private static final long STUBS = 0x700000L;
    private static final long RAM = 0x10000000L;
    private static final long FLAGS = RAM + 0x100;
    private static final long UI_STATE = RAM + 0x200;
    private static final long PLAY_STATE = RAM + 0x300;
    private static final long STACK = RAM + 0x3000;
    private static final int STACK_SENTINEL = 0xa5;

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

    private void fill(Memory memory, long address, int length, int value) throws Exception {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte)value);
        memory.setBytes(addr(address), bytes);
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
            "now_playing_state_probe_ram", addr(RAM),
            new ByteArrayInputStream(new byte[0x4000]), 0x4000, monitor, false);
        block.setRead(true); block.setWrite(true); block.setExecute(true);
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

    private long word(EmulatorHelper emu, long address) throws Exception {
        byte[] values = emu.readMemory(addr(address), 4);
        return (values[0] & 0xffL)
            | ((values[1] & 0xffL) << 8)
            | ((values[2] & 0xffL) << 16)
            | ((values[3] & 0xffL) << 24);
    }

    private long half(EmulatorHelper emu, long address) throws Exception {
        byte[] values = emu.readMemory(addr(address), 2);
        return (values[0] & 0xffL) | ((values[1] & 0xffL) << 8);
    }

    private long byteValue(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xffL;
    }

    private boolean isStackAddress(long address) {
        return address >= STACK - 0x100 && address < STACK + 0x100;
    }

    private int dispatchId(EmulatorHelper emu) throws Exception {
        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
        if (a0 < STACK - 0x100 || a0 >= STACK + 0x100) return (int)(a0 & 0xffff);
        byte[] b = emu.readMemory(addr(a0), 2);
        return (b[0] & 0xff) | ((b[1] & 0xff) << 8);
    }

    private String dispatchSummary(EmulatorHelper emu) throws Exception {
        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
        long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
        long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
        long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;
        StringBuilder result = new StringBuilder(String.format(
            "id=%s a0=%s a1=%s a2=%s a3=%s",
            hex(dispatchId(emu)),
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
        if (a3 == 0x02L || a3 == 0x03L) {
            result.append(" style-type=").append(hex(byteValue(emu, a0 + 2)));
        }
        return result.toString();
    }

    private void verifyPayloadLayout(EmulatorHelper emu) throws Exception {
        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
        long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
        long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;

        if (!isStackAddress(a0)) {
            return;
        }

        long id = dispatchId(emu);
        long expectedStyleType = id == 0x1003L ? 1L : 0L;
        if ((a3 == 0x02L || a3 == 0x03L)
            && (byteValue(emu, a0 + 2) != expectedStyleType
                || byteValue(emu, a0 + 3) != STACK_SENTINEL)) {
            throw new Exception(String.format(
                "style payload layout mismatch id=%s a0=%s type=%s reserve=%s",
                hex(id),
                hex(a0),
                hex(byteValue(emu, a0 + 2)),
                hex(byteValue(emu, a0 + 3))
            ));
        }

        if (a3 == 0x02L) {
            boolean hasSecondaryPayload = a1 != 0;
            boolean pointerMatches = !hasSecondaryPayload || a1 == a0 + 8;
            if (!pointerMatches || half(emu, a0 + 0) != id) {
                throw new Exception(String.format(
                    "control payload layout mismatch id=%s a0=%s a1=%s",
                    hex(id),
                    hex(a0),
                    hex(a1)
                ));
            }

            long expectedSecondaryWord = id == 0x1000L || id == 0x1001L
                ? 0xff00L
                : 0xffffL;
            long expectedSecondaryByte = id == 0x1003L || id == 0x1004L
                ? 1L
                : 0L;
            if (hasSecondaryPayload
                && (half(emu, a1) != expectedSecondaryWord
                    || byteValue(emu, a1 + 2) != expectedSecondaryByte
                    || byteValue(emu, a1 + 3) != STACK_SENTINEL
                    || word(emu, a1 + 4) != 0xa5a5a5a5L)) {
                throw new Exception(String.format(
                    "control secondary payload mismatch id=%s a0=%s a1=%s word=%s byte2=%s byte3=%s word4=%s",
                    hex(id),
                    hex(a0),
                    hex(a1),
                    hex(half(emu, a1)),
                    hex(byteValue(emu, a1 + 2)),
                    hex(byteValue(emu, a1 + 3)),
                    hex(word(emu, a1 + 4))
                ));
            }
        }

        if (a3 == 0x03L) {
            if (a1 != a0 + 0x10 || word(emu, a1) != word(emu, a0 + 0x10)) {
                throw new Exception(String.format(
                    "text payload layout mismatch id=%s a0=%s a1=%s",
                    hex(id),
                    hex(a0),
                    hex(a1)
                ));
            }
        }
    }

    private void verifyMetadataPayload(
        EmulatorHelper emu,
        int metadataIndex,
        int metadataByte
    ) throws Exception {
        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
        long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
        long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;

        if (a3 != 0x03L) {
            return;
        }

        long tableBase = word(emu, 0x40155338L);
        long expectedTableValue = half(emu, tableBase + metadataIndex * 2L);
        long actualTableValue = half(emu, a1);
        long actualMetadataByte = byteValue(emu, a1 + 2);
        long actualByteAtThree = byteValue(emu, a1 + 3);
        long actualWordAtFour = word(emu, a1 + 4);
        long actualWordAtEight = word(emu, a1 + 8);

        if (a1 != a0 + 0x10
            || actualTableValue != expectedTableValue
            || actualMetadataByte != (metadataByte & 0xffL)
            || actualByteAtThree != STACK_SENTINEL
            || actualWordAtFour != 0
            || actualWordAtEight != 0xa5a5a5a5L) {
            throw new Exception(String.format(
                "metadata payload mismatch index=%d a0=%s a1=%s table=%s/%s byte=%s byte3=%s word4=%s word8=%s",
                metadataIndex,
                hex(a0),
                hex(a1),
                hex(actualTableValue),
                hex(expectedTableValue),
                hex(actualMetadataByte),
                hex(actualByteAtThree),
                hex(actualWordAtFour),
                hex(actualWordAtEight)
            ));
        }
    }

    private int[] expectedIds(String mode) {
        if ("initial".equals(mode)) return new int[] {0x18c3, 0, 0x39e7, 0, 0x18c3, 0, 0x1002};
        if ("metadata".equals(mode)) return new int[] {0x1002, 0x2005};
        if ("metadata-table".equals(mode)) return new int[] {0x1002, 0x2005};
        if ("transport".equals(mode)) return new int[] {0x1002, 0x1004, 0x1003};
        if ("status-low".equals(mode)) return new int[] {0x1002, 0x1000};
        if ("status-high".equals(mode)) return new int[] {0x1002, 0x1001};
        if ("controls".equals(mode)) return new int[] {0x1002, 0x1007, 0x1006, 0x1008, 0x1005, 0x1004, 0x1002};
        throw new IllegalArgumentException("unknown mode " + mode);
    }

    private int flagFor(String mode) {
        if ("initial".equals(mode)) return 0x001;
        if ("metadata".equals(mode)) return 0x100;
        if ("metadata-table".equals(mode)) return 0x100;
        if ("transport".equals(mode)) return 0x020;
        if ("status-low".equals(mode)) return 0x200;
        if ("status-high".equals(mode)) return 0x800;
        if ("controls".equals(mode)) return 0x008;
        throw new IllegalArgumentException("unknown mode " + mode);
    }

    private void runCase(
        Memory memory,
        String mode,
        byte[] stubs,
        int metadataIndex,
        int metadataByte
    ) throws Exception {
        memory.setBytes(addr(RAM), new byte[0x4000]);
        fill(memory, STACK - 0x100, 0x200, STACK_SENTINEL);
        int flag = flagFor(mode);
        putWord(memory, FLAGS, flag);
        putByte(memory, UI_STATE + 0x19, metadataIndex);
        putByte(memory, UI_STATE + 0x0e, metadataByte);
        putByte(memory, PLAY_STATE, 2);
        putByte(memory, PLAY_STATE + 2, 1);

        // Each case runs in the same Ghidra program, so restore the original
        // literal-pool values before applying this case's RAM redirects.
        putWord(memory, 0x40155324L, 0xbfc16405L);
        putWord(memory, 0x40155334L, 0x9fc15418L);
        putWord(memory, 0x4015533cL, 0x9fc156f4L);
        putWord(memory, 0x4015534cL, 0x9fc156ccL);
        putWord(memory, 0x40155340L, 0xbfc16535L);
        expectWord(memory, 0x40155324L, 0xbfc16405L);
        expectWord(memory, 0x40155334L, 0x9fc15418L);
        expectWord(memory, 0x4015533cL, 0x9fc156f4L);
        expectWord(memory, 0x4015534cL, 0x9fc156ccL);
        expectWord(memory, 0x40155340L, 0xbfc16535L);
        putWord(memory, 0x40155324L, STUBS + 1);
        putWord(memory, 0x40155334L, FLAGS);
        putWord(memory, 0x4015533cL, UI_STATE);
        putWord(memory, 0x4015534cL, PLAY_STATE);
        putWord(memory, 0x40155340L, STUBS + 0x21);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        List<String> events = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
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
                    int[] expected = expectedIds(mode);
                    int[] actual = new int[ids.size()];
                    for (int i = 0; i < ids.size(); i++) actual[i] = ids.get(i);
                    if (!Arrays.equals(actual, expected)) {
                        throw new Exception(mode + " IDs=" + Arrays.toString(actual)
                            + " expected=" + Arrays.toString(expected));
                    }
                    println(String.format(
                        "NOW PLAYING STATE DYNAMIC PASS mode=%s flag=0x%x metadata-index=%d metadata-byte=0x%02x steps=%d dispatches=%d",
                        mode, flag, metadataIndex, metadataByte, steps, events.size()
                    ));
                    for (String event : events) println("  " + event);
                    return;
                }
                if (base == STUBS) {
                    verifyPayloadLayout(emu);
                    if ("metadata".equals(mode) || "metadata-table".equals(mode)) {
                        verifyMetadataPayload(emu, metadataIndex, metadataByte);
                    }
                    ids.add(dispatchId(emu));
                    events.add(dispatchSummary(emu));
                    emu.writeRegister("v0", 1);
                    long ra = emu.readRegister("ra").longValue() & 0xffffffffL;
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(mode + " stopped at " + hex(pc) + ": " + emu.getLastError());
                }
            }
            throw new Exception(mode + " exceeded step limit at "
                + hex(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }

    private void runMetadataTable(Memory memory, byte[] stubs) throws Exception {
        for (int metadataIndex = 0; metadataIndex < 8; metadataIndex++) {
            runCase(memory, "metadata-table", stubs, metadataIndex, 0x5a);
        }
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 1) {
            throw new Exception("usage: RunNowPlayingStateProbe.java STUBS.BIN [mode ...]");
        }
        Memory memory = currentProgram.getMemory();
        byte[] stubs = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        MemoryBlock stubBlock = memory.createInitializedBlock(
            "now_playing_state_probe_stubs", addr(STUBS),
            new ByteArrayInputStream(stubs), stubs.length, monitor, false);
        stubBlock.setRead(true); stubBlock.setWrite(false); stubBlock.setExecute(true);
        ramBlock(memory);
        List<String> modes = new ArrayList<>();
        boolean runAllModes = getScriptArgs().length == 1 || "all".equals(getScriptArgs()[1]);
        boolean runMetadataTable = runAllModes
            || "metadata-table".equals(getScriptArgs()[1]);
        if (runAllModes) {
            modes.addAll(Arrays.asList(
                "initial", "metadata", "transport", "status-low", "status-high", "controls"
            ));
        } else if (!runMetadataTable) {
            modes.addAll(Arrays.asList(getScriptArgs()).subList(1, getScriptArgs().length));
        }
        for (String mode : modes) {
            runCase(memory, mode, stubs, 0, 0);
        }
        if (runMetadataTable) {
            runMetadataTable(memory, stubs);
        }
    }
}

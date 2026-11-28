// Execute the real resident browser-scene caller and the AP scene loop through
// its refresh callback. Only external service callbacks are RAM-stubbed.
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
import java.util.HashMap;
import java.util.Map;

public class RunBrowserSceneRefreshChainProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc16600L;
    private static final long SCENE = 0x40cd4e00L;
    private static final long EVENT_PREDICATE = 0x40cd5058L;
    private static final long LIST_STATE_CALLBACK = 0x487546d8L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long STATE = RAM + 0x100L;
    private static final long FSEL = RAM + 0x300L;
    private static final long CONTROL = RAM + 0x500L;
    private static final long PATH_INFO = STATE + 8;
    private static final long LIST_STATE = RAM + 0x400L;
    private static final long MODE_VALUE = RAM + 0x480L;
    private static final long ENTRIES = RAM + 0x600L;
    private static final long TITLE = RAM + 0x800L;
    private static final long NAMES = RAM + 0x900L;
    private static final long FALLBACK_STATE = RAM + 0x3000L;
    private static final long STUB_BASE = RAM + 0x4000L;
    private static final long STACK = RAM + 0x7000L;
    private static final int ROWS = 7;
    private int listCount = ROWS;
    private int listBase;
    private String nameEncoding = "utf16";

    private static final int NOOP = 1;
    private static final int INIT = 2;
    private static final int FIRST_PREDICATE = 3;
    private static final int READY = 4;
    private static final int ROW = 5;
    private static final int FINISH = 6;
    private static final int RESET_STATE = 7;
    private static final int LIST_UPDATE_A = 8;
    private static final int LIST_UPDATE_B = 9;

    private final Map<Long, Integer> stubs = new HashMap<>();
    private int rowCalls;
    private int readyCalls;
    private int initCalls;
    private int resetCalls;
    private int listUpdateACalls;
    private int listUpdateBCalls;
    private int listStateCalls;
    private int eventPredicateCalls;
    private long listStateTitleSource;
    private long listStateRowSource;
    private int listStateTitleLoadCalls;
    private int listStateRowLoadCalls;
    private int currentListStateRowLoads;
    private boolean listStateSourceLoadsValid = true;
    private long listStateReturn;
    private int listStateOutputCalls;
    private long listStateTitleOutput;
    private long listStateRowOutput;
    private long listStateTitleLength;
    private long listStateRowLength;
    private final int[] listStateRowFlags = new int[ROWS];
    private final int[] listStateRowMarkers = new int[ROWS];
    private boolean listStateRowsValid = true;
    private boolean listStateFlagsValid = true;
    private boolean listStateMarkersValid = true;
    private long sceneReturn;
    private long sceneContext;

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

    private void putBytes(Memory memory, long address, byte[] bytes) throws Exception {
        memory.setBytes(addr(address), bytes);
    }

    private void putWord(EmulatorHelper emu, long address, long value) throws Exception {
        emu.writeMemory(addr(address), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private void putHalf(EmulatorHelper emu, long address, int value) throws Exception {
        emu.writeMemory(addr(address), new byte[] {(byte)value, (byte)(value >>> 8)});
    }

    private void putByte(EmulatorHelper emu, long address, int value) throws Exception {
        emu.writeMemory(addr(address), new byte[] {(byte)value});
    }

    private void putBytes(EmulatorHelper emu, long address, byte[] bytes) throws Exception {
        emu.writeMemory(addr(address), bytes);
    }

    private long word(EmulatorHelper emu, long address) throws Exception {
        byte[] bytes = emu.readMemory(addr(address), 4);
        return (bytes[0] & 0xffL) | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16) | ((bytes[3] & 0xffL) << 24);
    }

    private long half(EmulatorHelper emu, long address) throws Exception {
        byte[] bytes = emu.readMemory(addr(address), 2);
        return (bytes[0] & 0xffL) | ((bytes[1] & 0xffL) << 8);
    }

    private int byteValue(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xff;
    }

    private void ramBlock(Memory memory, String name, long base, int size) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(name, addr(base),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private long stub(int id) {
        long address = STUB_BASE + id * 0x20L;
        stubs.put(address, id);
        return address | 1L;
    }

    private void redirect(Memory memory, long literal, int id) throws Exception {
        putWord(memory, literal, stub(id));
    }

    private void seedListStateSources(EmulatorHelper emu) throws Exception {
        putWord(emu, 0x9fc15598L, 0);
        putHalf(emu, 0x9fc1559aL, ROWS);
        putHalf(emu, 0x9fc15598L, listBase);
        putWord(emu, 0x9fc1558cL, FALLBACK_STATE);
        putByte(emu, FALLBACK_STATE + 0xeL, 0x42);
        putWord(emu, 0x9fc155a0L, TITLE);
        putHalf(emu, 0x9fc155a6L, ROWS);
        putWord(emu, 0x9fc155f8L, 0);
        putHalf(emu, 0x9fc155faL, listCount);
        putBytes(emu, TITLE, new byte[] {
            (byte)0xff, (byte)0xfe, 'R', 0, 'o', 0, 'o', 0, 't', 0, 0, 0
        });
        for (int row = 0; row < ROWS; row++) {
            long name = NAMES + row * 0x40L;
            putWord(emu, 0x9fc155a4L + row * 0xcL + 8, name);
            if (nameEncoding.equals("utf16")) {
                putBytes(emu, name, new byte[] {
                    (byte)0xff, (byte)0xfe, 'F', 0, 'o', 0, 'l', 0, 'd', 0,
                    (byte)('1' + row), 0, 0, 0
                });
            } else if (nameEncoding.equals("utf8")) {
                putBytes(emu, name, new byte[] {
                    (byte)0xef, (byte)0xbb, (byte)0xbf, 'F', 'o', 'l', 'd',
                    (byte)('1' + row), 0
                });
            } else {
                putBytes(emu, name, new byte[] {'F', 'o', 'l', 'd', (byte)('1' + row), 0});
            }
        }
    }

    private void installStubs(Memory memory) throws Exception {
        redirect(memory, 0x40cd5010L, NOOP);
        redirect(memory, 0x40cd501cL, NOOP);
        redirect(memory, 0x40cd5028L, INIT);
        redirect(memory, 0x40cd502cL, NOOP);
        redirect(memory, 0x40cd503cL, NOOP);
        putWord(memory, 0x40cd5044L, LIST_STATE_CALLBACK | 1L);
        redirect(memory, 0x40cd504cL, NOOP);
        redirect(memory, 0x40cd5050L, NOOP);
        redirect(memory, 0x40cd5054L, NOOP);
        redirect(memory, 0x40cd5164L, FIRST_PREDICATE);
        redirect(memory, 0x40cd5174L, FINISH);
        putWord(memory, 0x9fc15580L, stub(READY));
        redirect(memory, 0x40cd5180L, NOOP);
        redirect(memory, 0x40cd5200L, RESET_STATE);
        redirect(memory, 0x40cd5210L, LIST_UPDATE_A);
        redirect(memory, 0x40cd5214L, LIST_UPDATE_B);
        putWord(memory, 0x40cd5020L, CONTROL);
        putWord(memory, 0x40cd5024L, ENTRIES);
        putWord(memory, 0x40cd5040L, PATH_INFO);
        putWord(memory, 0x40cd52ccL, MODE_VALUE);
        putWord(memory, 0x40cd52d0L, LIST_STATE);
        putWord(memory, 0x40cd52d4L, ENTRIES);
        redirect(memory, 0x40cd52d8L, ROW);
        redirect(memory, 0x40cd52dcL, NOOP);
        redirect(memory, 0x40cd53a0L, NOOP);
    }

    private void handleStub(EmulatorHelper emu, Memory memory, int id) throws Exception {
        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
        long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
        long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
        long ra = emu.readRegister("ra").longValue() & 0xffffffffL;
        long result = 0;
        if (id == INIT) {
            initCalls++;
            if (a0 != CONTROL || a1 != ENTRIES || a2 != FSEL) {
                throw new Exception(String.format("scene init ABI mismatch a0=0x%08x a1=0x%08x a2=0x%08x", a0, a1, a2));
            }
        } else if (id == FIRST_PREDICATE) {
            result = 0;
        } else if (id == FINISH) {
            result = 1;
        } else if (id == READY) {
            readyCalls++;
            result = readyCalls == 1 ? 0xffff8014L : 1L;
        } else if (id == ROW) {
            int expectedIndex = rowCalls;
            if (a0 != ENTRIES + expectedIndex * 0xcL || a1 != LIST_STATE
                || a2 != expectedIndex || expectedIndex >= ROWS) {
                throw new Exception(String.format(
                    "refresh row ABI mismatch call=%d a0=0x%08x a1=0x%08x a2=0x%08x control+8=%d control+10=%d list+2=%d control+0x14=%d ready=%d init=%d reset=%d updateA=%d updateB=%d",
                    rowCalls, a0, a1, a2, word(emu, CONTROL + 8) & 0xffffL,
                    word(emu, CONTROL + 10) & 0xffffL, word(emu, LIST_STATE + 2) & 0xffffL,
                    word(emu, CONTROL + 0x14) & 0xffL, readyCalls, initCalls, resetCalls,
                    listUpdateACalls, listUpdateBCalls));
            }
            rowCalls++;
            result = 1;
        } else if (id == RESET_STATE) {
            resetCalls++;
            if (a0 != 0x9fc1561cL || a1 != CONTROL || a2 != 0x18L) {
                throw new Exception(String.format(
                    "state-init ABI mismatch a0=0x%08x a1=0x%08x a2=0x%08x", a0, a1, a2));
            }
            // The real service copies/initializes the 0x18-byte list state;
            // seed a bounded seven-row window in its synthetic replacement.
            putHalf(memory, a1 + 8, 1);
            putHalf(memory, a1 + 10, ROWS);
        } else if (id == LIST_UPDATE_A) {
            listUpdateACalls++;
        } else if (id == LIST_UPDATE_B) {
            listUpdateBCalls++;
        }
        emu.writeRegister("v0", result);
        emu.writeRegister("pc", ra & ~1L);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length > 3) {
            throw new Exception("usage: RunBrowserSceneRefreshChainProbe.java [synthetic-list-count [synthetic-list-base [utf16|utf8|plain]]]");
        }
        if (getScriptArgs().length == 1) {
            listCount = Integer.decode(getScriptArgs()[0]);
        } else if (getScriptArgs().length == 2) {
            listCount = Integer.decode(getScriptArgs()[0]);
            listBase = Integer.decode(getScriptArgs()[1]);
        } else if (getScriptArgs().length == 3) {
            listCount = Integer.decode(getScriptArgs()[0]);
            listBase = Integer.decode(getScriptArgs()[1]);
            nameEncoding = getScriptArgs()[2];
        }
        if (listCount < 0 || listCount > 0xffff || listBase < 0 || listBase > 0xffff) {
            throw new Exception("synthetic list count/base must fit unsigned halfwords");
        }
        if (!nameEncoding.equals("utf16") && !nameEncoding.equals("utf8")
            && !nameEncoding.equals("plain")) {
            throw new Exception("name encoding must be utf16, utf8, or plain");
        }
        Memory memory = currentProgram.getMemory();
        ramBlock(memory, "browser_scene_chain_ram", RAM, 0x8000);
        ramBlock(memory, "browser_scene_chain_guest_ram", 0x9fc10000L, 0x30000);
        putWord(memory, 0xbfc166e8L, FSEL);
        putWord(memory, 0xbfc166ecL, 0);
        installStubs(memory);
        putWord(memory, 0x9fc1a1d2L, 1);
        putByte(memory, PATH_INFO, 0xff);
        putByte(memory, PATH_INFO + 1, 1);
        putWord(memory, PATH_INFO + 8, 0); // initialized by the real resident scene caller
        putHalf(memory, LIST_STATE + 2, ROWS);
        // Seed only the synthetic visible window. The real service that fills
        // these bounds is outside the extracted firmware and remains stubbed.
        putHalf(memory, CONTROL + 8, 1);
        putHalf(memory, CONTROL + 10, ROWS);
        putWord(memory, 0x9fc15580L, stub(READY));
        putWord(memory, 0x9fc15598L, 0);
        putByte(memory, 0x9fc1559dL, 0);
        putHalf(memory, MODE_VALUE, 0);
        putByte(memory, FSEL, 0);
        putByte(memory, FSEL + 4, 0);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int guestSteps = 0;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("s1", STATE);
            emu.writeRegister("t8", 0);
            emu.writeRegister("a0", 0);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            Register isa = currentProgram.getRegister("ISA_MODE");
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 200000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                long base = pc & ~1L;
                if (listStateReturn != 0 && base == (listStateReturn & ~1L)) {
                    listStateTitleOutput = word(emu, PATH_INFO);
                    listStateRowOutput = word(emu, PATH_INFO + 0xc);
                    listStateTitleLength = half(emu, PATH_INFO + 6);
                    listStateRowLength = half(emu, PATH_INFO + 0x12);
                    for (int row = 0; row < ROWS; row++) {
                        long rowPointer = word(emu, PATH_INFO + 0xc + row * 0xcL);
                        long rowLength = half(emu, PATH_INFO + 0x12 + row * 0xcL);
                        int rowFlags = (int)half(emu, PATH_INFO + 0x14 + row * 0xcL);
                        int rowMarker = byteValue(emu, PATH_INFO + 0x10 + row * 0xcL);
                        listStateRowsValid &= rowPointer == NAMES + row * 0x40L;
                        listStateRowsValid &= rowLength == 0x32;
                        if (listStateOutputCalls == 0) {
                            listStateRowFlags[row] = rowFlags;
                            listStateRowMarkers[row] = rowMarker;
                            int expectedMarker = nameEncoding.equals("utf16") ? 0x7d
                                : nameEncoding.equals("utf8") ? 0x7c : 0x42;
                            listStateMarkersValid &= rowMarker == expectedMarker;
                            int rowIndex = row + 1;
                            int expectedFlags = 0;
                            if (listCount < rowIndex) {
                                expectedFlags = 0x8000;
                                if (listBase + listCount < rowIndex) expectedFlags |= 0x0100;
                            }
                            listStateFlagsValid &= rowFlags == expectedFlags;
                        }
                    }
                    listStateSourceLoadsValid &= currentListStateRowLoads == ROWS;
                    listStateOutputCalls++;
                    listStateReturn = 0;
                }
                if (base == 0x487546e6L) {
                    long loadedTitle = emu.readRegister("v0").longValue() & 0xffffffffL;
                    listStateTitleLoadCalls++;
                    listStateSourceLoadsValid &= loadedTitle == word(emu, 0x9fc155a0L);
                }
                if (base == 0x48754798L) {
                    long destination = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long loadedRow = emu.readRegister("v0").longValue() & 0xffffffffL;
                    int row = currentListStateRowLoads;
                    long sourceAddress = 0x9fc155acL + row * 0xcL;
                    long destinationAddress = PATH_INFO + row * 0xcL;
                    listStateSourceLoadsValid &= row < ROWS;
                    listStateSourceLoadsValid &= destination == destinationAddress;
                    listStateSourceLoadsValid &= row < ROWS && loadedRow == word(emu, sourceAddress);
                    listStateRowLoadCalls++;
                    currentListStateRowLoads++;
                }
                if (base == EVENT_PREDICATE) {
                    long ra = emu.readRegister("ra").longValue() & 0xffffffffL;
                    eventPredicateCalls++;
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (base == LIST_STATE_CALLBACK) {
                    long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                    if (a0 != CONTROL || a1 != PATH_INFO) {
                        throw new Exception(String.format(
                            "list-state callback ABI mismatch a0=0x%08x a1=0x%08x", a0, a1));
                    }
                    seedListStateSources(emu);
                    listStateTitleSource = word(emu, 0x9fc155a0L);
                    listStateRowSource = word(emu, 0x9fc155acL);
                    currentListStateRowLoads = 0;
                    listStateReturn = emu.readRegister("ra").longValue() & 0xffffffffL;
                    listStateCalls++;
                }
                if (base == SCENE) {
                    long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                    long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                    sceneReturn = emu.readRegister("ra").longValue() & 0xffffffffL;
                    if (a0 != 0x6000L || a1 != FSEL || a2 != PATH_INFO
                        || word(emu, a2 + 8) != STATE + 24) {
                        throw new Exception(String.format(
                            "scene entry ABI mismatch a0=0x%08x a1=0x%08x a2=0x%08x ctx=0x%08x",
                            a0, a1, a2, word(emu, a2 + 8)));
                    }
                    sceneContext = word(emu, a2 + 8);
                }
                if (sceneReturn != 0 && base == (sceneReturn & ~1L)) {
                    if (rowCalls != ROWS || readyCalls != 2 || initCalls != 1 || resetCalls != 1
                        || listStateCalls != 2 || eventPredicateCalls != 2 || listStateOutputCalls != 2
                        || listStateTitleOutput != TITLE || listStateRowOutput != NAMES
                        || listStateTitleLength != 0x32 || listStateRowLength != 0x32
                        || !listStateRowsValid || !listStateFlagsValid || !listStateMarkersValid
                        || !listStateSourceLoadsValid
                        || listStateTitleLoadCalls != 2 || listStateRowLoadCalls != ROWS * 2) {
                        throw new Exception(String.format(
                            "chain incomplete row-calls=%d ready-calls=%d init-calls=%d reset-calls=%d event-predicates=%d list-state-calls=%d list-state-returns=%d title-source=0x%08x title-loads=%d row-source=0x%08x row-loads=%d title-out=0x%08x title-length=0x%x row0-out=0x%08x row0-length=0x%x all-row-slots-valid=%s row-flags-valid=%s flags=%s row-markers-valid=%s markers=%s source-loads-valid=%s final-title=0x%08x final-row0=0x%08x",
                            rowCalls, readyCalls, initCalls, resetCalls, eventPredicateCalls, listStateCalls,
                            listStateOutputCalls, listStateTitleSource, listStateTitleLoadCalls,
                            listStateRowSource, listStateRowLoadCalls,
                            listStateTitleOutput, listStateTitleLength, listStateRowOutput,
                            listStateRowLength, listStateRowsValid, listStateFlagsValid,
                            java.util.Arrays.toString(listStateRowFlags), listStateMarkersValid,
                            java.util.Arrays.toString(listStateRowMarkers), listStateSourceLoadsValid,
                            word(emu, PATH_INFO), word(emu, PATH_INFO + 0xc)));
                    }
                    println(String.format(
                        "BROWSER SCENE REFRESH CHAIN PASS loop-iterations=%d guest-steps=%d scene=0x%08x event-predicate-forced-zero=%d list-state=0x%08x refresh=0x40cd5218 context=0x%08x rows=%d indices=0..%d synthetic-count=%d synthetic-base=%d encoding=%s row-flags=%s row-markers=%s",
                        steps, guestSteps, SCENE, eventPredicateCalls, LIST_STATE_CALLBACK, sceneContext,
                        rowCalls, ROWS - 1, listCount, listBase, nameEncoding,
                        java.util.Arrays.toString(listStateRowFlags), java.util.Arrays.toString(listStateRowMarkers)));
                    return;
                }
                Integer id = stubs.get(base);
                if (id != null) {
                    handleStub(emu, memory, id);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("execution stopped at 0x%08x: %s", pc, emu.getLastError()));
                }
                guestSteps++;
            }
            throw new Exception("scene chain exceeded step limit at 0x"
                + Long.toHexString(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }

    private void putByte(Memory memory, long address, int value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value});
    }
}

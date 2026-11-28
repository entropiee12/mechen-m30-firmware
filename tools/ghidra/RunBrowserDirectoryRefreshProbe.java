// Execute the browser refresh fallback through its real fsel dispatcher and
// directory callback. Resident filesystem/converter services remain synthetic.
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

public class RunBrowserDirectoryRefreshProbe extends GhidraScript {
    private static final long ENTRY = 0x40cd5218L;
    private static final long DISPATCHER = 0x780db22cL;
    private static final long DIRECTORY_CALLBACK = 0x71352820L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long CONTROL = RAM + 0x100L;
    private static final long PATH = RAM + 0x200L;
    private static final long LIST_STATE = RAM + 0x400L;
    private static final long ENTRIES = RAM + 0x600L;
    private static final long NAMES = RAM + 0x1000L;
    private static final long CURRENT = RAM + 0x1800L;
    private static final long GLOBAL = RAM + 0x1810L;
    private static final long FS_ARGUMENT = RAM + 0x1840L;
    private static final long CONTEXT = RAM + 0x1870L;
    private static final long RESULT = RAM + 0x1880L;
    private static final long FAILURE = RAM + 0x1884L;
    private static final long MODE_VALUE = RAM + 0x1890L;
    private static final long DB_STUB = RAM + 0x2000L;
    private static final long FS_STUB = RAM + 0x2040L;
    private static final long CONVERTER_STUB = RAM + 0x2080L;
    private static final long ROW_STUB = RAM + 0x20c0L;
    private static final long STACK = RAM + 0x3000L;
    private static final int ROWS = 7;

    private int dispatchCalls;
    private int directoryCalls;
    private int databaseCalls;
    private int filesystemCalls;
    private int converterCalls;
    private int rowStubCalls;
    private final List<Long> rowCallbackIndices = new ArrayList<>();
    private int listStateCount;
    private long dispatchMode;
    private long dispatchCount;
    private long dispatchStart;
    private long dispatchTable;
    private long dispatchDescriptor;
    private long originalUpdateCallback;
    private long directoryRows;
    private long directoryTable;
    private boolean filesystemDestinationsValid = true;
    private boolean filesystemArgumentsValid = true;
    private long firstFilesystemDestination;
    private final long[] filesystemLengths = new long[ROWS];
    private final long[] filesystemModes = new long[ROWS];
    private long firstConverterRow;
    private long firstConverterContext;
    private long firstConverterLimit;

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

    private long word(EmulatorHelper emu, long address) throws Exception {
        byte[] bytes = emu.readMemory(addr(address), 4);
        return (bytes[0] & 0xffL) | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16) | ((bytes[3] & 0xffL) << 24);
    }

    private long half(EmulatorHelper emu, long address) throws Exception {
        byte[] bytes = emu.readMemory(addr(address), 2);
        return (bytes[0] & 0xffL) | ((bytes[1] & 0xffL) << 8);
    }

    private long byteValue(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xffL;
    }

    private byte[] providerName() {
        String text = "VeryLongFolderName12345";
        byte[] bytes = new byte[50];
        bytes[0] = (byte)0xff;
        bytes[1] = (byte)0xfe;
        for (int index = 0; index < text.length(); index++) {
            bytes[2 + index * 2] = (byte)text.charAt(index);
            bytes[3 + index * 2] = (byte)(text.charAt(index) >>> 8);
        }
        return bytes;
    }

    private void ramBlock(Memory memory, String name, long base, int size) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(name, addr(base),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private void seed(Memory memory, int totalRows) throws Exception {
        listStateCount = totalRows;
        memory.setBytes(addr(RAM), new byte[0x4000]);

        byte[] callbackWord = new byte[4];
        memory.getBytes(addr(0x40cd52dcL), callbackWord);
        originalUpdateCallback = (callbackWord[0] & 0xffL)
            | ((callbackWord[1] & 0xffL) << 8) | ((callbackWord[2] & 0xffL) << 16)
            | ((callbackWord[3] & 0xffL) << 24);

        putWord(memory, 0x40cd52ccL, MODE_VALUE);
        putWord(memory, 0x40cd52d0L, LIST_STATE);
        putWord(memory, 0x40cd52d4L, ENTRIES);
        putWord(memory, 0x40cd52d8L, ROW_STUB | 1L);
        putWord(memory, 0x40cd52dcL, DISPATCHER | 1L);
        putWord(memory, 0x780db48cL, RESULT);
        putWord(memory, 0x780db490L, FS_ARGUMENT);
        putWord(memory, 0x780db494L, DB_STUB | 1L);
        putWord(memory, 0x780db498L, FAILURE);
        putWord(memory, 0x71352a5cL, CURRENT);
        putWord(memory, 0x71352a60L, GLOBAL);
        putWord(memory, 0x71352a64L, FS_ARGUMENT);
        putWord(memory, 0x71352a68L, FS_STUB | 1L);
        putWord(memory, 0x71352a6cL, CONTEXT);
        putWord(memory, 0x71352a70L, CONVERTER_STUB | 1L);

        putWord(memory, CONTROL + 4, PATH);
        putHalf(memory, CONTROL + 8, 1);
        putHalf(memory, CONTROL + 10, ROWS);
        putByte(memory, CONTROL + 0x14, 3);
        putWord(memory, PATH + 8, RAM + 0x280);
        // Sweep this boundary to measure how much of the requested row window
        // takes the ordinary callback before the real fsel fallback runs.
        putHalf(memory, LIST_STATE + 2, totalRows);
        putHalf(memory, MODE_VALUE, 0);
        putByte(memory, 0x9fc15734L, 0); // fsel type 0: directory
        putWord(memory, CURRENT, 1);
        putWord(memory, GLOBAL + 4, FS_ARGUMENT);
        for (int row = 0; row < ROWS; row++) {
            long entry = ENTRIES + row * 0xcL;
            putByte(memory, entry + 4, 0x32);
            putWord(memory, entry + 8, NAMES + row * 0x40L);
        }
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        ramBlock(memory, "browser_directory_refresh_ram", RAM, 0x4000);
        ramBlock(memory, "browser_directory_refresh_guest_ram", 0x9fc10000L, 0x30000);
        int totalRows = getScriptArgs().length == 0 ? 0 : Integer.decode(getScriptArgs()[0]);
        if (totalRows < 0 || totalRows > 8) throw new Exception("list-state count must be between 0 and 8");
        seed(memory, totalRows);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
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

            for (int steps = 0; steps < 200000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                long base = pc & ~1L;
                if (base == STOP) {
                    verify(emu, steps, totalRows);
                    return;
                }
                if (base == DISPATCHER) {
                    long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    dispatchCalls++;
                    dispatchDescriptor = a0;
                    dispatchMode = byteValue(emu, a0);
                    dispatchCount = byteValue(emu, a0 + 1);
                    dispatchStart = half(emu, a0 + 2);
                    dispatchTable = emu.readRegister("a1").longValue() & 0xffffffffL;
                } else if (base == DIRECTORY_CALLBACK) {
                    directoryCalls++;
                    directoryRows = emu.readRegister("a0").longValue() & 0xffffffffL;
                    directoryTable = emu.readRegister("a1").longValue() & 0xffffffffL;
                } else if (base == DB_STUB) {
                    databaseCalls++;
                    emu.writeRegister("v0", 1);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                } else if (base == FS_STUB) {
                    long destination = emu.readRegister("a1").longValue() & 0xffffffffL;
                    long expectedDestination = NAMES + Math.min(listStateCount, ROWS) * 0x40L
                        + filesystemCalls * 0x40L;
                    filesystemDestinationsValid &= filesystemCalls < ROWS;
                    filesystemDestinationsValid &= filesystemCalls < ROWS
                        && destination == expectedDestination;
                    filesystemCalls++;
                    if (filesystemCalls == 1) firstFilesystemDestination = destination;
                    int callIndex = filesystemCalls - 1;
                    if (callIndex < ROWS) {
                        filesystemLengths[callIndex] = emu.readRegister("a2").longValue() & 0xffffffffL;
                        filesystemModes[callIndex] = word(emu,
                            (emu.readRegister("sp").longValue() & 0xffffffffL) + 0x10);
                        filesystemArgumentsValid &= filesystemLengths[callIndex] == 25
                            && filesystemModes[callIndex] == 2;
                    } else {
                        filesystemArgumentsValid = false;
                    }
                    emu.writeMemory(addr(destination), providerName());
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                } else if (base == CONVERTER_STUB) {
                    converterCalls++;
                    if (converterCalls == 1) {
                        firstConverterRow = emu.readRegister("a0").longValue() & 0xffffffffL;
                        firstConverterContext = emu.readRegister("a1").longValue() & 0xffffffffL;
                        firstConverterLimit = emu.readRegister("a2").longValue() & 0xffffffffL;
                    }
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                } else if (base == ROW_STUB) {
                    rowStubCalls++;
                    rowCallbackIndices.add(emu.readRegister("a2").longValue() & 0xffffffffL);
                    emu.writeRegister("v0", 1);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
                }
            }
            throw new Exception("directory refresh exceeded step limit at 0x"
                + Long.toHexString(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }

    private void verify(EmulatorHelper emu, int steps, int totalRows) throws Exception {
        int normalRows = Math.min(totalRows, ROWS);
        int fallbackRows = ROWS - normalRows;
        long expectedTable = ENTRIES + normalRows * 0xcL;
        long expectedName = NAMES + normalRows * 0x40L;
        boolean callbackIndicesValid = rowCallbackIndices.size() == normalRows;
        for (int index = 0; index < rowCallbackIndices.size(); index++) {
            callbackIndicesValid &= rowCallbackIndices.get(index) == index;
        }
        boolean fallbackValid = fallbackRows == 0
            ? dispatchCalls == 0 && directoryCalls == 0 && filesystemCalls == 0
                && converterCalls == 0 && databaseCalls == 0
            : dispatchCalls == 1 && dispatchMode == 3 && dispatchCount == fallbackRows
                && dispatchStart == 1 && dispatchTable == expectedTable
                && directoryCalls == 1 && directoryRows == dispatchDescriptor
                && directoryTable == expectedTable && filesystemCalls == fallbackRows
                && converterCalls == fallbackRows && databaseCalls == fallbackRows + 1
                && firstFilesystemDestination == expectedName
                && firstConverterRow == expectedTable && firstConverterContext == CONTEXT
                && firstConverterLimit == 4;
        if (originalUpdateCallback != (DISPATCHER | 1L)
            || !fallbackValid || rowStubCalls != normalRows || !callbackIndicesValid
            || !filesystemDestinationsValid || !filesystemArgumentsValid) {
            throw new Exception(String.format(
                "directory refresh mismatch steps=%d list-count=%d original-callback=0x%08x expected=0x%08x dispatch=%d descriptor=0x%08x mode=%d count=%d start=%d table=0x%08x dir=%d dir-rows=0x%08x dir-table=0x%08x row-stub=%d callback-indices-valid=%s db=%d fs=%d dest=0x%08x destinations-valid=%s fs-args-valid=%s convert=%d row=0x%08x context=0x%08x limit=%d",
                steps, listStateCount, originalUpdateCallback, DISPATCHER | 1L, dispatchCalls, dispatchDescriptor,
                dispatchMode, dispatchCount, dispatchStart, dispatchTable, directoryCalls, directoryRows,
                directoryTable, rowStubCalls, callbackIndicesValid, databaseCalls, filesystemCalls,
                firstFilesystemDestination, filesystemDestinationsValid, filesystemArgumentsValid,
                converterCalls, firstConverterRow, firstConverterContext, firstConverterLimit));
        }
        byte[] expected = providerName();
        for (int row = normalRows; row < ROWS; row++) {
            byte[] actual = emu.readMemory(addr(NAMES + row * 0x40L), expected.length);
            for (int index = 0; index < expected.length; index++) {
                if (actual[index] != expected[index]) {
                    throw new Exception(String.format("row %d name mismatch at byte %d", row, index));
                }
            }
        }
        if (fallbackRows == 0) {
            println(String.format(
                "BROWSER REFRESH BOUNDARY PASS list-count=%d normal-rows=%d fallback-rows=0 steps=%d original-callback=0x%08x dispatcher=0x%08x filesystem-calls=0",
                listStateCount, normalRows, steps, originalUpdateCallback, DISPATCHER));
        } else {
            println(String.format(
                "BROWSER REFRESH BOUNDARY PASS list-count=%d normal-rows=%d fallback-rows=%d steps=%d original-callback=0x%08x dispatcher=0x%08x fsel-type=0 directory=0x%08x fs-length=25 units fs-mode=2 slot=0x32 bytes",
                listStateCount, normalRows, fallbackRows, steps, originalUpdateCallback, DISPATCHER,
                DIRECTORY_CALLBACK));
        }
    }
}

// Execute the real directory-name callback with only filesystem/provider
// boundaries redirected to RAM stubs. The browser AP remains unchanged.
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

public class RunBrowserDirectoryNameProbe extends GhidraScript {
    private static final long ENTRY = 0x71352820L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long DESCRIPTOR = RAM + 0x100L;
    private static final long ROWS = RAM + 0x200L;
    private static final long GLOBAL = RAM + 0x300L;
    private static final long FS_ARGUMENT = RAM + 0x360L;
    private static final long CURRENT = RAM + 0x380L;
    private static final long RESULT = RAM + 0x390L;
    private static final long FAILURE = RAM + 0x394L;
    private static final long NAME = RAM + 0x400L;
    private static final long DB_STUB = RAM + 0x2000L;
    private static final long FS_STUB = RAM + 0x2040L;
    private static final long CONVERTER_STUB = RAM + 0x2080L;
    private static final long STACK = RAM + 0x3000L;

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

    private byte[] providerName(String nameCase) throws Exception {
        if ("short83".equals(nameCase)) {
            return new byte[] {'F', 'I', 'L', 'E', ' ', ' ', ' ', ' ', 'T', 'X', 'T', 0};
        }
        String text;
        boolean bom;
        if ("lfn-bom".equals(nameCase)) {
            text = "VeryLongFolderName12345"; // 23 UTF-16 units after the BOM.
            bom = true;
        } else if ("lfn-plain".equals(nameCase)) {
            text = "LongFolderName123456789"; // 23 UTF-16 units without a BOM.
            bom = false;
        } else {
            throw new Exception("provider-case must be short83, lfn-bom, or lfn-plain");
        }
        byte[] bytes = new byte[(bom ? 2 : 0) + text.length() * 2 + 2];
        int offset = 0;
        if (bom) {
            bytes[offset++] = (byte)0xff;
            bytes[offset++] = (byte)0xfe;
        }
        for (int i = 0; i < text.length(); i++) {
            bytes[offset++] = (byte)text.charAt(i);
            bytes[offset++] = (byte)(text.charAt(i) >>> 8);
        }
        return bytes;
    }

    private long word(EmulatorHelper emu, long address) throws Exception {
        byte[] bytes = emu.readMemory(addr(address), 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private String hex(long value) {
        return String.format("0x%08x", value & 0xffffffffL);
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private void expectWord(Memory memory, long address, long expected) throws Exception {
        byte[] bytes = new byte[4];
        memory.getBytes(addr(address), bytes);
        long actual = (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
        if (actual != (expected & 0xffffffffL)) {
            throw new Exception(String.format(
                "literal 0x%08x: expected 0x%08x, got 0x%08x",
                address,
                expected,
                actual
            ));
        }
    }

    private void ramBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            "browser_directory_name_probe_ram",
            addr(RAM),
            new ByteArrayInputStream(new byte[0x4000]),
            0x4000,
            monitor,
            false
        );
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 0 && getScriptArgs().length != 3
            && getScriptArgs().length != 4 && getScriptArgs().length != 5) {
            throw new Exception(
                "usage: RunBrowserDirectoryNameProbe.java [current-index start-index row-count [read-result [provider-case]]]"
            );
        }
        int currentIndex = getScriptArgs().length == 3 ? Integer.parseInt(getScriptArgs()[0]) : 5;
        int startIndex = getScriptArgs().length == 3 ? Integer.parseInt(getScriptArgs()[1]) : 5;
        int rowCount = getScriptArgs().length == 3 ? Integer.parseInt(getScriptArgs()[2]) : 1;
        int readResult = getScriptArgs().length == 4 ? Integer.parseInt(getScriptArgs()[3]) : 0;
        String providerCase = getScriptArgs().length == 5 ? getScriptArgs()[4] : "short83";
        if (getScriptArgs().length >= 4) {
            currentIndex = Integer.parseInt(getScriptArgs()[0]);
            startIndex = Integer.parseInt(getScriptArgs()[1]);
            rowCount = Integer.parseInt(getScriptArgs()[2]);
            readResult = Integer.parseInt(getScriptArgs()[3]);
        }
        byte[] providerName = providerName(providerCase);
        if (rowCount <= 0 || rowCount > 3) {
            throw new Exception("row-count must be between one and three");
        }

        Memory memory = currentProgram.getMemory();
        ramBlock(memory);
        memory.setBytes(addr(RAM), new byte[0x4000]);

        // One descriptor covers one 0xc-byte directory row. The current
        // filesystem index equals the descriptor start, so the real callback
        // takes the ordinary name-read branch.
        putBytes(memory, DESCRIPTOR, new byte[] {
            0, (byte)rowCount, (byte)startIndex, (byte)(startIndex >>> 8),
            0x32, 0, 0, 0, 0, 0, 0, 0
        });
        for (int row = 0; row < rowCount; row++) {
            long rowAddress = ROWS + row * 0xcL;
            putBytes(memory, rowAddress, new byte[] {0, 0, 0, 0, 0x32, 0, 0, 0, 0, 0, 0, 0});
            putWord(memory, rowAddress + 8, NAME + row * 0x32L);
        }
        putBytes(memory, NAME, new byte[] {
            'S', 0, 't', 0, 'a', 0, 'l', 0, 'e', 0, 0, 0
        });
        putWord(memory, GLOBAL + 4, FS_ARGUMENT);
        putWord(memory, CURRENT, currentIndex);

        // The extracted pointer table is part of the contract under test.
        expectWord(memory, 0x71352a5cL, 0x9fc157fcL);
        expectWord(memory, 0x71352a60L, 0x9fc1574cL);
        expectWord(memory, 0x71352a64L, 0x9fc15748L);
        expectWord(memory, 0x71352a68L, 0xbfc16a2dL);
        expectWord(memory, 0x71352a6cL, 0x9fc1573cL);
        expectWord(memory, 0x71352a70L, 0xbfc16a21L);
        expectWord(memory, 0x780db48cL, 0x9fc1573cL);
        expectWord(memory, 0x780db490L, 0x9fc15748L);
        expectWord(memory, 0x780db494L, 0xbfc16a2dL);
        expectWord(memory, 0x780db498L, 0x9fc15735L);

        putWord(memory, 0x71352a5cL, CURRENT);
        putWord(memory, 0x71352a60L, GLOBAL);
        putWord(memory, 0x71352a64L, FS_ARGUMENT);
        putWord(memory, 0x71352a68L, FS_STUB | 1L);
        putWord(memory, 0x71352a6cL, RAM + 0x370L);
        putWord(memory, 0x71352a70L, CONVERTER_STUB | 1L);
        putWord(memory, 0x780db48cL, RESULT);
        putWord(memory, 0x780db490L, FS_ARGUMENT);
        putWord(memory, 0x780db494L, DB_STUB | 1L);
        putWord(memory, 0x780db498L, FAILURE);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int databaseCalls = 0;
        int filesystemCalls = 0;
        int converterCalls = 0;
        long filesystemDestination = 0;
        long filesystemLength = 0;
        long filesystemMode = 0;
        long converterRow = 0;
        long converterContext = 0;
        long converterLimit = 0;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            Register isa = currentProgram.getRegister("ISA_MODE");
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", DESCRIPTOR);
            emu.writeRegister("a1", ROWS);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 100000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                long base = pc & ~1L;
                if (base == STOP) {
                    byte[] name = emu.readMemory(addr(NAME), 12);
                    StringBuilder nameHex = new StringBuilder();
                    for (byte value : name) nameHex.append(String.format("%02x", value & 0xff));
                    byte[] slot = emu.readMemory(addr(NAME), 0x32);
                    StringBuilder slotHex = new StringBuilder();
                    for (byte value : slot) slotHex.append(String.format("%02x", value & 0xff));
                    if (readResult == 0) {
                        for (int row = 0; row < rowCount; row++) {
                            byte[] rowSlot = emu.readMemory(addr(NAME + row * 0x32L), 0x32);
                            String rowHex = hex(rowSlot);
                            if ("short83".equals(providerCase)) {
                                byte[] expected = {'F', 'I', 'L', 'E', '.', 'T', 'X', 'T', 0};
                                for (int i = 0; i < expected.length; i++) {
                                    if (rowSlot[i] != expected[i])
                                        throw new Exception("short 8.3 formatter mismatch row="
                                            + row + " bytes=" + rowHex);
                                }
                            } else if ("lfn-bom".equals(providerCase)) {
                                for (int i = 0; i < providerName.length; i++) {
                                    if (rowSlot[i] != providerName[i])
                                        throw new Exception("BOM-prefixed LFN changed row="
                                            + row + " bytes=" + rowHex);
                                }
                            } else if ("lfn-plain".equals(providerCase)) {
                                byte[] expectedPrefix = {
                                    0x4c, 0x00, 0x6f, 0x00, 0x6e, 0x00, 0x67, 0x00,
                                    0x2e, 0x46, 0x00, 0x6f
                                };
                                for (int i = 0; i < expectedPrefix.length; i++) {
                                    if (rowSlot[i] != expectedPrefix[i])
                                        throw new Exception("BOM-less LFN formatter path changed row="
                                            + row + " bytes=" + rowHex);
                                }
                            }
                        }
                    }
                    int preflightCalls = currentIndex < startIndex
                        ? startIndex - currentIndex
                        : startIndex;
                    int expectedDatabaseCalls = preflightCalls + rowCount;
                    int expectedConverterCalls = rowCount;
                    if (databaseCalls != expectedDatabaseCalls || filesystemCalls != rowCount
                        || converterCalls != expectedConverterCalls
                        || filesystemDestination != NAME || filesystemLength != 25
                        || filesystemMode != 2 || converterRow != ROWS
                        || converterContext != RAM + 0x370L || converterLimit != 4) {
                        throw new Exception(String.format(
                            "directory contract mismatch result=%d db=%d fs=%d converter=%d dest=%s len=%s mode=%s row=%s context=%s limit=%s name=%s",
                            readResult,
                            databaseCalls,
                            filesystemCalls,
                            converterCalls,
                            hex(filesystemDestination),
                            hex(filesystemLength),
                            hex(filesystemMode),
                            hex(converterRow),
                            hex(converterContext),
                            hex(converterLimit),
                            nameHex
                        ));
                    }
                    println(String.format(
                        "BROWSER DIRECTORY NAME PASS read-result=%d provider-case=%s current=%d start=%d rows=%d steps=%d db-calls=%d fs-calls=%d converter-calls=%d row-stride=0xc slot=0x32 fs-length=%d fs-mode=%d converter-limit=0x%x name-bytes=%s slot-bytes=%s",
                        readResult,
                        providerCase,
                        currentIndex,
                        startIndex,
                        rowCount,
                        steps,
                        databaseCalls,
                        filesystemCalls,
                        converterCalls,
                        filesystemLength,
                        filesystemMode,
                        converterLimit,
                        nameHex,
                        slotHex
                    ));
                    return;
                }
                if (base == DB_STUB) {
                    databaseCalls++;
                    emu.writeRegister("v0", 1);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                }
                if (base == FS_STUB) {
                    filesystemCalls++;
                    long destination = emu.readRegister("a1").longValue() & 0xffffffffL;
                    if (filesystemCalls == 1) filesystemDestination = destination;
                    filesystemLength = emu.readRegister("a2").longValue() & 0xffffffffL;
                    filesystemMode = word(emu, emu.readRegister("sp").longValue() + 0x10);
                    if (readResult == 0) {
                        emu.writeMemory(
                            addr(destination),
                            providerName
                        );
                    }
                    emu.writeRegister("v0", readResult);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                }
                if (base == CONVERTER_STUB) {
                    converterCalls++;
                    if (converterCalls == 1) {
                        converterRow = emu.readRegister("a0").longValue() & 0xffffffffL;
                        converterContext = emu.readRegister("a1").longValue() & 0xffffffffL;
                        converterLimit = emu.readRegister("a2").longValue() & 0xffffffffL;
                    }
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception("directory callback stopped at " + hex(pc)
                        + ": " + emu.getLastError());
                }
            }
            throw new Exception("directory callback exceeded step limit at "
                + hex(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }
}

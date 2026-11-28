// Execute the browser list-state consumer through the real fsel dispatcher
// and directory callback. Filesystem/list services remain synthetic.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

public class RunBrowserListCountConsumerProbe extends GhidraScript {
    private static final long ENTRY = 0x40094e08L;
    private static final long DISPATCHER = 0x780db22cL;
    private static final long INDEX_HELPER = 0x780db28cL;
    private static final long DIRECTORY_CALLBACK = 0x71352820L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long STACK = RAM + 0x3000L;
    private static final long STUBS = RAM + 0x2000L;
    private static final long COUNT_QUERY = STUBS;
    private static final long PATH_HELPER = STUBS + 0x20L;
    private static final long METADATA_HELPER = STUBS + 0x40L;
    private static final long ERROR_HELPER = STUBS + 0x60L;
    private static final long UPDATE_HELPER = STUBS + 0x80L;
    private static final long ROM_HELPER = STUBS + 0xa0L;
    private static final long FILESYSTEM_HELPER = STUBS + 0x100L;
    private static final long DATABASE_HELPER = STUBS + 0x120L;
    private static final long CONVERTER_HELPER = STUBS + 0x140L;
    private static final long CURRENT = RAM + 0x1800L;
    private static final long GLOBAL = RAM + 0x1810L;
    private static final long FS_ARGUMENT = RAM + 0x1840L;
    private static final long CONTEXT = RAM + 0x1870L;
    private static final long RESULT = RAM + 0x1880L;
    private static final long FAILURE = RAM + 0x1884L;
    private static final int LIST_COUNT = 2;
    private static final int LIST_POSITION = 5;
    private static final int REQUESTED_ROW = LIST_POSITION - LIST_COUNT;

    private int countQueries;
    private int pathCalls;
    private int metadataCalls;
    private int updateCalls;
    private int romCalls;
    private int indexHelperCalls;
    private int dispatcherCalls;
    private int directoryCalls;
    private int filesystemCalls;
    private int databaseCalls;
    private int converterCalls;
    private long countQueryBuffer;
    private long indexHelperArgument;
    private long descriptor;
    private long descriptorMode;
    private long descriptorCount;
    private long descriptorStart;
    private long rowTable;
    private long directoryDescriptor;
    private long directoryTable;
    private long destination;
    private boolean sourcePointersValid = true;
    private boolean filesystemArgumentsValid = true;
    private boolean rowPointerValid = true;
    private long originalBufferPointer;
    private long originalCountPointer;
    private long originalFsTarget;
    private long originalIndexTarget;
    private long originalFselTarget;

    private Address addr(long value) {
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

    private void putHalf(EmulatorHelper emu, long address, int value) throws Exception {
        emu.writeMemory(addr(address), new byte[] {(byte)value, (byte)(value >>> 8)});
    }

    private void putByte(Memory memory, long address, int value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value});
    }

    private long word(EmulatorHelper emu, long address) throws Exception {
        byte[] b = emu.readMemory(addr(address), 4);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private long half(EmulatorHelper emu, long address) throws Exception {
        byte[] b = emu.readMemory(addr(address), 2);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8);
    }

    private long byteValue(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xffL;
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private byte[] providerName() {
        String text = "VeryLongFolderName12345";
        byte[] b = new byte[50];
        b[0] = (byte)0xff;
        b[1] = (byte)0xfe;
        for (int i = 0; i < text.length(); i++) {
            b[2 + i * 2] = (byte)text.charAt(i);
            b[3 + i * 2] = (byte)(text.charAt(i) >>> 8);
        }
        return b;
    }

    private void ramBlock(Memory memory, String name, long base, int size) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(name, addr(base),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private void patchLiteral(Memory memory, long address, long value) throws Exception {
        putWord(memory, address, value | ((value & 1L) == 0 ? 1L : 0L));
    }

    private void seed(Memory memory) throws Exception {
        ramBlock(memory, "browser_list_count_consumer_ram", RAM, 0x4000);
        ramBlock(memory, "browser_list_count_consumer_guest_ram", 0x9fc10000L, 0x30000);

        originalBufferPointer = 0x9fc15604L;
        originalCountPointer = 0x9fc155f8L;
        originalFsTarget = 0x780db201L;
        originalIndexTarget = 0x780db28dL;
        originalFselTarget = 0x780db22dL;
        byte[] source = new byte[4];
        memory.getBytes(addr(0x40094f80L), source);
        sourcePointersValid &= ((source[0] & 0xffL) | ((source[1] & 0xffL) << 8)
            | ((source[2] & 0xffL) << 16) | ((source[3] & 0xffL) << 24)) == originalBufferPointer;
        memory.getBytes(addr(0x40094f84L), source);
        sourcePointersValid &= ((source[0] & 0xffL) | ((source[1] & 0xffL) << 8)
            | ((source[2] & 0xffL) << 16) | ((source[3] & 0xffL) << 24)) == originalCountPointer;
        memory.getBytes(addr(0x40094f8cL), source);
        sourcePointersValid &= ((source[0] & 0xffL) | ((source[1] & 0xffL) << 8)
            | ((source[2] & 0xffL) << 16) | ((source[3] & 0xffL) << 24)) == originalFsTarget;
        memory.getBytes(addr(0x40094f90L), source);
        sourcePointersValid &= ((source[0] & 0xffL) | ((source[1] & 0xffL) << 8)
            | ((source[2] & 0xffL) << 16) | ((source[3] & 0xffL) << 24)) == originalIndexTarget;
        memory.getBytes(addr(0x40094fa8L), source);
        sourcePointersValid &= ((source[0] & 0xffL) | ((source[1] & 0xffL) << 8)
            | ((source[2] & 0xffL) << 16) | ((source[3] & 0xffL) << 24)) == originalFselTarget;

        putHalf(memory, originalCountPointer + 2, LIST_COUNT);
        putHalf(memory, originalBufferPointer + 0xe, LIST_POSITION);
        putWord(memory, 0x9fc157feL, 8);
        putWord(memory, 0x9fc157fcL, 8);
        putByte(memory, 0x9fc15734L, 0); // fsel type 0: directory
        putByte(memory, 0x9fc15735L, 0);

        // The initial count query is a resident service, so provide a controlled
        // zero result while leaving the real index helper and fsel dispatcher.
        patchLiteral(memory, 0x40094f8cL, COUNT_QUERY);
        patchLiteral(memory, 0x40094f9cL, PATH_HELPER);
        patchLiteral(memory, 0x40094fa4L, METADATA_HELPER);
        patchLiteral(memory, 0x40094facL, ERROR_HELPER);
        patchLiteral(memory, 0x40094fb0L, UPDATE_HELPER);
        patchLiteral(memory, 0x40094fb4L, ROM_HELPER);
        putWord(memory, 0x780db48cL, RESULT);
        putWord(memory, 0x780db490L, FS_ARGUMENT);
        patchLiteral(memory, 0x780db494L, DATABASE_HELPER);
        putWord(memory, 0x780db498L, FAILURE);
        putWord(memory, 0x71352a5cL, CURRENT);
        putWord(memory, 0x71352a60L, GLOBAL);
        putWord(memory, 0x71352a64L, FS_ARGUMENT);
        patchLiteral(memory, 0x71352a68L, FILESYSTEM_HELPER);
        putWord(memory, 0x71352a6cL, CONTEXT);
        patchLiteral(memory, 0x71352a70L, CONVERTER_HELPER);

        putWord(memory, CURRENT, 1);
        putWord(memory, GLOBAL + 4, FS_ARGUMENT);
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        seed(memory);
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", 0);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s0", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 100000; steps++) {
                long pc = reg(emu, "pc");
                long base = pc & ~1L;
                if (base == STOP) {
                    verify(emu, steps);
                    return;
                }
                if (base == COUNT_QUERY) {
                    countQueries++;
                    countQueryBuffer = reg(emu, "a2");
                    putHalf(emu, countQueryBuffer, 0);
                    returnFromStub(emu, 1);
                    continue;
                }
                if (base == PATH_HELPER) {
                    pathCalls++;
                    returnFromStub(emu, 1);
                    continue;
                }
                if (base == METADATA_HELPER) {
                    metadataCalls++;
                    returnFromStub(emu, 1);
                    continue;
                }
                if (base == ERROR_HELPER) {
                    returnFromStub(emu, 0);
                    continue;
                }
                if (base == UPDATE_HELPER) {
                    updateCalls++;
                    returnFromStub(emu, 1);
                    continue;
                }
                if (base == ROM_HELPER) {
                    romCalls++;
                    returnFromStub(emu, 0);
                    continue;
                }
                if (base == INDEX_HELPER) {
                    indexHelperCalls++;
                    indexHelperArgument = reg(emu, "a0");
                } else if (base == DISPATCHER) {
                    dispatcherCalls++;
                    descriptor = reg(emu, "a0");
                    rowTable = reg(emu, "a1");
                    descriptorMode = byteValue(emu, descriptor);
                    descriptorCount = byteValue(emu, descriptor + 1);
                    descriptorStart = half(emu, descriptor + 2);
                } else if (base == DIRECTORY_CALLBACK) {
                    directoryCalls++;
                    directoryDescriptor = reg(emu, "a0");
                    directoryTable = reg(emu, "a1");
                } else if (base == FILESYSTEM_HELPER) {
                    destination = reg(emu, "a1");
                    filesystemArgumentsValid &= reg(emu, "a2") == 25
                        && word(emu, reg(emu, "sp") + 0x10) == 2;
                    filesystemCalls++;
                    byte[] name = providerName();
                    emu.writeMemory(addr(destination), name);
                    returnFromStub(emu, 0);
                    continue;
                } else if (base == DATABASE_HELPER) {
                    databaseCalls++;
                    returnFromStub(emu, 1);
                    continue;
                } else if (base == CONVERTER_HELPER) {
                    converterCalls++;
                    returnFromStub(emu, 0);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
                }
            }
            throw new Exception("list-count consumer exceeded step limit");
        } finally {
            emu.dispose();
        }
    }

    private void returnFromStub(EmulatorHelper emu, long result) throws Exception {
        long ra = reg(emu, "ra");
        emu.writeRegister("v0", result);
        emu.writeRegister("pc", ra & ~1L);
    }

    private void verify(EmulatorHelper emu, int steps) throws Exception {
        boolean valid = sourcePointersValid && countQueries == 1 && pathCalls == 1
            && metadataCalls == 0 && updateCalls == 1 && romCalls == 1
            && countQueryBuffer != 0 && indexHelperCalls == 1
            && indexHelperArgument == REQUESTED_ROW && dispatcherCalls == 1
            && descriptorMode == 1 && descriptorCount == 1
            && descriptorStart == REQUESTED_ROW && rowTable != 0
            && directoryCalls == 1 && directoryDescriptor == descriptor
            && directoryTable == rowTable && filesystemCalls == 1
            && filesystemArgumentsValid && databaseCalls == 3 && converterCalls == 1
            && word(emu, 0x9fc157f8L) == REQUESTED_ROW;
        if (!valid) {
            throw new Exception(String.format(
                "consumer mismatch steps=%d source=%s queries=%d query-buffer=0x%08x path=%d metadata=%d update=%d rom=%d index-helper-calls=%d index-helper-arg=%d dispatch=%d desc=0x%08x mode=%d count=%d start=%d table=0x%08x directory=%d dir-desc=0x%08x dir-table=0x%08x fs=%d fs-args=%s db=%d convert=%d index=0x%08x expected-index=%d",
                steps, sourcePointersValid, countQueries, countQueryBuffer, pathCalls, metadataCalls,
                updateCalls, romCalls, indexHelperCalls, indexHelperArgument,
                dispatcherCalls, descriptor, descriptorMode,
                descriptorCount, descriptorStart, rowTable, directoryCalls, directoryDescriptor,
                directoryTable, filesystemCalls, filesystemArgumentsValid, databaseCalls,
                converterCalls, word(emu, 0x9fc157f8L), REQUESTED_ROW));
        }
        byte[] expected = providerName();
        byte[] actual = emu.readMemory(addr(0x9fc154e0L), expected.length);
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) throw new Exception("formatted name mismatch at byte " + i);
        }
        println(String.format(
            "BROWSER LIST COUNT CONSUMER PASS steps=%d state-count=%d state-position=%d requested-row=%d index-helper=0x%08x index-arg=%d fsel=0 directory-callback=0x%08x name-slot=0x9fc154e0 state-word=0x9fc157f8",
            steps, LIST_COUNT, LIST_POSITION, REQUESTED_ROW, INDEX_HELPER,
            indexHelperArgument, DIRECTORY_CALLBACK));
    }
}

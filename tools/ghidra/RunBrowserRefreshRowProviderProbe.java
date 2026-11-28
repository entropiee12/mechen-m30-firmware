// Execute the real browser refresh -> row-display callback -> in-AP name
// provider path. Filesystem and text conversion remain RAM-backed boundaries.
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

public class RunBrowserRefreshRowProviderProbe extends GhidraScript {
    private static final long ENTRY = 0x40cd5218L;
    private static final long ROW_CALLBACK = 0x48714a20L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long CONTROL = RAM + 0x100L;
    private static final long PATH = RAM + 0x200L;
    private static final long LIST_STATE = RAM + 0x400L;
    private static final long ENTRIES = RAM + 0x600L;
    private static final long NAMES = RAM + 0x1000L;
    private static final long MODE_VALUE = RAM + 0x2480L;
    private static final long FS_STATE = RAM + 0x2500L;
    private static final long FS_STUB = RAM + 0x2600L;
    private static final long CONVERT_STUB = RAM + 0x2640L;
    private static final long STACK = RAM + 0x3000L;
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

    private long word(EmulatorHelper emu, long address) throws Exception {
        byte[] b = emu.readMemory(addr(address), 4);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private void ramBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock("browser_refresh_row_provider_ram",
            addr(RAM), new ByteArrayInputStream(new byte[0x5000]), 0x5000, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
        MemoryBlock guest = memory.createInitializedBlock("browser_refresh_row_provider_guest",
            addr(0x9fc10000L), new ByteArrayInputStream(new byte[0x30000]), 0x30000, monitor, false);
        guest.setRead(true);
        guest.setWrite(true);
    }

    private byte[] utf16(String value) {
        byte[] bytes = new byte[value.length() * 2 + 2];
        for (int i = 0; i < value.length(); i++) bytes[i * 2] = (byte)value.charAt(i);
        return bytes;
    }

    private void returnFromStub(EmulatorHelper emu, long result) throws Exception {
        long ra = reg(emu, "ra");
        emu.writeRegister("v0", result);
        emu.writeRegister("pc", ra & ~1L);
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        ramBlock(memory);
        putWord(memory, CONTROL + 4, PATH);
        putHalf(memory, CONTROL + 8, 1);
        putHalf(memory, CONTROL + 10, ROWS);
        putByte(memory, CONTROL + 0x14, 3);
        putWord(memory, PATH + 8, RAM + 0x280L);
        putHalf(memory, LIST_STATE + 2, ROWS);
        for (int row = 0; row < ROWS; row++) {
            long descriptor = ENTRIES + row * 0xcL;
            long name = NAMES + row * 0x40L;
            putByte(memory, descriptor + 4, 0x32);
            putWord(memory, descriptor + 8, name);
            putBytes(memory, name, new byte[] {(byte)0xff, (byte)0xfe});
        }

        // Rewire only framework/service edges. Both refresh and row callback,
        // plus the 20-byte provider, execute from the unmodified AP image.
        putWord(memory, 0x40cd52ccL, MODE_VALUE);
        putWord(memory, 0x40cd52d0L, LIST_STATE);
        putWord(memory, 0x40cd52d4L, ENTRIES);
        putWord(memory, 0x40cd52d8L, ROW_CALLBACK | 1L);
        putWord(memory, 0x4859456cL, FS_STATE);
        putWord(memory, 0x48594570L, FS_STUB | 1L);
        putWord(memory, 0x48714a78L, CONVERT_STUB | 1L);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int rows = 0, fsCalls = 0, conversions = 0;
        long seekOffset = -1, readLength = -1, readMode = -1;
        boolean callbackAbiValid = true, providerAbiValid = true, outputValid = true;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", CONTROL);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 300000; steps++) {
                long pc = reg(emu, "pc"), base = pc & ~1L;
                if (base == STOP) {
                    for (int row = 0; row < ROWS; row++) {
                        byte[] expected = utf16("Folder" + row);
                        byte[] actual = emu.readMemory(addr(NAMES + row * 0x40L + 2), expected.length);
                        for (int i = 0; i < expected.length; i++) {
                            outputValid &= actual[i] == expected[i];
                        }
                        byte[] terminator = emu.readMemory(addr(NAMES + row * 0x40L + 2 + expected.length), 2);
                        outputValid &= terminator[0] == 0 && terminator[1] == 0;
                    }
                    if (rows != ROWS || fsCalls != ROWS * 2 || conversions != ROWS
                        || seekOffset != (ROWS - 1) * 0x14L || readLength != 0x14L
                        || readMode != 0x11L || !callbackAbiValid || !providerAbiValid || !outputValid) {
                        throw new Exception(String.format(
                            "refresh-row mismatch rows=%d fs=%d conversions=%d last-seek=0x%x len=0x%x mode=0x%x callback-abi=%s provider-abi=%s outputs=%s",
                            rows, fsCalls, conversions, seekOffset, readLength, readMode,
                            callbackAbiValid, providerAbiValid, outputValid));
                    }
                    println(String.format(
                        "BROWSER REFRESH ROW PROVIDER PASS steps=%d refresh-callbacks=%d provider-seeks=%d provider-reads=%d conversions=%d rows=0..%d last-seek=0x%x record-length=0x%x read-mode=0x%x name-slots=0x32 output=Folder0..Folder6",
                        steps, rows, rows, rows, conversions, ROWS - 1,
                        seekOffset, readLength, readMode));
                    return;
                }
                if (base == ROW_CALLBACK) {
                    int index = rows;
                    long expectedEntry = ENTRIES + index * 0xcL;
                    callbackAbiValid &= index < ROWS
                        && reg(emu, "a0") == expectedEntry
                        && reg(emu, "a1") == LIST_STATE
                        && reg(emu, "a2") == index;
                    rows++;
                } else if (base == FS_STUB) {
                    long a1 = reg(emu, "a1"), a2 = reg(emu, "a2"), a3 = reg(emu, "a3");
                    fsCalls++;
                    if ((fsCalls & 1) == 1) {
                        seekOffset = a2;
                        providerAbiValid &= a2 == ((fsCalls - 1) / 2) * 0x14L;
                    } else {
                        readLength = a2;
                        readMode = a3;
                        providerAbiValid &= readLength == 0x14L && readMode == 0x11L;
                        byte[] record = new byte[0x14];
                        for (int j = 0; j < record.length; j++) record[j] = (byte)(0xa0 + j);
                        emu.writeMemory(addr(a1), record);
                    }
                    returnFromStub(emu, 0);
                    continue;
                } else if (base == CONVERT_STUB) {
                    long recordField = reg(emu, "a0");
                    long arg1 = reg(emu, "a1");
                    long dest = word(emu, arg1);
                    long limit = reg(emu, "a3");
                    int index = conversions++;
                    providerAbiValid &= recordField == 0xa3a2L && limit == 0x1cL
                        && dest == NAMES + index * 0x40L + 2;
                    byte[] name = utf16("Folder" + index);
                    emu.writeMemory(addr(dest), name);
                    returnFromStub(emu, 0);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("refresh-row stopped at 0x%08x: %s", pc, emu.getLastError()));
                }
            }
            throw new Exception("refresh-row exceeded step limit at 0x" + Long.toHexString(reg(emu, "pc")));
        } finally {
            emu.dispose();
        }
    }

    private void putBytes(Memory memory, long address, byte[] bytes) throws Exception {
        memory.setBytes(addr(address), bytes);
    }
}

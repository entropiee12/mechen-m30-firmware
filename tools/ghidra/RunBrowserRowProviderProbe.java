// Execute the real browser row callback and its in-AP 20-byte provider.
// Only the resident file helper and text converter are RAM-stubbed, so the
// probe observes the real provider/callback argument chain without touching a
// device or writing an AP file.
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

public class RunBrowserRowProviderProbe extends GhidraScript {
    private static final long ENTRY = 0x48714a20L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long ROW = RAM + 0x100;
    private static final long CONTEXT = RAM + 0x200;
    private static final long FS_STATE = RAM + 0x400;
    private static final long NAME = RAM + 0x600;
    private static final long FS_STUB = RAM + 0x2000;
    private static final long CONVERT_STUB = RAM + 0x2040;
    private static final long STACK = RAM + 0x3000;
    private static final int ROW_INDEX = 2;

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

    private void putUtf16(Memory memory, long address, String text) throws Exception {
        byte[] bytes = new byte[text.length() * 2 + 2];
        for (int i = 0; i < text.length(); i++) {
            bytes[i * 2] = (byte)text.charAt(i);
            bytes[i * 2 + 1] = 0;
        }
        memory.setBytes(addr(address), bytes);
    }

    private long word(EmulatorHelper emu, long address) throws Exception {
        byte[] b = emu.readMemory(addr(address), 4);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
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

    private void ramBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            "browser_row_provider_probe_ram", addr(RAM),
            new ByteArrayInputStream(new byte[0x4000]), 0x4000, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        ramBlock(memory);
        putWord(memory, ROW + 8, NAME);
        putHalf(memory, CONTEXT + 4, 0);
        putUtf16(memory, NAME, "\uffff");

        // Rewire only the indirect boundaries: the row callback and provider
        // remain real AP code, while file I/O and fixed-ROM conversion are
        // observable synthetic leaves.
        putWord(memory, 0x48714a78L, CONVERT_STUB | 1L);
        putWord(memory, 0x4859456cL, FS_STATE);
        putWord(memory, 0x48594570L, FS_STUB | 1L);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int fsCalls = 0;
        long seekOffset = -1;
        long readDest = -1;
        long readLength = -1;
        long readMode = -1;
        long converterDest = -1;
        long converterLimit = -1;
        long converterA0 = -1;
        long converterA1 = -1;
        long converterA2 = -1;
        long converterA3 = -1;
        long converterA1Value = -1;
        String converterA1Bytes = "";
        String recordBytes = "";
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("s1", 0);
            emu.writeRegister("a0", ROW);
            emu.writeRegister("a1", CONTEXT);
            emu.writeRegister("a2", ROW_INDEX);
            emu.writeRegister("a3", 0);
            Register isa = currentProgram.getRegister("ISA_MODE");
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 100000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                long base = pc & ~1L;
                if (base == STOP) {
                    if (fsCalls != 2 || seekOffset != ROW_INDEX * 0x14L
                        || readLength != 0x14L || readMode != 0x11L
                        || converterA0 != 0xa3a2L || converterDest != NAME + 2
                        || converterLimit != 0x1cL) {
                        throw new Exception(String.format(
                            "provider contract mismatch fs=%d seek=%s read-dest=%s len=%s mode=%s converter-a0=%s converter-dest=%s limit=%s",
                            fsCalls, hex(seekOffset), hex(readDest), hex(readLength),
                            hex(readMode), hex(converterA0), hex(converterDest),
                            hex(converterLimit)));
                    }
                    println(String.format(
                        "BROWSER ROW PROVIDER PASS steps=%d row=%d fs-calls=%d seek-offset=%s read-dest=%s read-length=%s read-mode=%s record-field-0x02=%s converter-a1=%s converter-a2=%s converter-a3=%s converter-a1-value=%s converter-a1-bytes=%s record-bytes=%s converter-dest=%s converter-limit=%s",
                        steps, ROW_INDEX, fsCalls, hex(seekOffset), hex(readDest),
                        hex(readLength), hex(readMode), hex(converterA0), hex(converterA1),
                        hex(converterA2), hex(converterA3), hex(converterA1Value),
                        converterA1Bytes, recordBytes,
                        hex(converterDest), hex(converterLimit)));
                    return;
                }
                if (base == FS_STUB) {
                    long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                    long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                    long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;
                    fsCalls++;
                    if (fsCalls == 1) {
                        seekOffset = a2;
                    } else if (fsCalls == 2) {
                        readDest = a1;
                        readLength = a2;
                        readMode = a3;
                        // Supply a deterministic 20-byte record to the real
                        // provider's destination pointer.
                        byte[] record = new byte[0x14];
                        for (int i = 0; i < 0x14; i++) {
                            record[i] = (byte)(0xa0 + i);
                        }
                        emu.writeMemory(addr(a1), record);
                    }
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                }
                if (base == CONVERT_STUB) {
                    converterA0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                    converterA1 = a1;
                    converterA2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                    converterA3 = emu.readRegister("a3").longValue() & 0xffffffffL;
                    converterDest = word(emu, a1);
                    converterA1Value = converterDest;
                    converterLimit = converterA3;
                    converterA1Bytes = bytes(emu, converterA1, 0x14);
                    recordBytes = bytes(emu, readDest, 0x14);
                    byte[] text = new byte["FolderProvider".length() * 2 + 2];
                    for (int i = 0; i < "FolderProvider".length(); i++) {
                        text[i * 2] = (byte)"FolderProvider".charAt(i);
                    }
                    emu.writeMemory(addr(converterDest), text);
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception("row provider stopped at " + hex(pc)
                        + ": " + emu.getLastError());
                }
            }
            throw new Exception("row provider exceeded step limit at "
                + hex(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }
}

// Execute the recovered browser/music folder-list handlers in Ghidra p-code.
// The redraw/refresh helpers are intercepted at their entry points so this
// probe verifies the real state transition without claiming to render the LCD.
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

public class RunFolderNavProbe extends GhidraScript {
    private static final long NEXT = 0x487144a8L;
    private static final long PREVIOUS = 0x487146ccL;
    private static final long REDRAW = 0x487148f8L;
    private static final long RESET_PAGE = 0x487148b0L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long STATE = 0x10002000L;
    private static final long STACK = 0x10003000L;

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
            "folder_probe_ram", addr(RAM), new ByteArrayInputStream(new byte[0x4000]),
            0x4000, monitor, false);
        block.setRead(true);
        block.setWrite(true);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 1 || getScriptArgs().length > 6) {
            throw new Exception("usage: RunFolderNavProbe.java next|previous [total page start end cursor]");
        }
        String mode = getScriptArgs()[0];
        long entry;
        if ("next".equals(mode)) entry = NEXT;
        else if ("previous".equals(mode)) entry = PREVIOUS;
        else throw new Exception("mode must be next or previous");

        boolean defaultState = getScriptArgs().length == 1;
        int total = getScriptArgs().length > 1 ? Integer.decode(getScriptArgs()[1]) : 10;
        int page = getScriptArgs().length > 2 ? Integer.decode(getScriptArgs()[2]) : 7;
        int start = getScriptArgs().length > 3 ? Integer.decode(getScriptArgs()[3]) : 1;
        int end = getScriptArgs().length > 4 ? Integer.decode(getScriptArgs()[4]) : 7;
        int cursor = getScriptArgs().length > 5 ? Integer.decode(getScriptArgs()[5]) : 7;

        Memory memory = currentProgram.getMemory();
        ramBlock(memory);
        memory.setBytes(addr(STATE), new byte[0x40]);
        putByte(memory, STATE + 2, page);
        putHalf(memory, STATE + 8, start);
        putHalf(memory, STATE + 10, end);
        putHalf(memory, STATE + 12, total);
        putHalf(memory, STATE + 14, cursor);
        putHalf(memory, STATE + 16, cursor);
        putHalf(memory, STATE + 18, cursor);
        putByte(memory, STATE + 0x14, 0);
        putByte(memory, STATE + 0x15, 0);

        Register isa = currentProgram.getRegister("ISA_MODE");
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
                // MIPS16 return pointers carry ISA mode in bit 0; Ghidra's
                // context register already carries that mode for this probe.
                if ((pc & 1L) != 0) {
                    pc &= ~1L;
                    emu.writeRegister("pc", pc);
                }
                if (pc == STOP) {
                    int startIndex = half(emu, STATE + 8);
                    int endIndex = half(emu, STATE + 10);
                    int activeIndex = half(emu, STATE + 14);
                    int previous = half(emu, STATE + 18);
                    int status = one(emu, STATE + 0x14);
                    int update = one(emu, STATE + 0x15);
                    if (defaultState) {
                        boolean expected = "next".equals(mode)
                            ? startIndex == 2 && endIndex == 8 && activeIndex == 8 && previous == 7
                                && status == 1 && update == 2
                                && hits.getOrDefault(REDRAW, 0) == 1
                            : startIndex == 1 && endIndex == 7 && activeIndex == 6 && previous == 7
                                && status == 0 && update == 1
                                && hits.getOrDefault(REDRAW, 0) == 0
                                && hits.getOrDefault(RESET_PAGE, 0) == 0;
                        if (!expected) {
                            throw new Exception(String.format(
                                "default %s state mismatch: start=%d end=%d active=%d previous=%d status=%d update=%d redraw=%d reset=%d",
                                mode, startIndex, endIndex, activeIndex, previous, status, update,
                                hits.getOrDefault(REDRAW, 0), hits.getOrDefault(RESET_PAGE, 0)));
                        }
                    }
                    println(String.format(
                        "FOLDER NAV PASS mode=%s steps=%d start=%d end=%d active=%d previous=%d status=%d update=%d redraw=%d reset=%d",
                        mode, steps, startIndex, endIndex, activeIndex, previous, status, update,
                        hits.getOrDefault(REDRAW, 0), hits.getOrDefault(RESET_PAGE, 0)));
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
}

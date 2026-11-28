// Dynamically execute the resident browser-scene caller that invokes
// FUN_40cd4e00.  The GUI entry itself is intercepted at a RAM stub so the
// probe records the real argument ABI and the initialized path_info context.
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

public class RunBrowserSceneCallerProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc16600L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long STATE = RAM + 0x100;
    private static final long FSEL = RAM + 0x300;
    private static final long GUI_ENTRY_STUB = RAM + 0x2000;
    private static final long STACK = RAM + 0x3000;

    private Address addr(long value) throws Exception {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void putWord(Memory memory, long address, long value) throws Exception {
        memory.setBytes(addr(address), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private long word(EmulatorHelper emu, long address) throws Exception {
        byte[] b = emu.readMemory(addr(address), 4);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private void ramBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            "browser_scene_caller_probe_ram", addr(RAM),
            new ByteArrayInputStream(new byte[0x4000]), 0x4000, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private String hex(long value) {
        return String.format("0x%08x", value & 0xffffffffL);
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        ramBlock(memory);

        // bfc166f0 is the resident literal containing the odd MIPS16 GUI
        // entry 0x40cd4e01.  Redirect only this call; no AP/ROM bytes are
        // modified on disk.
        putWord(memory, 0xbfc166f0L, GUI_ENTRY_STUB | 1L);
        putWord(memory, 0xbfc166e8L, FSEL);
        putWord(memory, 0xbfc166ecL, 0);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
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

            for (int steps = 0; steps < 100000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                long base = pc & ~1L;
                if (base == STOP) {
                    throw new Exception("scene caller returned before GUI callback");
                }
                if (base == GUI_ENTRY_STUB) {
                    long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                    long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                    long context = word(emu, a2 + 8);
                    long initializedContext = word(emu, STATE + 16);
                    if (a0 != 0x6000L || a1 != FSEL || a2 != STATE + 8
                        || context == 0 || context != initializedContext) {
                        throw new Exception(String.format(
                            "GUI ABI mismatch a0=%s a1=%s a2=%s context=%s initialized=%s",
                            hex(a0), hex(a1), hex(a2), hex(context), hex(initializedContext)));
                    }
                    println(String.format(
                        "BROWSER SCENE CALLER PASS steps=%d gui-a0=%s fsel=%s path-info=%s context=%s state-context=%s",
                        steps, hex(a0), hex(a1), hex(a2), hex(context), hex(initializedContext)));
                    return;
                }
                if (!emu.step(monitor)) {
                    throw new Exception("scene caller stopped at " + hex(pc)
                        + ": " + emu.getLastError());
                }
            }
            throw new Exception("scene caller exceeded step limit at "
                + hex(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }
}

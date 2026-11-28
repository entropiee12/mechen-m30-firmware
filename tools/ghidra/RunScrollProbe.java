// Execute the isolated real MIPS16 scroll renderer in Ghidra's p-code emulator.
// Arguments: path to a raw MIPS16 stub binary (the .stubs section of qemu_probe.o)
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public class RunScrollProbe extends GhidraScript {
    private static final long ENTRY = 0x40d54f2cL;
    private static final long EVENT_ENTRY = 0x40d54d80L;
    private static final long STOP = 0x700300L;
    private static final long STUBS = 0x700000L;
    private static final long RAM = 0x10000000L;
    private static final long STATE = 0x10002000L;
    private static final long ENTRIES = 0x10002100L;
    private static final long CONTEXT = 0x10002200L;
    private static final long STACK = 0x10003000L;

    private Address addr(long value) throws Exception {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void putWordLE(Memory memory, long address, long value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)});
    }

    private void putHalfLE(Memory memory, long address, int value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value, (byte)(value >>> 8)});
    }

    private void expectWord(Memory memory, long address, long expected) throws Exception {
        byte[] b = new byte[4];
        memory.getBytes(addr(address), b);
        long actual = (b[0] & 0xffL) | ((b[1] & 0xffL) << 8) | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
        if (actual != (expected & 0xffffffffL)) throw new Exception(String.format("literal 0x%08x: expected 0x%08x, got 0x%08x", address, expected, actual));
    }

    private int byteAt(Memory memory, long address) throws Exception {
        byte[] b = new byte[1];
        memory.getBytes(addr(address), b);
        return b[0] & 0xff;
    }

    private int emuByteAt(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xff;
    }

    private String emuBytes(EmulatorHelper emu, long address, int length) throws Exception {
        byte[] bytes = emu.readMemory(addr(address), length);
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private int eventCode(String mode) throws Exception {
        if (mode.startsWith("render-")) mode = mode.substring("render-".length());
        if ("next".equals(mode)) return 0x2901;
        if ("previous".equals(mode)) return 0x2902;
        if ("redraw".equals(mode)) return 0x2903;
        if ("cancel".equals(mode)) return 0x2906;
        throw new Exception("mode must be cancel, next, previous, or redraw");
    }

    private void patchEventStub(Memory memory, int event) throws Exception {
        // The MIPS16 assembler emits li v0,0x29xx as these four little-endian
        // bytes in qemu_probe.S. Keep this check explicit so a future stub
        // layout change cannot silently alter the probe semantics.
        byte[] original = new byte[4];
        memory.getBytes(addr(STUBS + 0xa0), original);
        byte[] expected = new byte[] {(byte)0x05, (byte)0xf1, (byte)0x06, (byte)0x6a};
        for (int i = 0; i < expected.length; i++) {
            if (original[i] != expected[i]) throw new Exception("unexpected key-event stub encoding");
        }
        memory.setBytes(addr(STUBS + 0xa0), new byte[] {
            (byte)0x05, (byte)0xf1, (byte)(event & 0xff), (byte)0x6a
        });
    }

    private void ramBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock("probe_ram", addr(RAM), new java.io.ByteArrayInputStream(new byte[0x4000]), 0x4000, monitor, false);
        block.setRead(true);
        block.setWrite(true);
    }

    private void zeroBlock(Memory memory) throws Exception {
        MemoryBlock block = memory.createInitializedBlock("probe_zero", addr(0), new java.io.ByteArrayInputStream(new byte[0x100]), 0x100, monitor, false);
        block.setRead(true);
        block.setWrite(false);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 1 || getScriptArgs().length > 2) throw new Exception("usage: RunScrollProbe.java STUBS.BIN [cancel|next|previous|redraw|render-next|render-previous|render-redraw]");
        String mode = getScriptArgs().length == 2 ? getScriptArgs()[1] : "cancel";
        int event = eventCode(mode);
        boolean fullRenderer = "cancel".equals(mode) || mode.startsWith("render-");
        Memory memory = currentProgram.getMemory();
        byte[] stubs = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        MemoryBlock stubBlock = memory.createInitializedBlock("probe_stubs", addr(STUBS), new java.io.ByteArrayInputStream(stubs), stubs.length, monitor, false);
        stubBlock.setRead(true); stubBlock.setWrite(false); stubBlock.setExecute(true);
        patchEventStub(memory, event);
        zeroBlock(memory);
        ramBlock(memory);
        Register isa = currentProgram.getRegister("ISA_MODE");
        currentProgram.getProgramContext().setValue(isa, addr(STUBS), addr(STUBS + stubs.length - 1), BigInteger.ONE);

        memory.setBytes(addr(STATE), new byte[0x20]);
        memory.setBytes(addr(ENTRIES), new byte[0x18]);
        putHalfLE(memory, ENTRIES, 0x2005);
        putHalfLE(memory, ENTRIES + 4, 190);
        putHalfLE(memory, ENTRIES + 6, 10);
        putHalfLE(memory, ENTRIES + 8, 40);
        memory.setBytes(addr(STATE + 6), new byte[] {3, 0, 0});
        putWordLE(memory, STATE + 0x0c, ENTRIES);
        memory.setBytes(addr(CONTEXT), new byte[8]);
        putWordLE(memory, CONTEXT, STATE); putWordLE(memory, CONTEXT + 4, 3);

        long[] literals = {0x40d5516cL,0x40d55170L,0x40d55174L,0x40d55178L,0x40d5517cL,0x40d55180L,0x40d55198L,0x40d551b0L,0x40d54f10L,0x40d54f18L,0x40d54f1cL,0x40d54f24L,0x40d54f28L};
        long[] expected = {0xbfc16405L,0x48454841L,0x9fc1592cL,0x484547adL,0xbfc16401L,0xbfc16bb1L,0x9fc1a294L,0x9fc1a294L,0xbfc16ae9L,0x485547edL,0x48bd4601L,0xbfc16b55L,0x9fc1a28cL};
        long[] targets = {0x700001L,0x700021L,0x10002200L,0x700041L,0x700041L,0x700061L,0x10002200L,0x10002200L,0x700081L,0x7000a1L,0x7000c1L,0x7000e1L,0x10002300L};
        for (int i = 0; i < literals.length; i++) { expectWord(memory, literals[i], expected[i]); putWordLE(memory, literals[i], targets[i]); }

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            // The MIPS16 bank uses JXWritePC/setISAMode for odd function
            // pointers. All probe targets are MIPS16, so keeping the current
            // context is the correct behavior for this isolated path.
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("sp", STACK); emu.writeRegister("ra", STOP | 1); emu.writeRegister("s1", 0);
            emu.writeRegister("a0", fullRenderer ? 0 : CONTEXT);
            emu.writeRegister("a1", fullRenderer ? CONTEXT : 0x10002300L);
            emu.setContextRegister(isa, BigInteger.ONE); emu.writeRegister("pc", fullRenderer ? ENTRY : EVENT_ENTRY);
            Map<Long,Integer> hits = new LinkedHashMap<>();
            for (int steps = 0; steps < 200000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                if (pc == STOP) {
                    if (!fullRenderer) {
                        int active = emuByteAt(emu, STATE + 7);
                        int previous = emuByteAt(emu, STATE + 8);
                        int expectedActive = "next".equals(mode) ? 1 : ("previous".equals(mode) ? 2 : 0);
                        int expectedStatus = "redraw".equals(mode) ? 1 : 2;
                        int expectedY = "redraw".equals(mode) ? 50 : 40;
                        int actualY = (emuByteAt(emu, ENTRIES + 8) | (emuByteAt(emu, ENTRIES + 9) << 8));
                        if (active != expectedActive || previous != 0 || emuByteAt(emu, CONTEXT + 4) != expectedStatus || actualY != expectedY) {
                            for (Map.Entry<Long,Integer> hit : hits.entrySet()) println(String.format("stub 0x%08x hits %d", hit.getKey(), hit.getValue()));
                            byte[] eventBytes = emu.readMemory(addr(STACK - 0x28), 2);
                            int observedEvent = (eventBytes[0] & 0xff) | ((eventBytes[1] & 0xff) << 8);
                            println(String.format("observed event=0x%04x", observedEvent));
                            throw new Exception(String.format("%s state mismatch: active=%d previous=%d status=%d y=%d", mode, active, previous, emuByteAt(emu, CONTEXT + 4), actualY));
                        }
                        println(String.format("SCROLL EVENT PASS mode=%s steps=%d active=%d previous=%d status=%d y=%d", mode, steps, active, previous, emuByteAt(emu, CONTEXT + 4), actualY));
                    }
                    else {
                        println("SCROLL PROBE PASS steps=" + steps + " v0=" + emu.readRegister("v0"));
                    }
                    for (Map.Entry<Long,Integer> hit : hits.entrySet()) println(String.format("stub 0x%08x hits %d", hit.getKey(), hit.getValue())); return;
                }
                if (pc >= STUBS && pc < STUBS + stubs.length) {
                    long base = pc & ~1L;
                    hits.put(base, hits.containsKey(base) ? hits.get(base)+1 : 1);
                    if (fullRenderer && base == STUBS) {
                        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                        long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                        long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                        long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;
                        println(String.format("DISPATCH_TRACE a0=0x%08x a1=0x%08x a2=0x%08x a3=0x%08x",
                            a0, a1, a2, a3));
                        if (a0 >= RAM && a0 < RAM + 0x4000) {
                            String second = a1 >= RAM && a1 < RAM + 0x4000 ? emuBytes(emu, a1, 16) : "scalar";
                            println(String.format("DISPATCH_BYTES a0=%s a1=%s", emuBytes(emu, a0, 16), second));
                        }
                    }
                    if (base == STUBS + 0x60 && mode.startsWith("render-")) {
                        long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                        long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                        long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                        long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;
                        if (a0 != 1 || a1 != 0 || a2 != 0 || a3 != 0x31) {
                            throw new Exception(String.format("unexpected timer ABI: a0=0x%08x a1=0x%08x a2=0x%08x a3=0x%08x", a0, a1, a2, a3));
                        }
                        println(String.format("SCROLL TIMER ABI PASS event=%s steps=%d a0=0x%08x a1=0x%08x a2=0x%08x a3=0x%08x", mode, steps, a0, a1, a2, a3));
                        return;
                    }
                    if (!fullRenderer && base == STUBS + 0xa0) println(String.format("event stub a1=0x%08x", emu.readRegister("a1").longValue() & 0xffffffffL));
                    if (!fullRenderer && base == STUBS + 0xa4) println(String.format("event stub v0=0x%08x a1=0x%08x", emu.readRegister("v0").longValue() & 0xffffffffL, emu.readRegister("a1").longValue() & 0xffffffffL));
                }
                if (!emu.step(monitor)) throw new Exception("emulator stopped: " + emu.getLastError());
            }
            throw new Exception("step limit reached at PC 0x" + Long.toHexString(emu.readRegister("pc").longValue()) + ": " + emu.getLastError());
        } finally { emu.dispose(); }
    }
}

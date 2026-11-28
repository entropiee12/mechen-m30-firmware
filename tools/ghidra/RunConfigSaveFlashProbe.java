import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * config.ap: item 0x96 -> state+0x18 -> DAE +2/+3 -> persistent save (FUN_40054f38).
 * Arguments: CONFIG.BIN OUTPUT_DIR.  Synthetic: config item getter/reader, fill/copy stub,
 * kernel stub (alloc 0x16, shared-memory create 0x47) and the persistent write stub, which
 * records each (id, bytes) into OUTPUT_DIR/case<N>/id_<hex>.bin for the MEngine probe.
 * The 0x55b9 DAE magic is set by the probe, standing in for FUN_40054ccc.
 */
public class RunConfigSaveFlashProbe extends GhidraScript {
    static final long STOP = 0x700300L, RAM = 0x9fc15000L; static final int RAM_SIZE = 0x6000;
    static final long STATE = 0x9fc15654L, DAE = 0x9fc15700L, ALLOC = 0x9fc1a000L, STACK = 0x9fc1af00L;
    static final long GETTER = 0x485146bcL, READER = 0x4851464cL, KCALL = 0xbfc16584L, COPY = 0xbfc16580L;
    byte[] cfg; Path outDir;
    Address addr(long v) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(v); }
    long reg(EmulatorHelper e, String n) throws Exception { return e.readRegister(n).longValue() & 0xffffffffL; }
    String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x & 255)); return s.toString(); }
    byte[] item(int id) {
        ByteBuffer bb = ByteBuffer.wrap(cfg).order(ByteOrder.LITTLE_ENDIAN);
        int off = bb.getInt(0x10 + id * 8), len = bb.getInt(0x10 + id * 8 + 4);
        return Arrays.copyOfRange(cfg, off, off + len);
    }
    int u16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    Map<Long, byte[]> writes = new LinkedHashMap<>();
    int override;

    void call(EmulatorHelper e, long fn, long a0, long a1) throws Exception {
        e.writeRegister("sp", STACK); e.writeRegister("s0", 0); e.writeRegister("s1", 0);
        e.writeRegister("ra", STOP | 1L);
        e.writeRegister("a0", a0); e.writeRegister("a1", a1); e.writeRegister("a2", 0); e.writeRegister("a3", 0);
        e.writeRegister("pc", fn);
        for (int step = 0; step < 400000; step++) {
            long pc = reg(e, "pc") & ~1L;
            if (pc == STOP) return;
            long ra = reg(e, "ra");
            if (pc == GETTER) {
                long id = reg(e, "a0") & 0xffff;
                long v = id == 0x96 ? override : u16(item((int) id), 0);
                e.writeRegister("v0", v); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (pc == READER) {
                long id = reg(e, "a0") & 0xffff, buf = reg(e, "a1"), len = reg(e, "a2");
                e.writeMemory(addr(buf), Arrays.copyOf(item((int) id), (int) len));
                e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (pc == COPY) {
                long d = reg(e, "a0"), s = reg(e, "a1"), n = reg(e, "a2"), f = reg(e, "a3");
                if (f == 1) { byte[] b = new byte[(int) n]; Arrays.fill(b, (byte) s); e.writeMemory(addr(d), b); }
                else e.writeMemory(addr(d), e.readMemory(addr(s), (int) n));
                e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (pc == KCALL) {
                long k0 = reg(e, "a0"), k1 = reg(e, "a1"), k2 = reg(e, "a2"), k3 = reg(e, "a3");
                long v0 = 0;
                if (k3 == 0x16) v0 = ALLOC;
                if (k3 == 0x47) v0 = 0x1234;
                if (k3 == 0x13) { writes.put(k1, e.readMemory(addr(k0), (int) k2)); println(String.format("  persist WRITE id=0x%x len=0x%x", k1, k2)); if (k1 == 0xf0000L) { println("  (stop after 2nd write: FUN_40054f38 then makes 2 more unmodeled calls via pool cells)"); return; } }
                else if (k3 == 0x12) throw new Exception("unexpected persistent read in phase A");
                else println(String.format("  kcall(a0=%x,a1=%08x,a2=%x,op=0x%x) -> %x", k0, k1, k2, k3, v0));
                e.writeRegister("v0", v0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (!e.step(monitor)) throw new Exception(String.format("stopped at %08x: %s", pc, e.getLastError()));
        }
        throw new Exception("step limit");
    }

    void scenario(int v96) throws Exception {
        Memory m = currentProgram.getMemory();
        m.setBytes(addr(RAM), new byte[RAM_SIZE]);
        writes.clear(); override = v96;
        EmulatorHelper e = new EmulatorHelper(currentProgram);
        try {
            e.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            println("=== PHASE A item 0x96 = " + v96);
            call(e, 0x40dd4c00L, STATE, 0);
            println("  state after 4c00: " + hex(e.readMemory(addr(STATE), 0x38)));
            call(e, 0x40dd4d60L, DAE, 0);
            println("  dae after 4d60 (before magic fix): " + hex(e.readMemory(addr(DAE), 0xf0)));
            byte[] d0 = e.readMemory(addr(DAE), 2);
            if (u16(d0, 0) != 0x55b9) { e.writeMemory(addr(DAE), new byte[] {(byte) 0xb9, 0x55}); println("  magic 0x55b9 NOT set by 4d60 -> set by probe (synthetic)"); }
            else println("  magic 0x55b9 already present after 4d60");
            call(e, 0x40dd4fe8L, STATE, DAE);
            byte[] dae = e.readMemory(addr(DAE), 0xf0), st = e.readMemory(addr(STATE), 0x38);
            println(String.format("  AFTER 4fe8: dae+2=%02x dae+3=%02x dae+7=%02x dae+0xc=%02x dae+0xd=%02x ; state+0x18=%02x state+0x19=%02x", dae[2], dae[3], dae[7], dae[0xc], dae[0xd], st[0x18], st[0x19]));
            call(e, 0x40054f38L, 0, 0);
            checkCase(v96, dae, st);
            Path d = outDir.resolve("case" + v96);
            Files.createDirectories(d);
            for (Map.Entry<Long, byte[]> w : writes.entrySet()) {
                Files.write(d.resolve(String.format("id_%x.bin", w.getKey())), w.getValue());
                byte[] b = w.getValue();
                println(String.format("  stored id=0x%x len=0x%x: %s", w.getKey(), b.length, b.length == 0x38 ? "state+0x18=" + b[0x18] + " +0x19=" + b[0x19] : "dae+2=" + b[2] + " +3=" + b[3]));
            }
        } finally { e.dispose(); }
    }

    void checkCase(int v96, byte[] dae, byte[] state) throws Exception {
        int expectPlusTwo = v96 == 1 ? 1 : 0;
        if (state[0x18] != v96 || dae[2] != expectPlusTwo || dae[3] != 1 - expectPlusTwo)
            throw new Exception(String.format("unexpected config result for item 0x96=%d: state+0x18=%d dae+2/+3=%d/%d",
                v96, state[0x18], dae[2], dae[3]));
        if (!writes.containsKey(0L) || !writes.containsKey(0xf0000L))
            throw new Exception("FUN_40054f38 did not write ids 0 and 0xf0000");
        byte[] saved = writes.get(0xf0000L);
        if (saved.length != 0xf0 || saved[2] != dae[2] || saved[3] != dae[3] || writes.get(0L)[0x18] != v96)
            throw new Exception("saved blocks do not carry the patched +2/+3 and state+0x18");
    }

    @Override public void run() throws Exception {
        cfg = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        outDir = Paths.get(getScriptArgs()[1]);
        currentProgram.getMemory().createInitializedBlock("cfg_ram", addr(RAM), new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false).setWrite(true);
        for (int v : new int[] {0, 1, 2}) scenario(v);
        println("CONFIG SAVE FLASH P-CODE PASS item96=0,1,2 saved=id0,id0xf0000 synthetic=getter,reader,copy,kcall,write-stub");
    }
}

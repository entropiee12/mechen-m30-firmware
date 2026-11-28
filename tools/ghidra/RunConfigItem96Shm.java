import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.*;
import java.util.*;

/** config.ap: item 0x96 -> state+0x18 (FUN_40dd4c00) -> dae_cfg+2/+3 (FUN_40dd4fe8) -> SHM create (FUN_40dd5034). */
public class RunConfigItem96Shm extends GhidraScript {
    static final long STOP = 0x700300L;
    static final long RAM = 0x9fc15000L; static final int RAM_SIZE = 0x6000;
    static final long STATE = 0x9fc15654L, DAE = 0x9fc15700L, ALLOC = 0x9fc1a000L, STACK = 0x9fc1af00L;
    static final long GETTER = 0x485146bcL, READER = 0x4851464cL, KCALL = 0xbfc16584L, COPY = 0xbfc16580L;
    byte[] cfg;
    Address addr(long v) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(v); }
    long reg(EmulatorHelper e, String n) throws Exception { return e.readRegister(n).longValue() & 0xffffffffL; }
    String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x & 255)); return s.toString(); }
    byte[] item(int id) {
        ByteBuffer bb = ByteBuffer.wrap(cfg).order(ByteOrder.LITTLE_ENDIAN);
        int off = bb.getInt(0x10 + id * 8), len = bb.getInt(0x10 + id * 8 + 4);
        return Arrays.copyOfRange(cfg, off, off + len);
    }
    int u16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    List<String> log = new ArrayList<>();
    byte[] shmSnapshot;

    void call(EmulatorHelper e, long fn, long a0, long a1, String label, int override) throws Exception {
        e.writeRegister("sp", STACK);
        e.writeRegister("s0", 0);
        e.writeRegister("s1", 0);
        e.writeRegister("ra", STOP | 1L);
        e.writeRegister("a0", a0); e.writeRegister("a1", a1); e.writeRegister("a2", 0); e.writeRegister("a3", 0);
        e.writeRegister("pc", fn);
        for (int step = 0; step < 200000; step++) {
            long pc = reg(e, "pc") & ~1L;
            if (pc == STOP) return;
            long ra = reg(e, "ra");
            if (pc == GETTER) {
                long id = reg(e, "a0") & 0xffff;
                long v = id == 0x96 ? override : u16(item((int) id), 0);
                log.add(String.format("getter(id=0x%x) -> %d%s", id, v, id == 0x96 ? "  [item 0x96 override]" : ""));
                e.writeRegister("v0", v); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (pc == READER) {
                long id = reg(e, "a0") & 0xffff, buf = reg(e, "a1"), len = reg(e, "a2");
                byte[] it = Arrays.copyOf(item((int) id), (int) len);
                e.writeMemory(addr(buf), it);
                e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (pc == COPY) {
                long d = reg(e, "a0"), s = reg(e, "a1"), n = reg(e, "a2"), f = reg(e, "a3");
                e.writeMemory(addr(d), e.readMemory(addr(s), (int) n));
                if (d >= ALLOC) log.add(String.format("copy(dst=%08x,src=%08x,len=0x%x,flag=%d) [synthetic memcpy]", d, s, n, f));
                e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (pc == KCALL) {
                long k0 = reg(e, "a0"), k1 = reg(e, "a1"), k2 = reg(e, "a2"), k3 = reg(e, "a3");
                long v0 = 0;
                String tag = "";
                if (k3 == 0x16) { v0 = ALLOC; tag = " alloc"; }
                if (k3 == 0x47) {
                    v0 = 0x1234; tag = " SHM-create";
                    shmSnapshot = e.readMemory(addr(k1), (int) k2);
                }
                if (k3 == 0x4b) tag = " PANIC-STRING";
                log.add(String.format("kcall(a0=%x,a1=%08x,a2=%x,op=0x%x)%s -> %x", k0, k1, k2, k3, tag, v0));
                e.writeRegister("v0", v0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (!e.step(monitor)) throw new Exception(String.format("stopped at %08x: %s", pc, e.getLastError()));
        }
        throw new Exception("step limit " + label);
    }

    void scenario(int v96) throws Exception {
        Memory m = currentProgram.getMemory();
        m.setBytes(addr(RAM), new byte[RAM_SIZE]);
        log.clear(); shmSnapshot = null;
        EmulatorHelper e = new EmulatorHelper(currentProgram);
        try {
            e.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            println("=== config item 0x96 = " + v96);
            call(e, 0x40dd4c00L, STATE, 0, "defaults FUN_40dd4c00", v96);
            int stateFlag = e.readMemory(addr(STATE + 0x18), 1)[0] & 255;
            require(stateFlag == v96, "config item 0x96 did not reach state+0x18");
            println(String.format("  state+0x18 = %d   (state=0x%08x, bytes +0x10..+0x1b = %s)", stateFlag, STATE, hex(e.readMemory(addr(STATE + 0x10), 12))));
            call(e, 0x40dd4d60L, DAE, 0, "dae init FUN_40dd4d60", v96);
            println(String.format("  dae before: +2=%02x +3=%02x +7=%02x +0xc=%02x +0xd=%02x", e.readMemory(addr(DAE + 2), 1)[0], e.readMemory(addr(DAE + 3), 1)[0], e.readMemory(addr(DAE + 7), 1)[0], e.readMemory(addr(DAE + 0xc), 1)[0], e.readMemory(addr(DAE + 0xd), 1)[0]));
            int before = log.size();
            call(e, 0x40dd4fe8L, STATE, DAE, "FUN_40dd4fe8(state,dae)", v96);
            byte[] dae = e.readMemory(addr(DAE), 0x10);
            int expectedDae2 = v96 == 1 ? 1 : 0;
            int expectedDae3 = v96 == 1 ? 0 : 1;
            require((dae[2] & 255) == expectedDae2 && (dae[3] & 255) == expectedDae3,
                "state+0x18 did not select dae_cfg +2/+3");
            require(shmSnapshot != null && shmSnapshot.length == 0xf0,
                "shared-memory create call did not receive the complete DAE image");
            require((shmSnapshot[2] & 255) == expectedDae2
                    && (shmSnapshot[3] & 255) == expectedDae3,
                "SHM create image did not preserve the selected +2/+3 flags");
            println(String.format("  dae after : +2=%02x +3=%02x +4=%02x +5=%02x (first16=%s)", dae[2], dae[3], dae[4], dae[5], hex(dae)));
            for (int i = before; i < log.size(); i++) println("  CALL " + log.get(i));
            if (shmSnapshot == null) println("  SHM create NOT reached");
            else println(String.format("  SHM image at create (op 0x47, id=3): +2=%02x +3=%02x  len=0x%x  first16=%s", shmSnapshot[2], shmSnapshot[3], shmSnapshot.length, hex(Arrays.copyOf(shmSnapshot, 16))));
            println("  getter log before 4fe8 (item reads): " + before + " entries");
        } finally { e.dispose(); }
    }

    @Override public void run() throws Exception {
        cfg = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(getScriptArgs()[0]));
        Memory m = currentProgram.getMemory();
        m.createInitializedBlock("cfg_ram", addr(RAM), new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false).setWrite(true);
        println("config.bin item 0x96 payload = " + hex(item(0x96)) + "  (u16 default=" + u16(item(0x96), 0) + ")");
        for (int v : new int[] {0, 1, 2}) scenario(v);
        println("CONFIG ITEM96 SHM P-CODE PASS cases=0,1,2 config-bin-sha256=900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19 synthetic-services=getter,copy,kcall-shm-create shared-memory-identity=unverified");
    }
}

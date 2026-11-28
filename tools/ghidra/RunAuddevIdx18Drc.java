import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.Arrays;

/** aud_dev bank-5 table[18] (FUN_9400, a0=1) at several sample rates; module-0x10 copy and MMIO synthetic. */
public class RunAuddevIdx18Drc extends GhidraScript {
    static final long ENTRY = 0x9400L, STOP = 0x700300L, COPY_STUB = 0x10003000L;
    static final long RAM = 0x10000000L, SOURCE = RAM + 0x1000, STATE = 0x9fc34000L, STACK = RAM + 0x4000;
    Memory memory;
    Address addr(long v) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(v); }
    long reg(EmulatorHelper e, String n) throws Exception { return e.readRegister(n).longValue() & 0xffffffffL; }
    String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x & 255)); return s.toString(); }
    byte[] le(long v, int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte)(v >>> (8 * i)); return b; }
    void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    byte[] snap(EmulatorHelper e, long off, int n) throws Exception {
        return e == null ? read(off, n) : e.readMemory(addr(STATE + off), n);
    }
    byte[] read(long off, int n) throws Exception { byte[] b = new byte[n]; memory.getBytes(addr(STATE + off), b); return b; }

    long u16(EmulatorHelper e, long off) throws Exception { byte[] b = e.readMemory(addr(STATE + off), 2); return (b[0] & 255) | ((b[1] & 255) << 8); }
    String fields(EmulatorHelper e) throws Exception {
        return String.format("rate+0x00=%s  +0x10=%s  +0x28=%s  eqflag+0x3a=%s  +0x40=%s",
            hex(snap(e, 0, 4)), hex(snap(e, 0x10, 4)), hex(snap(e, 0x28, 4)), hex(snap(e, 0x3a, 2)), hex(snap(e, 0x40, 2)));
    }

    void scenario(String name, long rate, int drc, byte[] baseInput) throws Exception {
        byte[] input = baseInput.clone();
        input[0x0c] = (byte) drc;
        boolean prior = true;
        // reset state block (program memory) then seed
        memory.setBytes(addr(STATE), new byte[0x200]);
        memory.setBytes(addr(STATE), le(rate, 4));                 // synthetic seed: what op0/op2 store at cfg+0
        if (prior) {
            memory.setBytes(addr(STATE + 0x10), le(0x11223344L, 4));
            memory.setBytes(addr(STATE + 0x40), le(1, 2));  // +0x3a stays 0, +0x28 stays 0
        }
        memory.setBytes(addr(SOURCE), input);
        String before = fields(null);
        byte[] bandsBefore = read(0x94, 0x80);
        EmulatorHelper e = new EmulatorHelper(currentProgram);
        int copies = 0;
        try {
            e.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            e.writeRegister("sp", STACK);
            e.writeRegister("ra", STOP | 1L);
            e.writeRegister("a0", 1);
            e.writeRegister("a1", SOURCE);
            e.writeRegister("a2", 0);
            e.writeRegister("a3", 0);
            e.writeRegister("pc", ENTRY);
            boolean done = false;
            java.util.TreeSet<Long> seenCompare = new java.util.TreeSet<>();
            java.util.Set<Long> visit = java.util.Set.of(0x954eL,0x955cL,0x955fL,0x96e8L,0x96ecL,0x961aL,0x962aL,0x9640L);
            for (int step = 0; step < 100000; step++) {
                long pc = reg(e, "pc") & ~1L;
                if (pc >= 0x9400 && pc < 0x9a00) e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
                if (pc == 0x9556L || pc == 0x9620L) {   // the two raw `slt` compares
                    seenCompare.add(pc);
                    long cmp = pc == 0x9556L ? reg(e, "v1") : reg(e, "a2");
                    long other = pc == 0x9556L ? reg(e, "a2") : reg(e, "v0");
                    println(String.format("  COMPARE pc=0x%04x slt: %d(li 48000) < %d(cfg+0) -> %s", pc, cmp, other, cmp < other ? "T(set)" : "F"));
                }
                if (pc == STOP) { done = true; break; }
                if (pc == COPY_STUB) {
                    long a0 = reg(e, "a0"), a1 = reg(e, "a1"), a2 = reg(e, "a2"), a3 = reg(e, "a3"), ra = reg(e, "ra");
                    if (a2 != 8 || a3 != 0) throw new Exception("unexpected copy request");
                    e.writeMemory(addr(a0), e.readMemory(addr(a1), 8));
                    copies++;
                    e.writeRegister("v0", 0);
                    e.writeRegister("pc", ra & ~1L);
                    continue;
                }
                long w40 = u16(e, 0x40), w3a = u16(e, 0x3a), w28 = u16(e, 0x28);
                if (!e.step(monitor)) throw new Exception(String.format("stopped at 0x%08x: %s", pc, e.getLastError()));
                long n40 = u16(e, 0x40), n3a = u16(e, 0x3a), n28 = u16(e, 0x28);
                if (n40 != w40) println(String.format("  WRITE +0x40 %d->%d by insn at 0x%04x", w40, n40, pc));
                if (n3a != w3a) println(String.format("  WRITE +0x3a %d->%d by insn at 0x%04x", w3a, n3a, pc));
                if (n28 != w28) println(String.format("  WRITE +0x28 %d->%d by insn at 0x%04x", w28, n28, pc));
                if (visit.contains(pc)) println(String.format("  BRANCH-POINT reached 0x%04x", pc));
            }
            if (!done) throw new Exception("step limit");
            long v0 = reg(e, "v0");
            byte[] bandsAfter = snap(e, 0x94, 0x80);
            byte[] src = Arrays.copyOfRange(input, 0x10, 0x90);
            require(v0 == 0, "aud_dev op 1 returned an error");
            require(copies == (drc == 1 ? 19 : 16), "unexpected number of copy calls");
            require(Arrays.equals(bandsAfter, src), "aud_dev copied bands differ from input");
            int expectedRateFlag = rate <= 48000 ? 1 : 0;
            require(u16(e, 0x3a) == expectedRateFlag, "EQ flag differs from rate boundary");
            require(u16(e, 0x28) == expectedRateFlag, "cfg+0x28 differs from rate boundary");
            int expectedDrcFlag = drc == 1 && rate <= 48000 ? 1 : 0;
            require(u16(e, 0x40) == expectedDrcFlag, "DRC flag differs from rate and input controls");
            println(String.format("RUN %s rate=%d prior=%s ret=0x%08x copies=%d", name, rate, "+0xc=" + drc + ", start +0x40=1,+0x3a=0,+0x28=0,+0x10=11223344", v0, copies));
            println("  BEFORE " + before);
            println("  AFTER  " + fields(e));
            println("  BANDS16 before=" + (Arrays.equals(bandsBefore, new byte[0x80]) ? "all-zero" : hex(bandsBefore)));
            println("  BANDS16 after =" + hex(bandsAfter));
            println("  BANDS16 equal-to-input(+0x10..+0x8f)=" + Arrays.equals(bandsAfter, src));
        } finally { e.dispose(); }
    }

    @Override public void run() throws Exception {
        byte[] driver = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(getScriptArgs()[0]));
        byte[] input = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(getScriptArgs()[1]));
        memory = currentProgram.getMemory();
        byte[] mapped = new byte[0x600];
        memory.getBytes(addr(ENTRY), mapped);
        if (!Arrays.equals(mapped, Arrays.copyOfRange(driver, 0x1f00, 0x2500))) throw new Exception("bank-5 mismatch");
        memory.getBlock(addr(ENTRY)).setWrite(true);
        memory.setBytes(addr(0x98fc), le(COPY_STUB | 1L, 4));   // synthetic ROM copy service
        memory.createInitializedBlock("ram", addr(RAM), new ByteArrayInputStream(new byte[0x5000]), 0x5000, monitor, false).setWrite(true);
        memory.createInitializedBlock("state", addr(STATE), new ByteArrayInputStream(new byte[0x200]), 0x200, monitor, false).setWrite(true);
        long[] rates = {48000, 48001, 96000};
        for (int drc : new int[] {1, 0}) for (long r : rates) scenario("drc" + drc + "-r" + r, r, drc, input);
        println("AUDDEV IDX18 DRC P-CODE PASS cases=6 rates=48000,48001,96000 drc=0,1 synthetic-copy=1");
    }
}

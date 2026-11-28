import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/**
 * mengine.ap: FUN_60050d00 reads the blocks config.ap saved, then builder FUN_601d0d58 runs.
 * Arguments: FLASH_DIR [sentinel] (FLASH_DIR holds case0..2/id_*.bin from RunConfigSaveFlashProbe).
 * Synthetic: persistent helper stub 0xbfc11cc8 (op 0x12 read from files / 0x13 write / 0x49 return shared ptr),
 * fill/copy stub 0xbfc11cc0, command stub 0xbfc11cc4, every other unmapped pc returns v0=0.
 */
public class RunMengineFlashBuilderProbe extends GhidraScript {
    static final long STOP = 0x700300L, RAM = 0x9fc15000L; static final int RAM_SIZE = 0x6000;
    static final long STACK = 0x9fc19000L, SHARED = 0x9fc1a400L;
    static final long SETTINGS = 0x9fc15d9cL, LOCAL = 0x9fc15dd4L, SELECTOR = 0x9fc15cc9L, SHM_CELL = 0x9fc15ee4L;
    static final long HELPER = 0xbfc11cc8L, FILLCOPY = 0xbfc11cc0L, CMD = 0xbfc11cc4L;
    Path flash; Map<Long, byte[]> store = new HashMap<>(); Set<Long> missed = new TreeSet<>();
    int cmd1f; List<String> unk = new ArrayList<>(); boolean shmSwap = false; long shmPtr;

    Address addr(long v) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(v); }
    long reg(EmulatorHelper e, String n) throws Exception { return e.readRegister(n).longValue() & 0xffffffffL; }
    String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x & 255)); return s.toString(); }
    int b(EmulatorHelper e, long a) throws Exception { return e.readMemory(addr(a), 1)[0] & 255; }

    void run(EmulatorHelper e, long fn, String label) throws Exception {
        e.writeRegister("sp", STACK); e.writeRegister("s0", 0); e.writeRegister("s1", 0);
        e.writeRegister("ra", STOP | 1L);
        for (String r : new String[] {"a0", "a1", "a2", "a3"}) e.writeRegister(r, 0);
        e.writeRegister("pc", fn);
        Memory mem = currentProgram.getMemory();
        for (int step = 0; step < 3000000; step++) {
            long pc = reg(e, "pc") & ~1L;
            if (pc == STOP) { println("  " + label + " returned after " + step + " steps"); return; }
            long ra = reg(e, "ra");
            long a0 = reg(e, "a0"), a1 = reg(e, "a1"), a2 = reg(e, "a2"), a3 = reg(e, "a3");
            if (pc == FILLCOPY) {
                if (a3 == 1) { byte[] f = new byte[(int) a2]; Arrays.fill(f, (byte) a1); e.writeMemory(addr(a0), f); }
                else e.writeMemory(addr(a0), e.readMemory(addr(a1), (int) a2));
                e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (pc == HELPER) {
                long v0 = 0;
                if (a3 == 0x12) {
                    byte[] d = store.get(a1);
                    if (d == null) { missed.add(a1); v0 = 1; println(String.format("  helper READ id=0x%x len=0x%x -> NO FILE, returns 1 (buf untouched)", a1, a2)); }
                    else { e.writeMemory(addr(a0), Arrays.copyOf(d, (int) a2)); println(String.format("  helper READ id=0x%x len=0x%x -> file ok, returns 0", a1, a2)); }
                } else if (a3 == 0x13) {
                    store.put(a1, e.readMemory(addr(a0), (int) a2)); println(String.format("  helper WRITE id=0x%x len=0x%x", a1, a2));
                } else if (a3 == 0x49) { v0 = shmPtr; println(String.format("  helper op 0x49(a0=%x) -> %08x", a0, v0)); }
                else println(String.format("  helper op 0x%x a0=%x a1=%x a2=%x -> 0 (synthetic default)", a3, a0, a1, a2));
                e.writeRegister("v0", v0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (pc == CMD) {
                if (a1 == 0x1f) cmd1f++;
                println(String.format("  mmm_mp cmd stub called handle=%08x cmd=0x%x arg=%x", a0, a1, a2));
                e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (!mem.contains(addr(pc))) {
                unk.add(String.format("%08x(a0=%x,a1=%x,a2=%x,a3=%x)", pc, a0, a1, a2, a3));
                println(String.format("  unmapped/external pc=%08x a0=%x a1=%x a2=%x a3=%x -> v0=0 (synthetic)", pc, a0, a1, a2, a3));
                e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue;
            }
            if (!e.step(monitor)) throw new Exception(String.format("%s stopped at %08x: %s", label, pc, e.getLastError()));
        }
        throw new Exception("step limit " + label);
    }

    void checkRow(int item, boolean whatIf, byte[] local, byte[] daeA, byte[] shared, int command) throws Exception {
        if (local[2] != daeA[2] || local[3] != daeA[3])
            throw new Exception("MEngine local +2/+3 differ from the saved DAE block");
        boolean sentinel = getScriptArgs().length > 1;
        boolean ran = !whatIf && item != 0;
        int expectedCommand = ran ? 1 : 0;
        if (command != expectedCommand)
            throw new Exception(String.format("item %d whatIf=%s: command 0x1f calls %d, expected %d", item, whatIf, command, expectedCommand));
        if (ran) {
            if (shared[7] != 1 || shared[2] != local[2] || shared[3] != local[3])
                throw new Exception("builder did not copy the local structure to shared memory");
        } else {
            byte expected = sentinel ? (byte) 0xee : daeA[2];
            if (shared[2] != expected)
                throw new Exception("skipped builder changed shared memory");
        }
    }

    @Override public void run() throws Exception {
        flash = Paths.get(getScriptArgs()[0]);
        Memory m = currentProgram.getMemory();
        m.createInitializedBlock("eng_ram", addr(RAM), new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false).setWrite(true);
        List<String> table = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            store.clear(); missed.clear();
            for (long id : new long[] {0, 0xf0000L}) store.put(id, Files.readAllBytes(flash.resolve("case" + c).resolve(String.format("id_%x.bin", id))));
            byte[] daeA = store.get(0xf0000L), stA = store.get(0L);
            println("##### CASE item 0x96 = " + c + "  | PHASE A: state+0x18=" + stA[0x18] + " +0x19=" + stA[0x19] + " dae+2=" + daeA[2] + " dae+3=" + daeA[3]);
            m.setBytes(addr(RAM), new byte[RAM_SIZE]);
            EmulatorHelper e = new EmulatorHelper(currentProgram);
            try {
                e.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                    @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
                });
                e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
                unk.clear();
                run(e, 0x60050d00L, "FUN_60050d00");
                byte[] loc = e.readMemory(addr(LOCAL), 0xf0);
                println(String.format("  PHASE B: settings+0x18=%02x +0x19=%02x | local +2=%02x +3=%02x +7=%02x +0xc=%02x +0xd=%02x ; missed read ids=%s ; unmodeled calls=%d %s",
                    b(e, SETTINGS + 0x18), b(e, SETTINGS + 0x19), loc[2], loc[3], loc[7], loc[0xc], loc[0xd], missed, unk.size(), unk));
                println("  local[0..0x10]=" + hex(Arrays.copyOf(loc, 16)));
                byte[] ram = e.readMemory(addr(RAM), RAM_SIZE);
                for (int sel = 0; sel <= 2; sel++) {
                    boolean whatIf = sel == 2;     // sel 2 = what-if: selector 1 AND local+0xc forced 1 (NOT the real chain)
                    int selector = whatIf ? 1 : sel;
                    e.writeMemory(addr(RAM), ram);
                    byte[] init = Arrays.copyOf(daeA, 0xf0);
                    if (getScriptArgs().length > 1) Arrays.fill(init, (byte) 0xee);   // sentinel mode: proves the builder copy overwrites +2/+3
                    e.writeMemory(addr(SHARED), init);
                    shmPtr = SHARED;
                    e.writeMemory(addr(SHM_CELL), new byte[] {(byte) SHARED, (byte) (SHARED >> 8), (byte) (SHARED >> 16), (byte) (SHARED >> 24)});
                    e.writeMemory(addr(SELECTOR), new byte[] {(byte) selector});
                    if (whatIf) e.writeMemory(addr(LOCAL + 0xc), new byte[] {1});
                    cmd1f = 0; unk.clear();
                    String tag = whatIf ? "WHAT-IF sel=1 + local+0xc forced 1" : "REAL sel=" + sel + (sel == 0 ? " (Off)" : " (Rock)");
                    println("  PHASE C " + tag);
                    run(e, 0x601d0d58L, "FUN_601d0d58");
                    byte[] sh = e.readMemory(addr(SHARED), 0xf0);
                    int s2 = sh[2] & 255;
                    long sr = 44100;
                    String dsp = s2 == 0 ? "adppext.dsp" : (sr <= 48000 ? "adppdrc.dsp" : "audiopp.dsp(>48k)");
                    String dsp96 = s2 == 0 ? "adppext.dsp" : "audiopp.dsp(96k)";
                    String row = String.format("item96=%d | %s | stage A dae+2/+3=%d/%d | B local+2/+3=%d/%d +0xc=%d | C shared+2/+3/+7/+0xd=%d/%d/%d/%d cmd1f=%d | DSP@<=48k=%s @>48k=%s",
                        c, tag, daeA[2], daeA[3], loc[2], loc[3], loc[0xc], sh[2], sh[3], sh[7], sh[0xd], cmd1f, dsp, s2 == 0 ? "adppext.dsp" : "audiopp.dsp");
                    println("  RESULT " + row);
                    table.add(row);
                    checkRow(c, whatIf, loc, daeA, sh, cmd1f);
                }
            } finally { e.dispose(); }
        }
        println("=== TABLE");
        for (String r : table) println("ROW " + r);
        println("MENGINE FLASH BUILDER P-CODE PASS item96=0,1,2 gate=settings+0x18 builder-never-writes +2/+3 synthetic=flash-files,fill-copy,command-stub,shared-buffer");
    }
}

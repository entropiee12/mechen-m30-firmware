import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Run mmm_pp.al FUN_68013258 in p-code under varying shared-memory +2 values; synthetic services record calls. */
public class RunMmmPpDspSelect extends GhidraScript {
    private static final long ENTRY = 0x68013258L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc00000L;
    private static final int RAM_SIZE = 0x20000;
    private static final long MMIO = 0xc0000000L;
    private static final long STUB_A = 0xbfc13a60L; // pool 0x68013378: clear/range service
    private static final long STUB_B = 0xbfc13a64L; // pool 0x68013388: open/service
    private static final long PTR_VAR = 0x9fc13c00L; // iVar1 = *PTR_VAR
    private static final long P = 0x9fc18000L;       // iVar1
    private static final long Q = 0x9fc18400L;       // *(P+0x9c)

    private Address addr(long v) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(v);
    }
    private long reg(EmulatorHelper e, String n) throws Exception {
        return e.readRegister(n).longValue() & 0xffffffffL;
    }
    private long rw(EmulatorHelper e, long a) throws Exception {
        byte[] d = e.readMemory(addr(a), 4);
        return (d[0] & 255L) | ((d[1] & 255L) << 8) | ((d[2] & 255L) << 16) | ((d[3] & 255L) << 24);
    }
    private void ww(EmulatorHelper e, long a, long v) throws Exception {
        e.writeMemory(addr(a), new byte[] {(byte)v, (byte)(v >>> 8), (byte)(v >>> 16), (byte)(v >>> 24)});
    }
    private String cstr(EmulatorHelper e, long a) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            int b = e.readMemory(addr(a + i), 1)[0] & 255;
            if (b == 0) break;
            sb.append((char) b);
        }
        return sb.toString();
    }

    private void scenario(String name, int plus2, int fill, long open1, long open2) throws Exception {
        EmulatorHelper e = new EmulatorHelper(currentProgram);
        List<String> calls = new ArrayList<>();
        List<String> openedNames = new ArrayList<>();
        try {
            e.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            byte[] q = new byte[0x40];
            java.util.Arrays.fill(q, (byte) fill);
            q[2] = (byte) plus2;
            e.writeMemory(addr(Q), q);
            ww(e, PTR_VAR + 0x9c, Q); // iVar1 is the pool VALUE 0x9fc13c00 itself, not a dereference
            e.writeRegister("sp", RAM + RAM_SIZE - 0x100L);
            e.writeRegister("ra", STOP | 1L);
            e.writeRegister("pc", ENTRY);
            boolean returned = false;
            for (int step = 0; step < 20000; step++) {
                long pc = reg(e, "pc");
                long t = pc & ~1L;
                if (t == STOP) { returned = true; break; }
                if (t == STUB_A || t == STUB_B) {
                    long a0 = reg(e, "a0"), a1 = reg(e, "a1"), a2 = reg(e, "a2"), a3 = reg(e, "a3");
                    long sp = reg(e, "sp");
                    long v0 = 0;
                    String fname = "";
                    if (t == STUB_B && a3 == 3) {
                        fname = cstr(e, a0);
                        openedNames.add(fname);
                        boolean first = a1 == 0;
                        v0 = first ? open1 : open2;
                    }
                    calls.add(String.format("%s a0=%08x a1=%x a2=%x a3=%x stack0=%08x%s ret=%08x",
                        t == STUB_A ? "stubA(bfc13a60)" : "stubB(bfc13a64)", a0, a1, a2, a3,
                        rw(e, sp + 16), fname.isEmpty() ? "" : " NAME=\"" + fname + "\"", v0));
                    e.writeRegister("v0", v0);
                    long ra = reg(e, "ra");
                    if ((ra & 1L) != 1L) throw new Exception("untagged ra");
                    e.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!e.step(monitor)) throw new Exception(String.format("stopped at %08x: %s", pc, e.getLastError()));
            }
            if (!returned) throw new Exception("no return");
            long ret = reg(e, "v0");
            String expectedSecond = plus2 == 0 ? "adppext.dsp" : "audiopp.dsp";
            int expectedOpens = open1 == 0xffffffffL ? 1 : 2;
            if (openedNames.size() != expectedOpens || !openedNames.get(0).equals("adWAVH.dsp")) {
                throw new Exception(name + ": unexpected first DSP or open count: " + openedNames);
            }
            if (expectedOpens == 2 && !openedNames.get(1).equals(expectedSecond)) {
                throw new Exception(name + ": unexpected second DSP: " + openedNames);
            }
            if ((open1 == 0xffffffffL || open2 == 0xffffffffL) && ret != 0xffffffffL) {
                throw new Exception(name + ": failed open did not propagate -1");
            }
            println(String.format("SCENARIO %s shm+2=0x%02x other-bytes=0x%02x open1=%x open2=%x -> return=%08x calls=%d",
                name, plus2, fill, open1, open2, ret, calls.size()));
            for (String c : calls) println("  CALL " + c);
        } finally {
            e.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        Memory m = currentProgram.getMemory();
        m.createInitializedBlock("mmmpp_ram", addr(RAM), new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false)
            .setWrite(true);
        m.createInitializedBlock("mmmpp_mmio", addr(MMIO), new ByteArrayInputStream(new byte[0x2000]), 0x2000, monitor, false)
            .setWrite(true);
        // raw stub words for the record
        StringBuilder sb = new StringBuilder();
        for (long a = 0xbfc13a5cL; a < 0xbfc13a6cL; a += 2) {
            byte[] d = new byte[2];
            m.getBytes(addr(a), d);
            sb.append(String.format("%02x%02x ", d[1] & 255, d[0] & 255));
        }
        println("STUB HALFWORDS bfc13a5c..: " + sb);
        scenario("A", 0x00, 0x00, 0x1111, 0x2222);
        scenario("B", 0x01, 0x00, 0x1111, 0x2222);
        scenario("C", 0xff, 0x00, 0x1111, 0x2222);
        scenario("D", 0x00, 0xff, 0x1111, 0x2222);
        scenario("E", 0x01, 0xff, 0x1111, 0x2222);
        scenario("F", 0x00, 0x00, -1L & 0xffffffffL, 0x2222);
        scenario("G", 0x01, 0x00, 0x1111, -1L & 0xffffffffL);
        println("MMM_PP DSP SELECT P-CODE PASS");
    }
}

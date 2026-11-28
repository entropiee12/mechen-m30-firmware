import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.*;

/** kernel.drv bank-6 ops 0x47 (create) / 0x49 (attach) / 0x48 (delete) in p-code; IRQ-lock and print are synthetic. */
public class RunKernelShmOps extends GhidraScript {
    static final long STOP = 0x700300L, RAM = 0x9fc1a000L, STACK = 0x9fc1af00L, BUF = 0x10001000L;
    static final long IRQ_SAVE = 0xbfc064b0L, IRQ_REST = 0xbfc064bcL, PRINT = 0xbfc08770L;
    Address addr(long v) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(v); }
    long reg(EmulatorHelper e, String n) throws Exception { return e.readRegister(n).longValue() & 0xffffffffL; }
    List<String> log = new ArrayList<>();
    EmulatorHelper e;
    long tableBase;

    void expect(String label, long actual, long expected) throws Exception {
        if (actual != expected) {
            throw new Exception(String.format("%s: got 0x%08x, expected 0x%08x", label, actual, expected));
        }
    }

    long call(long fn, long a0, long a1, long a2, String label) throws Exception {
        e.writeRegister("sp", STACK); e.writeRegister("ra", STOP | 1L);
        e.writeRegister("a0", a0); e.writeRegister("a1", a1); e.writeRegister("a2", a2); e.writeRegister("a3", 0);
        e.writeRegister("pc", fn);
        for (int step = 0; step < 20000; step++) {
            long pc = reg(e, "pc") & ~1L;
            if (pc == STOP) return reg(e, "v0");
            long ra = reg(e, "ra");
            if (pc == IRQ_SAVE) { log.add("irq_save"); e.writeRegister("v0", 0x1234); e.writeRegister("pc", ra & ~1L); e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE); continue; }
            if (pc == IRQ_REST) { log.add("irq_restore(" + Long.toHexString(reg(e, "a0")) + ")"); e.writeRegister("pc", ra & ~1L); e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE); continue; }
            if (pc == PRINT) { log.add(String.format("PRINT(str=%08x)", reg(e, "a0"))); e.writeRegister("pc", ra & ~1L); e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE); continue; }
            if ((pc & 0x0f000000L) == 0x0f000000L && (pc >>> 28) == 0) { // far bank call convention 0x0fBBxxxx -> window offset
                e.writeRegister("pc", 0x9000L | (pc & 0xfff)); log.add(String.format("far-call %08x -> %04x", pc, 0x9000 | (pc & 0xfff))); continue;
            }
            if (!e.step(monitor)) throw new Exception(String.format("%s: stopped at %08x: %s", label, pc, e.getLastError()));
        }
        throw new Exception("step limit " + label);
    }

    String table() throws Exception {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            byte[] b = e.readMemory(addr(tableBase + i * 8), 8);
            s.append(String.format("[%d id=%02x b1=%02x len=%02x%02x ptr=%02x%02x%02x%02x] ", i, b[0] & 255, b[1] & 255, b[3] & 255, b[2] & 255, b[7] & 255, b[6] & 255, b[5] & 255, b[4] & 255));
        }
        return s.toString();
    }

    @Override public void run() throws Exception {
        Memory m = currentProgram.getMemory();
        m.getBlock(addr(0x9fc1a400L)).setWrite(true);
        m.createInitializedBlock("ramA", addr(0x9fc1a000L), new ByteArrayInputStream(new byte[0x400]), 0x400, monitor, false).setWrite(true);
        m.createInitializedBlock("ramB", addr(0x9fc1a48cL), new ByteArrayInputStream(new byte[0xb74]), 0xb74, monitor, false).setWrite(true);
        m.createInitializedBlock("buf", addr(0x10000000L), new ByteArrayInputStream(new byte[0x4000]), 0x4000, monitor, false).setWrite(true);
        m.createInitializedBlock("kram", addr(0x13000L), new ByteArrayInputStream(new byte[0x1000]), 0x1000, monitor, false).setWrite(true);
        byte[] pool = new byte[4];
        m.getBytes(addr(0x9170), pool);
        tableBase = (pool[0] & 255L) | ((pool[1] & 255L) << 8) | ((pool[2] & 255L) << 16) | ((pool[3] & 255L) << 24);
        expect("SHM table pool", tableBase, 0x13d58L);
        println(String.format("lookup-table pool 0x9170 = 0x%08x", tableBase));
        for (int i = 0; i < 8; i++) m.setByte(addr(tableBase + i * 8), (byte) 0xff);   // synthetic: all slots free
        e = new EmulatorHelper(currentProgram);
        try {
            e.registerCallOtherCallback("setISAMode", new BreakCallBack() { @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; } });
            e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            long r;
            r = call(0x9218, 3, 0, 0, "attach3-empty"); println(String.format("attach(id=3) before create -> 0x%08x", r));
            expect("attach before create", r, 0);
            r = call(0x9174, 3, BUF, 0xf0, "create"); println(String.format("create(id=3,ptr=%08x,len=0xf0) -> 0x%08x", BUF, r));
            expect("create", r, 0);
            byte[] entry = e.readMemory(addr(tableBase), 8);
            expect("registered id", entry[0] & 255L, 3);
            expect("registered length", (entry[2] & 255L) | ((entry[3] & 255L) << 8), 0xf0);
            expect("registered pointer", (entry[4] & 255L) | ((entry[5] & 255L) << 8) | ((entry[6] & 255L) << 16) | ((entry[7] & 255L) << 24), BUF);
            println("  table: " + table());
            r = call(0x9218, 3, 0, 0, "attach3"); println(String.format("attach(id=3) -> 0x%08x  (expect %08x if pointer is stored, not copied)", r, BUF));
            expect("attach registered pointer", r, BUF);
            r = call(0x9218, 4, 0, 0, "attach4"); println(String.format("attach(id=4, never created) -> 0x%08x", r));
            expect("attach missing id", r, 0);
            r = call(0x91e8, 3, 0, 0, "delete3"); println(String.format("delete(id=3) -> 0x%08x", r));
            expect("deleted id", e.readMemory(addr(tableBase), 1)[0] & 255L, 0xff);
            r = call(0x9218, 3, 0, 0, "attach3-after-delete"); println(String.format("attach(id=3) after delete -> 0x%08x", r));
            expect("attach after delete", r, 0);
            for (int id = 10; id < 19; id++) {
                r = call(0x9174, id, BUF + id * 16, 0x10, "fill");
                println(String.format("create(id=%d) -> 0x%08x", id, r));
                expect("create id=" + id, r, id < 18 ? 0 : 0xffffffffL);
            }
            println("  table: " + table());
            println("  log: " + log);
            println("KERNEL SHM OPS P-CODE PASS");
        } finally { e.dispose(); }
    }
}

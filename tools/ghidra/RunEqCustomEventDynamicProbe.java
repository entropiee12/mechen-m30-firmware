import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

/** Execute the real V1.101.12 Custom callback and its shared event target. */
public class RunEqCustomEventDynamicProbe extends GhidraScript {
    private static final long CALLBACK = 0x40414c00L, HELPER = 0x48a94798L;
    private static final long TARGET = 0x48ad47b0L, STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L, STACK = 0x9fc1aff0L;
    private Address addr(long x) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(x); }
    private long reg(EmulatorHelper e, String n) throws Exception { return e.readRegister(n).longValue() & 0xffffffffL; }
    private byte[] bytes(EmulatorHelper e, long a, int n) throws Exception { return e.readMemory(addr(a), n); }
    private void require(boolean b, String m) throws Exception { if (!b) throw new Exception(m); }
    @Override public void run() throws Exception {
        var mem = currentProgram.getMemory();
        MemoryBlock ram = mem.createInitializedBlock("eq_custom_dynamic_ram", addr(RAM), new ByteArrayInputStream(new byte[0x7000]), 0x7000, monitor, false);
        ram.setRead(true); ram.setWrite(true); ram.setExecute(true);
        for (MemoryBlock block : mem.getBlocks()) block.setWrite(true);
        EmulatorHelper e = new EmulatorHelper(currentProgram);
        int steps = 0, helperCalls = 0, targetCalls = 0, residentStubs = 0, appStubs = 0, eventLoopEntries = 0;
        long[] residentA3 = new long[3];
        long result = 0, payload = 0;
        try {
            e.registerCallOtherCallback("setISAMode", new BreakCallBack() { @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; } });
            e.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            e.writeRegister("sp", STACK); e.writeRegister("ra", STOP | 1L);
            e.writeRegister("a0", 0); e.writeRegister("a1", 0); e.writeRegister("s0", 0); e.writeRegister("s1", 0);
            e.writeRegister("pc", CALLBACK);
            for (steps = 0; steps < 10000; steps++) {
                long pc = reg(e, "pc"), p = pc & ~1L, ra;
                if (p == STOP) { result = reg(e, "v0"); break; }
                if (p == HELPER) {
                    helperCalls++; ra = reg(e, "ra"); e.writeRegister("v0", 0xffff8011L); e.writeRegister("pc", ra & ~1L); continue;
                }
                if (p == TARGET) {
                    targetCalls++;
                    require(reg(e, "a0") == 0x8000, "event selector");
                    payload = reg(e, "a1");
                    byte[] b = bytes(e, payload, 8);
                    require((b[0] & 255) == 3 && (b[1] & 255) == 5, "event type/id");
                    require(b[2] == -1 && b[3] == -1, "event sentinel field");
                    long nested = (b[4] & 255L) | ((b[5] & 255L) << 8) | ((b[6] & 255L) << 16) | ((b[7] & 255L) << 24);
                    byte[] tail = bytes(e, nested, 5);
                    require((tail[0] & 255) == 0x54 && (tail[4] & 255) == 0x7e, "nested payload bytes");
                }
                if (p == 0x48ad4600L) eventLoopEntries++;
                if (p == 0xbfc16404L) { residentA3[residentStubs++] = reg(e, "a3"); ra = reg(e, "ra"); e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue; }
                if (p == 0x48454840L || p == 0x484547acL || p == 0x48b546b4L) { appStubs++; ra = reg(e, "ra"); e.writeRegister("v0", 0); e.writeRegister("pc", ra & ~1L); continue; }
                if (!e.step(monitor)) throw new Exception(String.format("stop at 0x%08x: %s", pc, e.getLastError()));
            }
            require((reg(e, "pc") & ~1L) == STOP, "callback did not return");
            require(helperCalls == 1 && targetCalls == 1, "callback-to-target sequence");
            require(residentStubs == 3 && appStubs == 4, "service-stub path counts");
            require(residentA3[0] == 0x14 && residentA3[1] == 0x12 && residentA3[2] == 9, "resident system-call envelope");
            require(eventLoopEntries == 0, "unsupported payload entered event loop");
            require((short)result == (short)0x8014, "target return should be -0x7fec");
            println(String.format("EQ CUSTOM EVENT DYNAMIC PASS steps=%d helper=%d target=%d type=3 resident-stubs=%d app-stubs=%d result=0x%08x", steps, helperCalls, targetCalls, residentStubs, appStubs, result));
        } finally { e.dispose(); }
    }
}

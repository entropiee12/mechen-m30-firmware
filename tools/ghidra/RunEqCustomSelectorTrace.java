import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

/** Watch the EQ selector during the known Custom callback -> shared event path. */
public class RunEqCustomSelectorTrace extends GhidraScript {
    private static final long CALLBACK = 0x40414c00L;
    private static final long HELPER = 0x48a94798L;
    private static final long TARGET = 0x48ad47b0L;
    private static final long EVENT_LOOP = 0x48ad4600L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L;
    private static final long RAM_SIZE = 0x7000L;
    private static final long STACK = 0x9fc1aff0L;
    private static final long SELECTOR = 0x9fc1570dL;
    private static final int SENTINEL = 0x55;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private int byteAt(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xff;
    }

    private byte[] bytes(EmulatorHelper emu, long address, int length) throws Exception {
        return emu.readMemory(addr(address), length);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    @Override
    public void run() throws Exception {
        MemoryBlock ram = currentProgram.getMemory().createInitializedBlock(
            "eq_custom_selector_trace_ram", addr(RAM),
            new ByteArrayInputStream(new byte[(int)RAM_SIZE]), RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) block.setWrite(true);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int steps = 0, helperCalls = 0, targetCalls = 0, residentCalls = 0;
        int appCalls = 0, selectorValueChanges = 0, eventLoopEntries = 0;
        int selector = SENTINEL;
        long result = 0;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", 0);
            emu.writeRegister("a1", 0);
            emu.writeRegister("s0", 0);
            emu.writeRegister("s1", 0);
            emu.writeMemory(addr(SELECTOR), new byte[] {(byte)SENTINEL});
            emu.writeRegister("pc", CALLBACK);

            for (steps = 0; steps < 10000; steps++) {
                long pc = reg(emu, "pc"), normalizedPc = pc & ~1L, ra;
                if (normalizedPc == STOP) {
                    result = reg(emu, "v0");
                    break;
                }
                if (normalizedPc == HELPER) {
                    helperCalls++;
                    ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0xffff8011L);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (normalizedPc == TARGET) {
                    targetCalls++;
                    require(reg(emu, "a0") == 0x8000, "event selector is not 0x8000");
                    long payload = reg(emu, "a1");
                    byte[] data = bytes(emu, payload, 8);
                    require((data[0] & 0xff) == 3 && (data[1] & 0xff) == 5,
                        "request payload is not type 3/id 5");
                    require(data[2] == -1 && data[3] == -1, "request sentinel mismatch");
                    long nested = (data[4] & 0xffL) | ((data[5] & 0xffL) << 8)
                        | ((data[6] & 0xffL) << 16) | ((data[7] & 0xffL) << 24);
                    byte[] nestedData = bytes(emu, nested, 5);
                    require((nestedData[0] & 0xff) == 0x54
                        && (nestedData[4] & 0xff) == 0x7e,
                        "nested callback payload mismatch");
                    println("EQ SELECTOR CALL callback=0x40414c00 -> request=0x8000 "
                        + "payload={type:3,id:5,nested:54..7e} -> target=0x48ad47b0");
                }
                if (normalizedPc == EVENT_LOOP) eventLoopEntries++;
                if (normalizedPc == 0xbfc16404L) {
                    residentCalls++;
                    ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (normalizedPc == 0x48454840L || normalizedPc == 0x484547acL
                    || normalizedPc == 0x48b546b4L) {
                    appCalls++;
                    ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("emulation stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
                }
                int after = byteAt(emu, SELECTOR);
                if (after != selector) {
                    selectorValueChanges++;
                    println(String.format("EQ SELECTOR VALUE CHANGE pc=0x%08x old=0x%02x new=0x%02x",
                        normalizedPc, selector, after));
                    selector = after;
                }
            }
            require((reg(emu, "pc") & ~1L) == STOP, "callback did not return");
            require(helperCalls == 1 && targetCalls == 1, "callback/request path count mismatch");
            require(residentCalls == 3 && appCalls == 4, "expected service boundary count mismatch");
            require(eventLoopEntries == 0, "unsupported type-3 request entered event loop");
            require(selectorValueChanges == 0 && selector == SENTINEL,
                "known callback/request path changed the sampled selector value");
            require((short)result == (short)0x8014, "request result is not -0x7fec");
            println("EQ CUSTOM SELECTOR TRACE PASS callback=0x40414c00 target=0x48ad47b0 "
                + "request=0x8000 type=3 selector_value_changes=0 event_loop=0 result=-0x7fec");
        } finally {
            emu.dispose();
        }
    }
}

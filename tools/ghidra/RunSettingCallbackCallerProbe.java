import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

/** Follow the real setting AP initializer to the callback-slot writer. */
public class RunSettingCallbackCallerProbe extends GhidraScript {
    private static final long ENTRY = 0x40054f78L;
    private static final long WRITER = 0x48494600L;
    private static final long SERVICE_A = 0x48414600L;
    private static final long SERVICE_B = 0x4845476cL;
    private static final long DESCRIPTOR = 0x9fc1a1b0L;
    private static final long RAM = 0x9fc14000L;
    private static final long STACK = 0x9fc1bf00L;
    private static final long STOP = 0x700300L;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private long word(EmulatorHelper emu, long at) throws Exception {
        byte[] b = emu.readMemory(addr(at), 4);
        return (b[0] & 255L) | ((b[1] & 255L) << 8)
            | ((b[2] & 255L) << 16) | ((b[3] & 255L) << 24);
    }

    private int byteAt(EmulatorHelper emu, long at) throws Exception {
        return emu.readMemory(addr(at), 1)[0] & 255;
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private int runEventToCallback(EmulatorHelper emu) throws Exception {
        require(word(emu, 0x9fc15608L) == 0x40054d79L,
            "event path did not inherit the real callback slot");
        emu.writeRegister("sp", STACK);
        emu.writeRegister("ra", STOP | 1L);
        emu.writeRegister("a0", RAM + 0x300);
        emu.writeRegister("a1", RAM + 0x304);
        emu.writeRegister("pc", 0x48914600L);
        int eventCalls = 0, auxiliaryCalls = 0;
        for (int steps = 0; steps < 5000; steps++) {
            long pc = reg(emu, "pc") & ~1L;
            if (pc == 0x40054d78L) {
                require(eventCalls == 4, "callback reached without four event service calls");
                require(auxiliaryCalls == 1, "callback reached with unexpected auxiliary calls");
                require(reg(emu, "a0") == 0x9fc1bee0L,
                    "callback received unexpected event buffer pointer");
                require(word(emu, reg(emu, "a0")) == 0x102L,
                    "callback event buffer lost synthetic event word");
                println(String.format("SETTING EVENT CALLBACK BUFFER pointer=0x%08x word0=0x%08x word1=0x%08x",
                    reg(emu, "a0"), word(emu, reg(emu, "a0")), word(emu, reg(emu, "a0") + 4)));
                return steps;
            }
            if (pc == 0xbfc16578L || pc == 0xbfc16580L) {
                require(pc == 0xbfc16580L && reg(emu, "a0") == 0x9fc1be38L
                    && reg(emu, "a1") == 0x9fc1be88L && reg(emu, "a2") == 8
                    && reg(emu, "a3") == 0,
                    "unexpected auxiliary event call");
                println(String.format("SETTING EVENT AUX BOUNDARY pc=0x%08x a0=0x%08x a1=0x%08x a2=0x%08x a3=0x%08x",
                    pc, reg(emu, "a0"), reg(emu, "a1"), reg(emu, "a2"), reg(emu, "a3")));
                auxiliaryCalls++;
                long ra = reg(emu, "ra");
                emu.writeRegister("v0", 0);
                emu.writeRegister("pc", ra & ~1L);
                continue;
            }
            if (pc == 0xbfc16584L) {
                long a0 = reg(emu, "a0"), a1 = reg(emu, "a1");
                long a3 = reg(emu, "a3"), ra = reg(emu, "ra");
                if (eventCalls == 0) {
                    require(a0 == 6 && a3 == 0x19, "unexpected primary event request");
                    emu.writeRegister("v0", 1);
                } else if (eventCalls == 1) {
                    require(a0 == 5 && a3 == 0x19,
                        String.format("unexpected gate request a0=0x%08x a1=0x%08x a3=0x%08x", a0, a1, a3));
                    emu.writeRegister("v0", 0);
                } else if (eventCalls == 2) {
                    require(a0 == 1 && a3 == 0x18,
                        String.format("unexpected secondary event request a0=0x%08x a1=0x%08x a3=0x%08x", a0, a1, a3));
                    emu.writeRegister("v0", 0);
                } else if (eventCalls == 3) {
                    require(a0 == 1 && a3 == 0x19,
                        String.format("unexpected tertiary event request a0=0x%08x a1=0x%08x a3=0x%08x", a0, a1, a3));
                    emu.writeMemory(addr(a1), new byte[] {2, 1, 0, 0, 0, 0, 0, 0});
                    emu.writeRegister("v0", 0);
                } else throw new Exception(String.format(
                    "unexpected event service request %d a0=0x%08x a1=0x%08x a3=0x%08x",
                    eventCalls, a0, a1, a3));
                eventCalls++;
                emu.writeRegister("pc", ra & ~1L);
                continue;
            }
            require(pc != STOP, "event helper returned before callback");
            if (!emu.step(monitor))
                throw new Exception(String.format("event path stopped at 0x%08x: %s",
                    pc, emu.getLastError()));
        }
        throw new Exception("event path did not reach callback before step limit");
    }

    private int runCallbackToReturn(EmulatorHelper emu) throws Exception {
        long returnAddress = reg(emu, "ra") & ~1L;
        require(returnAddress != 0x40054d78L, "callback return address equals entry");
        for (int steps = 0; steps < 500; steps++) {
            long pc = reg(emu, "pc") & ~1L;
            if (pc == returnAddress && steps > 0) {
                require(pc == 0x489146acL && reg(emu, "v0") == 0x8015L,
                    "0x0102 callback returned unexpected value or destination");
                require(steps == 28, "0x0102 callback instruction path changed");
                println(String.format("SETTING EVENT CALLBACK RETURN pc=0x%08x v0=0x%08x steps=%d",
                    pc, reg(emu, "v0"), steps));
                return steps;
            }
            require(pc != 0xbfc16578L && pc != 0xbfc16580L && pc != 0xbfc16584L,
                String.format("callback reached resident service at 0x%08x", pc));
            if (!emu.step(monitor))
                throw new Exception(String.format("callback stopped at 0x%08x: %s",
                    pc, emu.getLastError()));
        }
        throw new Exception("callback did not return before step limit");
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        var ram = memory.createInitializedBlock("setting_callback_caller_ram", addr(RAM),
            new ByteArrayInputStream(new byte[0x8000]), 0x8000, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int first = 0, second = 0, residentCalls = 0, steps = 0;
        boolean reachedWriter = false;
        long writerReturn = 0;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("pc", ENTRY);
            for (; steps < 5000; steps++) {
                long pc = reg(emu, "pc") & ~1L;
                if (pc == WRITER && !reachedWriter) {
                    require(first == 1 && second == 1, "writer reached without expected services");
                    require(reg(emu, "a0") == 0x40054d79L,
                        String.format("wrong callback argument: 0x%08x", reg(emu, "a0")));
                    require(word(emu, 0x9fc15600L) == DESCRIPTOR,
                        "real initializer did not populate descriptor pointer");
                    require(byteAt(emu, DESCRIPTOR + 1) == 4
                            && byteAt(emu, DESCRIPTOR + 2) == 1
                            && byteAt(emu, DESCRIPTOR + 3) == 1,
                        "real initializer produced unexpected descriptor gate fields");
                    reachedWriter = true;
                    writerReturn = reg(emu, "ra") & ~1L;
                }
                if (reachedWriter && pc == writerReturn) {
                    require(second == 1 && residentCalls == 16,
                        "unexpected external call sequence before writer return");
                    require(word(emu, 0x9fc15608L) == 0x40054d79L,
                        "real writer did not store the real callback argument");
                    println(String.format(
                        "SETTING CALLBACK CALLER PASS steps=%d caller=0x%08x writer=0x%08x callback=0x%08x descriptor-slot=0x%08x descriptor-gate=%d callback-slot=0x%08x resident-calls=%d external-effects=synthetic-zero",
                        steps, ENTRY, WRITER, 0x40054d79L, word(emu, 0x9fc15600L),
                        byteAt(emu, DESCRIPTOR + 2), word(emu, 0x9fc15608L), residentCalls));
                    int eventSteps = runEventToCallback(emu);
                    println(String.format(
                        "SETTING CALLBACK EVENT BRIDGE PASS callback=0x%08x event-steps=%d event-word=0x0102 event-services=synthetic-1,0,0,0",
                        reg(emu, "pc") & ~1L, eventSteps));
                    runCallbackToReturn(emu);
                    return;
                }
                if (pc == SERVICE_A) {
                    require(first++ == 0 && reg(emu, "a0") == 4 && reg(emu, "a1") == 1,
                        "unexpected initializer arguments");
                }
                if (pc == 0xbfc16578L || pc == 0xbfc16580L || pc == 0xbfc16584L) {
                    residentCalls++;
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (pc == SERVICE_B) {
                    long a0 = reg(emu, "a0"), a1 = reg(emu, "a1");
                    long ra = reg(emu, "ra");
                    require(second++ == 0 && a0 == 0x9fc15518L && a1 == 5,
                        "unexpected second service arguments");
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                require(pc != STOP, "initializer returned before callback writer");
                if (!emu.step(monitor))
                    throw new Exception(String.format("caller stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
            }
            throw new Exception("callback writer not reached before step limit");
        } finally {
            emu.dispose();
        }
    }
}

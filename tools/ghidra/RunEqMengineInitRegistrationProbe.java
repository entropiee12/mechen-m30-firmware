import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

/** Capture the MEngine entry's real operation-0x4a callback-registration arguments. */
public class RunEqMengineInitRegistrationProbe extends GhidraScript {
    private static final long ENTRY = 0x60010d30L;
    private static final long CALLBACK = 0x60010d00L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc14000L;
    private static final int RAM_SIZE = 0x8000;
    private static final long STACK = RAM + RAM_SIZE - 0x1000;
    private static final long ARGS = RAM + 0x1000;
    private static final int MAX_STEPS = 10000;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private byte[] bytes(EmulatorHelper emu, long at, int length) throws Exception {
        return emu.readMemory(addr(at), length);
    }

    private long word(EmulatorHelper emu, long at) throws Exception {
        byte[] b = bytes(emu, at, 4);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private int half(EmulatorHelper emu, long at) throws Exception {
        byte[] b = bytes(emu, at, 2);
        return (b[0] & 0xff) | ((b[1] & 0xff) << 8);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.createInitializedBlock("eq_init_registration_ram", addr(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int steps = 0;
        int acquireCalls = 0;
        int registrationCalls = 0;
        long registrationRecord = 0;
        long registrationHandle = 0;
        boolean returned = false;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

            memory.setBytes(addr(ARGS), new byte[] {
                0x22, 0x11, 0x44, 0x33, (byte)0x88, 0x77, 0x66, 0x55
            });
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", 0x12345678L);
            emu.writeRegister("a1", ARGS);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            for (int i = 0; i <= 7; i++) emu.writeRegister("s" + i, 0);
            for (int i = 0; i <= 9; i++) emu.writeRegister("t" + i, 0);
            emu.writeRegister("v0", 0);
            emu.writeRegister("v1", 0);
            emu.writeRegister("gp", 0);
            emu.writeRegister("pc", ENTRY);

            for (steps = 0; steps < MAX_STEPS; steps++) {
                long pc = reg(emu, "pc");
                long target = pc & ~1L;
                if (target == STOP) {
                    returned = true;
                    break;
                }
                if (target == 0xbfc11cc0L) {
                    require(reg(emu, "a0") == 0 && reg(emu, "a1") == 0
                            && reg(emu, "a2") == 0 && reg(emu, "a3") == 0x10,
                        "unexpected MEngine resource-acquire request");
                    acquireCalls++;
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0x22);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (target == 0xbfc11cc8L) {
                    long a0 = reg(emu, "a0"), a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2"), a3 = reg(emu, "a3");
                    long ra = reg(emu, "ra");
                    if (a3 == 0x33) {
                        require(a0 == 0 && a1 == 0 && a2 == 0,
                            "unexpected pre-registration operation 0x33 arguments");
                    } else if (a3 == 0x4a) {
                        require(a0 >= STACK - 0x100 && a0 < STACK,
                            "callback-registration record is not on the bounded stack");
                        require(a1 == 0x1122 && a2 == 0x22,
                            "callback-registration forwarded unexpected caller fields");
                        require(word(emu, a0) == (CALLBACK | 1L),
                            "registration record does not contain the MIPS16 callback pointer");
                        require(word(emu, a0 + 4) == 0x12345678L
                                && word(emu, a0 + 8) == 0x55667788L
                                && half(emu, a0 + 12) == 0x3344,
                            "callback-registration record does not preserve the remaining caller fields");
                        registrationCalls++;
                        registrationRecord = a0;
                        registrationHandle = a2;
                    } else {
                        throw new Exception(String.format("unexpected resident service op 0x%08x", a3));
                    }
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
            }

            require(returned, "MEngine entry did not return before step limit");
            require(acquireCalls == 1, "expected exactly one resident resource-acquire call");
            require(registrationCalls == 1, "expected exactly one operation-0x4a registration call");
            require(reg(emu, "v0") == 0, "MEngine entry did not report registration success");
            println(String.format(
                "EQ MENGINE INIT REGISTRATION PASS steps=%d entry=0x%08x record=0x%08x callback=0x%08x op=0x4a handle=0x%08x resident_services=synthetic",
                steps, ENTRY, registrationRecord, word(emu, registrationRecord), registrationHandle));
        } finally {
            emu.dispose();
        }
    }
}

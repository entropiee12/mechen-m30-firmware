import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;

/** Execute cleanup callbacks to their module-0x0f trap boundary. */
public class RunSettingCleanupServiceProbe extends GhidraScript {
    private static final long IMPORT15_BREAK = 0xbfc16586L;
    private static final long IMPORT6_BREAK = 0xbfc1658aL;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc14000L;
    private static final long RAM_SIZE = 0x8000L;
    private static final long RECORDS = 0x9fc15800L;
    private static final int RECORD_COUNT = 6;
    private static final long RECORD_LIMIT_CELL = 0x9fc155fcL;
    private static final long RECORD_BASE_CELL = 0x9fc1560cL;
    private static final int MAX_STEPS = 500;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private byte[] le32(long value) {
        return new byte[] {(byte)value, (byte)(value >>> 8),
            (byte)(value >>> 16), (byte)(value >>> 24)};
    }

    private long word(EmulatorHelper emu, long at) throws Exception {
        byte[] b = emu.readMemory(addr(at), 4);
        return (b[0] & 255L) | ((b[1] & 255L) << 8)
            | ((b[2] & 255L) << 16) | ((b[3] & 255L) << 24);
    }

    private int half(EmulatorHelper emu, long at) throws Exception {
        byte[] b = emu.readMemory(addr(at), 2);
        return (b[0] & 255) | ((b[1] & 255) << 8);
    }

    private int octet(EmulatorHelper emu, long at) throws Exception {
        return emu.readMemory(addr(at), 1)[0] & 255;
    }

    private void require(boolean ok, String message) throws Exception {
        if (!ok) throw new Exception(message);
    }

    private EmulatorHelper newEmulator(long entry, long a0) throws Exception {
        Memory memory = currentProgram.getMemory();
        if (memory.getBlock(addr(RAM)) == null) {
            var ram = memory.createInitializedBlock("setting_cleanup_probe_ram", addr(RAM),
                new ByteArrayInputStream(new byte[(int)RAM_SIZE]), RAM_SIZE, monitor, false);
            ram.setRead(true);
            ram.setWrite(true);
            ram.setExecute(true);
        }
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
            @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
        });
        emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
        emu.writeRegister("sp", RAM + RAM_SIZE - 0x100);
        emu.writeRegister("ra", STOP | 1L);
        emu.writeRegister("a0", a0);
        emu.writeRegister("a1", 0);
        emu.writeRegister("a2", 0);
        emu.writeRegister("a3", 0);
        emu.writeRegister("s0", 0);
        emu.writeRegister("s1", 0);
        emu.writeRegister("s2", 0);
        emu.writeRegister("s3", 0);
        emu.writeRegister("s4", 0);
        emu.writeRegister("s5", 0);
        emu.writeRegister("s6", 0);
        emu.writeRegister("s7", 0);
        emu.writeRegister("v0", 0);
        emu.writeRegister("pc", entry);
        return emu;
    }

    private void runImport6Callback(long argument) throws Exception {
        EmulatorHelper emu = newEmulator(0x48b5463cL, argument);
        try {
            for (int steps = 0; steps < MAX_STEPS; steps++) {
                long pc = reg(emu, "pc") & ~1L;
                if (pc == IMPORT6_BREAK) {
                    long a0 = reg(emu, "a0"), a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2"), a3 = reg(emu, "a3");
                    require(reg(emu, "v1") == 6 && a0 == argument
                            && a1 == 0 && a2 == 0 && a3 == 0x1e,
                        String.format("unexpected import-6 args v1=%08x %08x,%08x,%08x,%08x",
                            reg(emu, "v1"), a0, a1, a2, a3));
                    require(octet(emu, 0x9fc1561bL) == (argument & 255),
                        "import-6 callback did not store its input byte before the call");
                    println(String.format(
                        "SETTING CLEANUP IMPORT6 a3=0x1e arg=%d service-args=(%d,0,0,0x1e) stored-byte@0x9fc1561b=%d; stopped at trap boundary",
                        argument, argument, argument));
                    return;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("import-6 callback stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
            }
            throw new Exception("import-6 callback did not reach its trap before step limit");
        } finally {
            emu.dispose();
        }
    }

    private void runImport15Callback(int index, long serviceReturn) throws Exception {
        boolean valid = index >= 0 && index < RECORD_COUNT;
        long record = RECORDS + (index & 255L) * 0x10L;
        EmulatorHelper emu = newEmulator(0x484546f4L, index);
        try {
            emu.writeMemory(addr(RECORD_LIMIT_CELL), le32(RECORD_COUNT));
            emu.writeMemory(addr(RECORD_BASE_CELL), le32(RECORDS));
            if (valid) {
                emu.writeMemory(addr(record), new byte[] {1, (byte)0xa5, 0x34, 0x12});
                emu.writeMemory(addr(record + 8), le32(0xdeadbeefL));
            }
            int calls = 0;
            for (int steps = 0; steps < MAX_STEPS; steps++) {
                long pc = reg(emu, "pc") & ~1L;
                if (pc == STOP) {
                    if (valid) {
                        require(calls == 1, "valid index did not make exactly one import-15 call");
                        long expected = (serviceReturn + 0x1234L) & 0xffffffffL;
                        require(octet(emu, record) == 0, "valid index did not clear record state byte");
                        require(word(emu, record + 8) == expected,
                            String.format("record result mismatch: expected 0x%08x got 0x%08x", expected,
                                word(emu, record + 8)));
                        require(reg(emu, "v0") == 1, "valid index did not return success");
                        println(String.format(
                            "SETTING CLEANUP IMPORT15 valid-index=%d record=0x%08x injected-v0=0x%08x field+2=0x1234 stored+8=0x%08x state-byte=%d",
                            index, record, serviceReturn, word(emu, record + 8), octet(emu, record)));
                    } else {
                        require(calls == 0, "invalid index unexpectedly called import 15");
                        require(reg(emu, "v0") == 0, "invalid index did not return zero");
                        println(String.format("SETTING CLEANUP IMPORT15 invalid-index=%d no-service-call return=0",
                            index));
                    }
                    return;
                }
                if (pc == IMPORT15_BREAK) {
                    long a0 = reg(emu, "a0"), a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2"), a3 = reg(emu, "a3");
                    require(valid && calls == 0 && reg(emu, "v1") == 0x0f && a0 == 0
                            && a1 == 0 && a2 == 0 && a3 == 0x1c,
                        String.format("unexpected selector-0x1c args %08x,%08x,%08x,%08x", a0, a1, a2, a3));
                    calls++;
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", serviceReturn);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("selector-0x1c helper stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
            }
            throw new Exception("import-15 helper did not return before step limit");
        } finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        runImport6Callback(0);
        runImport6Callback(1);
        runImport15Callback(2, 0);
        runImport15Callback(2, 1);
        runImport15Callback(2, 0x55aa);
        runImport15Callback(0, 0x55aa);
        runImport15Callback(5, 0x55aa);
        runImport15Callback(6, 0);
        runImport15Callback(255, 0);
        println("SETTING CLEANUP SERVICE PROBE PASS; service returns are synthetic");
    }
}

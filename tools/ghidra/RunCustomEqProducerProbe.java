import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.Arrays;

/** Execute the patched Custom EQ producer and observe its copy/message boundary. */
public class RunCustomEqProducerProbe extends GhidraScript {
    private static final long ENTRY = 0x403d4ed0L;
    private static final long SEND_ENTRY = 0x403d4e20L;
    private static final long COPY_STUB = 0xbfc16400L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L;
    private static final int RAM_SIZE = 0x6000;
    private static final long STACK = 0x9fc1a800L;
    private static final long DESTINATION = 0x9fc1a19cL;
    private static final long EXPECTED_SOURCE = 0x403d4f00L;
    private static final byte[] GAINS = {6, 5, 0, (byte)0xfe, 0, 4, 3};

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private byte[] bytes(EmulatorHelper emu, long address, int length) throws Exception {
        return emu.readMemory(addr(address), length);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.createInitializedBlock(
            "custom_eq_probe_ram", addr(RAM), new ByteArrayInputStream(new byte[RAM_SIZE]),
            RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        boolean copyObserved = false;
        boolean sendObserved = false;
        int steps = 0;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            Register isa = currentProgram.getRegister("ISA_MODE");
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("pc", ENTRY);

            for (steps = 0; steps < 100000; steps++) {
                long pc = reg(emu, "pc");
                long target = pc & ~1L;
                if (target == STOP) break;

                if (target == COPY_STUB) {
                    long a0 = reg(emu, "a0");
                    long a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2");
                    long a3 = reg(emu, "a3");
                    require(a0 == DESTINATION, "Custom copy destination");
                    require(a1 == EXPECTED_SOURCE, "Custom copy source pointer");
                    require(a2 == 7 && a3 == 0, "Custom copy length/flags");
                    byte[] source = bytes(emu, a1, (int)a2);
                    emu.writeMemory(addr(a0), source);
                    copyObserved = true;
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (target == SEND_ENTRY) {
                    require(copyObserved, "Custom send occurred before gains copy");
                    require(reg(emu, "a0") == 7 && reg(emu, "a1") == 7,
                        "Custom EQ selector payload");
                    require(Arrays.equals(bytes(emu, DESTINATION, GAINS.length), GAINS),
                        "copied Custom EQ gain bytes");
                    sendObserved = true;
                    break;
                }

                if (!emu.step(monitor)) {
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
                }
            }

            require(copyObserved && sendObserved, "Custom EQ producer did not reach observed boundaries");
            println(String.format(
                "CUSTOM EQ PRODUCER P-CODE PASS steps=%d source=0x%08x dest=0x%08x gains=%s payload=7,7",
                steps, EXPECTED_SOURCE, DESTINATION, Arrays.toString(GAINS)));
        } finally {
            emu.dispose();
        }
    }
}

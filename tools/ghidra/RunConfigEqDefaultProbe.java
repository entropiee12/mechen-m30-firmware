// Execute the real config.ap 0x38-byte block initializer with a sentinel tail.
// Only the config-value getter is stubbed; no firmware or device state is changed.
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

public class RunConfigEqDefaultProbe extends GhidraScript {
    private static final long ENTRY = 0x40dd4c00L;
    private static final long CONFIG_GETTER = 0x485146bcL;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc14000L;
    private static final int RAM_SIZE = 0x4000;
    private static final long STATE = 0x9fc15654L;
    private static final int STATE_SIZE = 0x38;
    private static final long STACK = RAM + RAM_SIZE - 0x100;
    private static final int CANDIDATE_OFFSET = 0x2c;
    private static final int CANDIDATE_SIZE = 7;
    private static final int SENTINEL = 0xaa;

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        MemoryBlock ramBlock = memory.createInitializedBlock(
            "config_eq_default_probe_ram",
            address(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]),
            RAM_SIZE,
            monitor,
            false
        );
        ramBlock.setRead(true);
        ramBlock.setWrite(true);

        byte[] initialState = new byte[STATE_SIZE];
        Arrays.fill(initialState, (byte)SENTINEL);
        memory.setBytes(address(STATE), initialState);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        Register isa = currentProgram.getRegister("ISA_MODE");
        int getterCalls = 0;
        int steps = 0;

        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(isa, BigInteger.ONE);
            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", STATE);
            emulator.writeRegister("pc", ENTRY);

            for (steps = 0; steps < 10000; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    break;
                }

                if (pc == CONFIG_GETTER) {
                    getterCalls++;
                    emulator.writeRegister("v0", 0);
                    emulator.writeRegister("pc", register(emulator, "ra") & ~1L);
                    continue;
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "emulation stopped at 0x%08x: %s",
                        pc,
                        emulator.getLastError()
                    ));
                }
            }

            require(register(emulator, "pc") % 0x100000000L == STOP,
                "initializer did not return to the probe sentinel");
            require(getterCalls >= 20, "initializer did not execute its config getter loop");

            byte[] state = emulator.readMemory(address(STATE), STATE_SIZE);
            for (int offset = CANDIDATE_OFFSET;
                 offset < CANDIDATE_OFFSET + CANDIDATE_SIZE;
                 offset++) {
                require((state[offset] & 0xff) == SENTINEL,
                    String.format("initializer changed candidate byte +0x%x", offset));
            }

            println(String.format(
                "CONFIG EQ DEFAULT P-CODE PASS steps=%d getters=%d untouched=+0x2c..+0x32",
                steps,
                getterCalls
            ));
        } finally {
            emulator.dispose();
        }
    }
}

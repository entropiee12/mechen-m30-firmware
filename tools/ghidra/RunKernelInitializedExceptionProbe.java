// Compose the real extracted table constructor and exception vector in p-code.
// The selected fixed-ROM handler is observed as a stop address, never executed.
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

public class RunKernelInitializedExceptionProbe extends GhidraScript {
    private static final long CONSTRUCTOR = 0xbfc32bc8L;
    private static final long TABLE_SETUP_END = 0xbfc32c5cL;
    private static final long VECTOR = 0xbfc08180L;
    private static final long DEFAULT_HANDLER = 0xbfc19a75L;
    private static final long SELECTED_HANDLER = 0xbfc06f18L;
    private static final long TABLE = 0x9fc1a9a4L;
    private static final long STATE = 0x00013d00L;
    private static final long EPC_SAVE = 0x00013dccL;
    private static final long RA_SAVE = 0x00013dd0L;
    private static final long CAUSE_SAVE = 0x00013dd4L;
    private static final long INPUT_EPC = 0x80001234L;
    private static final long INPUT_RA = 0x80005679L;
    private static final long INPUT_CAUSE = 0x80000027L;
    private static final long SENTINEL = 0xccccccccL;
    private static final long INDEX9 = 9;
    private static final int TABLE_BYTES = 0x80;
    private static final int MAX_STEPS = 20000;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void mapRam(Memory memory, String name, long base, int size) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(name, addr(base),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
    }

    private void putWord(Memory memory, long at, long value) throws Exception {
        memory.setBytes(addr(at), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private long readWord(EmulatorHelper emu, long at) throws Exception {
        byte[] b = emu.readMemory(addr(at), 4);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 2)
            throw new Exception("usage: RunKernelInitializedExceptionProbe.java KERNEL.DRV KER_INIT.BIN");

        byte[] kernel = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        byte[] init = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[1]));
        require(kernel.length >= 0x3a0, "kernel.drv truncated before exception vector");
        require(init.length >= 0xc60, "ker_init.bin truncated before table constructor");

        Memory memory = currentProgram.getMemory();
        mapRam(memory, "initialized_exception_table", 0x9fc18000L, 0x4000);
        mapRam(memory, "initialized_exception_state", STATE, 0x100);
        for (int offset = 0; offset < TABLE_BYTES; offset += 4)
            putWord(memory, TABLE + offset, SENTINEL);

        Register isaMode = currentProgram.getProgramContext().getRegister("ISA_MODE");
        currentProgram.getProgramContext().setValue(isaMode,
            addr(0xbfc330a0L), addr(0xbfc330dbL), BigInteger.ONE);
        currentProgram.getProgramContext().setValue(isaMode,
            addr(0xbfc19a74L), addr(0xbfc19a94L), BigInteger.ONE);
        currentProgram.getProgramContext().setValue(isaMode,
            addr(VECTOR), addr(VECTOR + 0x20), BigInteger.ZERO);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.writeRegister("sp", 0x9fc1bf00L);
            emu.writeRegister("ra", 0x00700100L);
            emu.writeRegister("pc", CONSTRUCTOR);

            boolean tableReady = false;
            int constructorSteps = 0;
            for (; constructorSteps < MAX_STEPS; constructorSteps++) {
                long pc = reg(emu, "pc");
                if (pc == TABLE_SETUP_END) {
                    tableReady = true;
                    break;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("constructor stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
            }
            require(tableReady, "table constructor did not reach setup boundary");
            require(readWord(emu, TABLE + INDEX9 * 4) == SELECTED_HANDLER,
                "real constructor did not install ExcCode 9 handler");
            for (int index = 14; index < TABLE_BYTES / 4; index++)
                require(readWord(emu, TABLE + index * 4L) == SENTINEL,
                    "constructor unexpectedly wrote out-of-range slot " + index);

            emu.writeRegister("EPC", INPUT_EPC);
            emu.writeRegister("Cause", INPUT_CAUSE);
            emu.writeRegister("ra", INPUT_RA);
            emu.writeRegister("pc", VECTOR);

            boolean selected = false;
            int vectorSteps = 0;
            for (; vectorSteps < 128; vectorSteps++) {
                long pc = reg(emu, "pc");
                if (pc == SELECTED_HANDLER) {
                    selected = true;
                    break;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("vector stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
            }
            require(selected, "real exception vector did not select initialized handler");
            require(readWord(emu, EPC_SAVE) == INPUT_EPC, "saved EPC mismatch");
            require(readWord(emu, RA_SAVE) == INPUT_RA, "saved RA mismatch");
            require(readWord(emu, CAUSE_SAVE) == INPUT_CAUSE, "saved Cause mismatch");

            StringBuilder dispatch = new StringBuilder();
            long[] handlerEntryRegisters = null;
            long[] expectedHandlerEntryRegisters = {
                0xa0a0a0a0L, 0xa1a1a1a1L, 0xa2a2a2a2L,
                0xa3a3a3a3L, 0xb0b0b0b0L, 0xb1b1b1b1L
            };
            int handlerBoundaryProfiles = 0;
            for (int exceptionCode = 0; exceptionCode < TABLE_BYTES / 4; exceptionCode++) {
                long expectedHandler = readWord(emu, TABLE + exceptionCode * 4L);
                long expectedSlot = DEFAULT_HANDLER;
                if (exceptionCode == 0) expectedSlot = 0xbfc19624L;
                if (exceptionCode == 9) expectedSlot = SELECTED_HANDLER;
                if (exceptionCode == 10) expectedSlot = 0xbfc195d4L;
                if (exceptionCode >= 14) expectedSlot = SENTINEL;
                require(expectedHandler == expectedSlot,
                    String.format("constructor slot %d mismatch: 0x%08x",
                        exceptionCode, expectedHandler));
                long[] causeValues = exceptionCode == 9
                    ? new long[] {
                        0x00000024L, 0x00000027L, 0x80000024L,
                        0x7fffffa7L, 0xdeadbe24L
                    }
                    : new long[] {0x80000003L | ((long)exceptionCode << 2)};

                for (long cause : causeValues) {
                    emu.writeRegister("EPC", INPUT_EPC + exceptionCode * 4L);
                    emu.writeRegister("Cause", cause);
                    emu.writeRegister("ra", INPUT_RA + exceptionCode * 4L);
                    if (exceptionCode == 9) {
                        emu.writeRegister("a0", expectedHandlerEntryRegisters[0]);
                        emu.writeRegister("a1", expectedHandlerEntryRegisters[1]);
                        emu.writeRegister("a2", expectedHandlerEntryRegisters[2]);
                        emu.writeRegister("a3", expectedHandlerEntryRegisters[3]);
                        emu.writeRegister("v0", expectedHandlerEntryRegisters[4]);
                        emu.writeRegister("v1", expectedHandlerEntryRegisters[5]);
                    }
                    emu.writeRegister("pc", VECTOR);

                    boolean caseSelected = false;
                    for (int caseSteps = 0; caseSteps < 128; caseSteps++) {
                        if (reg(emu, "pc") == (expectedHandler & ~1L)) {
                            caseSelected = true;
                            if (exceptionCode == 9) {
                                handlerEntryRegisters = new long[] {
                                    reg(emu, "a0"), reg(emu, "a1"), reg(emu, "a2"),
                                    reg(emu, "a3"), reg(emu, "v0"), reg(emu, "v1")
                                };
                                for (int index = 0; index < expectedHandlerEntryRegisters.length; index++) {
                                    require(handlerEntryRegisters[index]
                                            == expectedHandlerEntryRegisters[index],
                                        "exception vector changed ROM-boundary register index " + index);
                                }
                                handlerBoundaryProfiles++;
                            }
                            break;
                        }
                        if (!emu.step(monitor))
                            throw new Exception(String.format(
                                "vector stopped for ExcCode %d at 0x%08x: %s",
                                exceptionCode, reg(emu, "pc"), emu.getLastError()));
                    }
                    require(caseSelected, "vector did not select table handler for ExcCode "
                        + exceptionCode);
                    require(readWord(emu, EPC_SAVE) == INPUT_EPC + exceptionCode * 4L,
                        "saved EPC mismatch for ExcCode " + exceptionCode);
                    require(readWord(emu, RA_SAVE) == INPUT_RA + exceptionCode * 4L,
                        "saved RA mismatch for ExcCode " + exceptionCode);
                    require(readWord(emu, CAUSE_SAVE) == cause,
                        "saved Cause mismatch for ExcCode " + exceptionCode);
                }
                if (dispatch.length() != 0) dispatch.append(',');
                dispatch.append(String.format("%d:0x%08x", exceptionCode,
                    expectedHandler));
            }

            println(String.format(
                "KERNEL INITIALIZED EXCEPTION PASS constructor=0x%08x vector=0x%08x cause=0x%08x table-offset=0x%02x handler=0x%08x ctor-steps=%d vector-steps=%d real-table=1 dispatch0-31=%s rom-handler-executed=0",
                CONSTRUCTOR, VECTOR, INPUT_CAUSE, INPUT_CAUSE & 0x7c,
                SELECTED_HANDLER, constructorSteps, vectorSteps, dispatch));
            require(handlerEntryRegisters != null,
                "ExcCode 9 did not capture registers at the ROM handler boundary");
            require(handlerBoundaryProfiles == 5,
                "unexpected ExcCode 9 Cause variation profile count");
            println(String.format(
                "KERNEL ROM HANDLER BOUNDARY exc=9 handler=0x%08x profiles=%d a0=%08x a1=%08x a2=%08x a3=%08x v0=%08x v1=%08x",
                SELECTED_HANDLER, handlerBoundaryProfiles,
                handlerEntryRegisters[0], handlerEntryRegisters[1],
                handlerEntryRegisters[2], handlerEntryRegisters[3],
                handlerEntryRegisters[4], handlerEntryRegisters[5]));
        }
        finally {
            emu.dispose();
        }
    }
}

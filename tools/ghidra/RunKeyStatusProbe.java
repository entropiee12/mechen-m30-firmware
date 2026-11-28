// Run the key driver's ADC/status producer with synthetic RAM and MMIO bytes.
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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;

public class RunKeyStatusProbe extends GhidraScript {
    private static final long STATUS_ENTRY = 0xbfc103ccL;
    private static final long LEVEL_ENTRY = 0xbfc1049cL;
    private static final long STOP = 0x700300L;
    private static final long BSS = 0x9fc14328L;
    private static final long STATUS = 0x9fc14368L;
    private static final long MMIO_2 = 0xc0020000L;
    private static final long MMIO_9 = 0xc0090000L;
    private static final long OUTPUT = 0x10000200L;
    private static final long STACK = 0x10003f00L;
    private static final String EXPECTED_SHA256 =
        "858d56ff19106fccb75e35552c9cecca99dce0579c7daac5dce376d742f67ba1";

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private int octet(EmulatorHelper emulator, long location) throws Exception {
        return emulator.readMemory(address(location), 1)[0] & 0xff;
    }

    private long word(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private void writeByte(EmulatorHelper emulator, long location, int value) throws Exception {
        emulator.writeMemory(address(location), new byte[] {(byte)value});
    }

    private void writeWord(EmulatorHelper emulator, long location, long value) throws Exception {
        emulator.writeMemory(address(location), new byte[] {
            (byte)value,
            (byte)(value >>> 8),
            (byte)(value >>> 16),
            (byte)(value >>> 24)
        });
    }

    private void addBlock(Memory memory, long start, int size, String name) throws Exception {
        MemoryBlock block = memory.getBlock(address(start));
        if (block == null) {
            block = memory.createInitializedBlock(
                name,
                address(start),
                new ByteArrayInputStream(new byte[size]),
                size,
                monitor,
                false
            );
        }
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private void runEntry(EmulatorHelper emulator, long entry, long argument) throws Exception {
        emulator.writeRegister("sp", STACK);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("a0", argument);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("pc", entry);

        for (int step = 0; step < 2000; step++) {
            if ((register(emulator, "pc") & ~1L) == STOP) {
                return;
            }
            if (!emulator.step(monitor)) {
                throw new Exception(String.format(
                    "key driver stopped at 0x%08x: %s",
                    register(emulator, "pc"),
                    emulator.getLastError()
                ));
            }
        }

        throw new Exception(String.format("key driver entry 0x%08x did not return", entry));
    }

    private void runLevelSweep(EmulatorHelper emulator) throws Exception {
        StringBuilder ranges = new StringBuilder();
        int rangeStart = 0;
        int previousLevel = -1;

        for (int sample = 0; sample <= 0x7f; sample++) {
            for (int index = 0; index < 10; index++) {
                writeByte(emulator, STATUS + 8 + index, sample);
            }

            writeByte(emulator, OUTPUT, 0);
            runEntry(emulator, LEVEL_ENTRY, OUTPUT);
            int level = octet(emulator, OUTPUT);

            if (previousLevel == -1) {
                previousLevel = level;
            } else if (level != previousLevel) {
                if (ranges.length() > 0) {
                    ranges.append(", ");
                }
                ranges.append(String.format("%d..%d=>%d", rangeStart, sample - 1, previousLevel));
                rangeStart = sample;
                previousLevel = level;
            }
        }

        if (ranges.length() > 0) {
            ranges.append(", ");
        }
        ranges.append(String.format("%d..127=>%d", rangeStart, previousLevel));
        println("KEY ADC LEVEL SWEEP " + ranges);

        for (int index = 0; index < 9; index++) {
            writeByte(emulator, STATUS + 8 + index, 80);
        }
        writeByte(emulator, STATUS + 17, 85);
        writeByte(emulator, OUTPUT, 0);
        runEntry(emulator, LEVEL_ENTRY, OUTPUT);
        require(octet(emulator, OUTPUT) == 0,
            "non-integral ADC mean was not truncated to 80");
        println("KEY ADC MEAN CHECK samples=9x80,1x85 level=0");

        for (int index = 0; index < 10; index++) {
            writeByte(emulator, STATUS + 8 + index, 0xd5);
        }
        writeByte(emulator, OUTPUT, 0);
        runEntry(emulator, LEVEL_ENTRY, OUTPUT);
        require(octet(emulator, OUTPUT) == 1,
            "ADC samples were not masked to seven bits");
        println("KEY ADC MASK CHECK sample=0xd5 masked=0x55 level=1");
    }

    private void runSamplingCadence(Memory memory) throws Exception {
        memory.setBytes(address(BSS), new byte[0xd6]);
        memory.setBytes(address(MMIO_2), new byte[0x1000]);
        memory.setBytes(address(MMIO_9), new byte[0x20]);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(
                currentProgram.getRegister("ISA_MODE"),
                BigInteger.ONE
            );
            writeByte(emulator, STATUS, 0xa5);
            writeWord(emulator, MMIO_2 + 0x10, 0x100);
            writeByte(emulator, MMIO_2 + 0x18, 85);
            writeWord(emulator, MMIO_9 + 0x08, 0);

            for (int invocation = 1; invocation <= 4; invocation++) {
                runEntry(emulator, STATUS_ENTRY, 0);
            }

            require(octet(emulator, STATUS + 8) == 0,
                "ADC sample was stored before the fifth invocation");
            runEntry(emulator, STATUS_ENTRY, 0);
            require(octet(emulator, STATUS + 8) == 85,
                "fifth invocation did not store the first ADC sample");

            for (int invocation = 6; invocation <= 49; invocation++) {
                runEntry(emulator, STATUS_ENTRY, 0);
            }

            require(octet(emulator, STATUS + 16) == 85,
                "ninth ADC sample was not stored on invocation 45");
            require(octet(emulator, STATUS + 17) == 0,
                "tenth ADC sample was stored before invocation 50");
            require(octet(emulator, STATUS) == 0xa5,
                "status updated before the tenth ADC sample");

            runEntry(emulator, STATUS_ENTRY, 0);
            require(octet(emulator, STATUS) == 1,
                "status after first ten-sample batch was not 1");

            writeWord(emulator, MMIO_2 + 0x10, 0);
            writeByte(emulator, MMIO_2 + 0x18, 50);
            for (int invocation = 51; invocation <= 99; invocation++) {
                runEntry(emulator, STATUS_ENTRY, 0);
            }

            require(octet(emulator, STATUS) == 1,
                "status changed before the second ten-sample batch completed");
            require(octet(emulator, STATUS + 8) == 50,
                "second batch did not start replacing the sample window");
            require(octet(emulator, STATUS + 16) == 50,
                "second batch did not replace its ninth sample");
            require(octet(emulator, STATUS + 17) == 85,
                "second batch replaced its tenth sample before invocation 100");

            runEntry(emulator, STATUS_ENTRY, 0);
            require(octet(emulator, STATUS) == 0,
                "status after the second ten-sample batch was not 0");
            for (int index = 0; index < 10; index++) {
                require(octet(emulator, STATUS + 8 + index) == 50,
                    "second batch left a stale sample in the ADC window");
            }
            require((word(emulator, MMIO_2 + 0x0c) & 0x8000) == 0,
                "second status 0 did not clear the LRADC control bit");
            println("KEY STATUS CADENCE PASS invocations=100 batches=2 status_sequence=1,0");
        } finally {
            emulator.dispose();
        }
    }

    private void runCase(Memory memory, int deviceBits, int selectorBit, int expectedStatus)
            throws Exception {
        memory.setBytes(address(BSS), new byte[0xd6]);
        memory.setBytes(address(MMIO_2), new byte[0x1000]);
        memory.setBytes(address(MMIO_9), new byte[0x20]);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(
                currentProgram.getRegister("ISA_MODE"),
                BigInteger.ONE
            );

            // Seed a completed 10-sample batch. The real function overwrites
            // sample 10 from LRADC register 0xc0020018 before updating status.
            writeByte(emulator, STATUS + 1, 9);
            writeByte(emulator, STATUS + 2, 4);
            for (int index = 0; index < 10; index++) {
                writeByte(emulator, STATUS + 8 + index, 85);
            }
            writeWord(emulator, MMIO_2 + 0x10, deviceBits);
            writeByte(emulator, MMIO_2 + 0x18, 85);
            writeWord(emulator, MMIO_9 + 0x08, selectorBit);

            runEntry(emulator, STATUS_ENTRY, 0);
            require(octet(emulator, STATUS) == expectedStatus,
                String.format("status=%d expected=%d", octet(emulator, STATUS), expectedStatus));
            require(octet(emulator, STATUS + 17) == 85,
                "LRADC sample was not stored in the tenth sample slot");
            long controlState = word(emulator, MMIO_9 + 0x08);
            if (expectedStatus == 0) {
                require((controlState & 0x20) == 0,
                    "status 0 did not clear the control-state bit");
                require((word(emulator, MMIO_2 + 0x0c) & 0x8000) == 0,
                    "status 0 did not clear the LRADC control bit");
            } else {
                require((word(emulator, MMIO_9 + 0x04) & 0x08) != 0,
                    "nonzero status did not set control register A bit 3");
                require((word(emulator, MMIO_9) & 0x20) != 0,
                    "nonzero status did not set control register B bit 5");

                if (expectedStatus == 1) {
                    require((controlState & 0x20) != 0,
                        "status 1 did not set the control-state bit");
                } else {
                    require((controlState & 0x20) == 0,
                        "status 2 did not clear the control-state bit");
                }
            }

            emulator.writeMemory(address(OUTPUT), new byte[] {0});
            runEntry(emulator, LEVEL_ENTRY, OUTPUT);
            require(octet(emulator, OUTPUT) == 1,
                String.format("ADC level=%d expected=1 for sample average 85", octet(emulator, OUTPUT)));
            long statusReturnedByOp0 = register(emulator, "v0") & 0xff;
            require(statusReturnedByOp0 == expectedStatus,
                "op-0 wrapper did not return the status produced by the sampler");

            println(String.format(
                "KEY STATUS CASE device_bits=0x%03x selector_bit=0x%x status=%d adc_level=%d status_returned_by_op0=%d",
                deviceBits,
                selectorBit,
                octet(emulator, STATUS),
                octet(emulator, OUTPUT),
                statusReturnedByOp0
            ));

            if (deviceBits == 0 && selectorBit == 0) {
                runLevelSweep(emulator);
            }
        } finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1, "usage: RunKeyStatusProbe.java KEY.DRV");
        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver)
        );
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);

        Memory memory = currentProgram.getMemory();
        require(memory.getBlock(address(STATUS_ENTRY)) != null,
            "key.drv resident code is not mapped");
        addBlock(memory, BSS, 0xd6, "key_status_probe_bss");
        addBlock(memory, MMIO_2, 0x1000, "key_status_probe_mmio_2");
        addBlock(memory, MMIO_9, 0x20, "key_status_probe_mmio_9");
        addBlock(memory, OUTPUT, 4, "key_status_probe_output");
        addBlock(memory, STACK, 0x100, "key_status_probe_stack");

        runCase(memory, 0, 0, 0);
        runCase(memory, 0x200, 0, 0);
        runCase(memory, 0x80, 0, 1);
        runCase(memory, 0x100, 0, 1);
        runCase(memory, 0x100, 0x08, 2);
        runSamplingCadence(memory);
        println("KEY STATUS P-CODE PASS cases=5 synthetic_mmio=1 hardware_semantics=unmodeled");
    }
}

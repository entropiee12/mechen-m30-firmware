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
import java.util.Arrays;

/** Replay the real M30 EP0 SET_INTERFACE handler with a synthetic MMIO window. */
public class RunUsoundInterfaceProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc303c4L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc33c00L;
    private static final int RAM_SIZE = 0x400;
    private static final long MMIO = 0xc0080000L;
    private static final int MMIO_SIZE = 0x400;
    private static final long STACK = 0x9fc35000L;
    private static final long ACK_REGISTER = MMIO + 0x02;
    private static final long SETUP = MMIO + 0x180;
    private static final long EP0_OUT_FIFO = MMIO + 0x140;
    private static final long EP0_FIFO = MMIO + 0x100;
    private static final long SAMPLE_RATE = RAM;
    private static final long FREQUENCY_SET_FLAG = RAM + 0x1d0;
    private static final long FREQUENCY_CHANGED_FLAG = RAM + 0x1d7;
    private static final long[] RING_POINTERS = {
        RAM + 0x1e8, RAM + 0x1ec, RAM + 0x1f0, RAM + 0x213
    };
    private static final int[] RING_POINTER_WIDTHS = {4, 4, 4, 1};
    private static final int DATA_FILE_OFFSET = 0x2900;
    private static final int DATA_LENGTH = 0x154;

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

    private int byteAt(EmulatorHelper emulator, long location) throws Exception {
        return emulator.readMemory(address(location), 1)[0] & 0xff;
    }

    private long wordAt(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private int runHandler(EmulatorHelper emulator) throws Exception {
        emulator.writeRegister("sp", STACK + 0x800);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("s1", 0);
        emulator.writeRegister("pc", ENTRY);

        int steps = 0;
        for (; steps < 100000; steps++) {
            long pc = register(emulator, "pc");
            if ((pc & ~1L) == STOP) {
                return steps;
            }

            if (!emulator.step(monitor)) {
                throw new Exception(String.format(
                    "EP0 handler stopped at 0x%08x: %s",
                    pc,
                    emulator.getLastError()
                ));
            }
        }

        throw new Exception("EP0 handler did not return within the instruction limit");
    }

    private void runFrequencyCase(byte[] dataSegment) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);

        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.writeMemory(address(RAM), new byte[RAM_SIZE]);
            emulator.writeMemory(address(RAM), dataSegment);
            emulator.writeMemory(address(MMIO), new byte[MMIO_SIZE]);
            emulator.writeMemory(address(STACK), new byte[0x1000]);
            emulator.setContextRegister(
                currentProgram.getRegister("ISA_MODE"),
                BigInteger.ONE
            );

            // The OUT payload is deliberately 32 kHz while the descriptor
            // advertises 48 kHz, making the GET_CUR source distinguishable.
            byte[] requestedRate = {0x00, 0x7d, 0x00};
            emulator.writeMemory(address(EP0_OUT_FIFO), requestedRate);
            emulator.writeMemory(address(SETUP), new byte[] {
                0x22, 0x01, 0x00, 0x01, 0x01, 0x00, 0x03, 0x00
            });
            int setSteps = runHandler(emulator);

            byte[] storedRate = emulator.readMemory(address(SAMPLE_RATE), 3);
            require(Arrays.equals(storedRate, requestedRate),
                String.format(
                    "SET_CUR sample rate mismatch: stored=%s setup=%s flags=%02x/%02x ack=%02x steps=%d",
                    Arrays.toString(storedRate),
                    Arrays.toString(emulator.readMemory(address(SETUP), 8)),
                    byteAt(emulator, FREQUENCY_SET_FLAG),
                    byteAt(emulator, FREQUENCY_CHANGED_FLAG),
                    byteAt(emulator, ACK_REGISTER),
                    setSteps
                ));
            require(byteAt(emulator, FREQUENCY_SET_FLAG) == 1,
                "SET_CUR did not set the frequency state flag");
            require(byteAt(emulator, FREQUENCY_CHANGED_FLAG) == 1,
                "SET_CUR did not set the frequency-changed flag");
            require((byteAt(emulator, ACK_REGISTER) & 0x02) != 0,
                "SET_CUR frequency was not acknowledged");

            emulator.writeMemory(address(EP0_FIFO), new byte[3]);
            emulator.writeMemory(address(SETUP), new byte[] {
                (byte)0xa2, (byte)0x81, 0x00, 0x01, 0x01, 0x00, 0x03, 0x00
            });
            int getSteps = runHandler(emulator);

            byte[] returnedRate = emulator.readMemory(address(EP0_FIFO), 3);
            byte[] descriptorRate = { (byte)0x80, (byte)0xbb, 0x00 };
            require(Arrays.equals(returnedRate, descriptorRate),
                "GET_CUR frequency did not return descriptor sample-rate bytes");
            require(byteAt(emulator, MMIO + 0x01) == 3,
                "GET_CUR frequency did not publish a three-byte response");

            println(String.format(
                "USOUND FREQUENCY P-CODE PASS requested=32000 get-cur=48000 set-steps=%d get-steps=%d",
                setSteps,
                getSteps
            ));
        }
        finally {
            emulator.dispose();
        }
    }

    private void runFeatureControlCase(
        byte[] dataSegment,
        int request,
        int expectedValue
    ) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);

        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.writeMemory(address(RAM), new byte[RAM_SIZE]);
            emulator.writeMemory(address(RAM), dataSegment);
            emulator.writeMemory(address(MMIO), new byte[MMIO_SIZE]);
            emulator.writeMemory(address(STACK), new byte[0x1000]);
            emulator.writeMemory(address(EP0_FIFO), new byte[2]);
            emulator.writeMemory(address(SETUP), new byte[] {
                (byte)0xa1, (byte)request, 0x00, 0x02, 0x00, 0x09, 0x02, 0x00
            });
            emulator.setContextRegister(
                currentProgram.getRegister("ISA_MODE"),
                BigInteger.ONE
            );

            int steps = runHandler(emulator);
            byte[] response = emulator.readMemory(address(EP0_FIFO), 2);
            byte[] expected = {
                (byte)expectedValue,
                (byte)(expectedValue >>> 8)
            };
            require(Arrays.equals(response, expected),
                String.format("feature request 0x%02x returned %s", request,
                    Arrays.toString(response)));
            require(byteAt(emulator, MMIO + 0x01) == 2,
                String.format("feature request 0x%02x did not return two bytes", request));
            require((byteAt(emulator, ACK_REGISTER) & 0x02) != 0,
                String.format("feature request 0x%02x was not acknowledged", request));

            println(String.format(
                "USOUND FEATURE CONTROL P-CODE PASS request=0x%02x value=0x%04x steps=%d",
                request,
                expectedValue,
                steps
            ));
        }
        finally {
            emulator.dispose();
        }
    }

    private void runCurrentControlCase(
        byte[] dataSegment,
        int entity,
        int selector,
        int dataOffset,
        byte[] requestedValue
    ) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);

        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.writeMemory(address(RAM), new byte[RAM_SIZE]);
            emulator.writeMemory(address(RAM), dataSegment);
            emulator.writeMemory(address(MMIO), new byte[MMIO_SIZE]);
            emulator.writeMemory(address(STACK), new byte[0x1000]);
            emulator.writeMemory(address(EP0_OUT_FIFO), requestedValue);
            emulator.setContextRegister(
                currentProgram.getRegister("ISA_MODE"),
                BigInteger.ONE
            );

            emulator.writeMemory(address(SETUP), new byte[] {
                0x21, 0x01,
                (byte)selector, (byte)(selector >>> 8),
                0x00, (byte)entity,
                (byte)requestedValue.length, 0x00
            });
            runHandler(emulator);

            byte[] storedValue = emulator.readMemory(address(RAM + dataOffset),
                requestedValue.length);
            require(Arrays.equals(storedValue, requestedValue),
                String.format("SET_CUR entity=0x%02x selector=0x%04x stored=%s",
                    entity, selector, Arrays.toString(storedValue)));

            emulator.writeMemory(address(EP0_FIFO), new byte[requestedValue.length]);
            emulator.writeMemory(address(SETUP), new byte[] {
                (byte)0xa1, (byte)0x81,
                (byte)selector, (byte)(selector >>> 8),
                0x00, (byte)entity,
                (byte)requestedValue.length, 0x00
            });
            int getSteps = runHandler(emulator);

            byte[] response = emulator.readMemory(address(EP0_FIFO),
                requestedValue.length);
            require(Arrays.equals(response, requestedValue),
                String.format("GET_CUR entity=0x%02x selector=0x%04x returned=%s",
                    entity, selector, Arrays.toString(response)));
            require(byteAt(emulator, MMIO + 0x01) == requestedValue.length,
                String.format("GET_CUR entity=0x%02x selector=0x%04x length mismatch",
                    entity, selector));
            require((byteAt(emulator, ACK_REGISTER) & 0x02) != 0,
                String.format("GET_CUR entity=0x%02x selector=0x%04x was not ACKed",
                    entity, selector));

            println(String.format(
                "USOUND CURRENT CONTROL P-CODE PASS entity=0x%02x selector=0x%04x bytes=%d steps=%d",
                entity,
                selector,
                requestedValue.length,
                getSteps
            ));
        }
        finally {
            emulator.dispose();
        }
    }

    private void runCase(
        byte[] dataSegment,
        int alternateSetting,
        boolean expectReset
    ) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);

        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });

            emulator.writeMemory(address(RAM), new byte[RAM_SIZE]);
            emulator.writeMemory(address(RAM), dataSegment);
            emulator.writeMemory(address(MMIO), new byte[MMIO_SIZE]);
            emulator.writeMemory(address(STACK), new byte[0x1000]);

            for (int index = 0; index < RING_POINTERS.length; index++) {
                byte[] sentinel = new byte[] {
                    (byte)(0x41 + index), (byte)(0x51 + index),
                    (byte)(0x61 + index), (byte)(0x71 + index)
                };
                emulator.writeMemory(
                    address(RING_POINTERS[index]),
                    Arrays.copyOf(sentinel, RING_POINTER_WIDTHS[index])
                );
            }

            byte[] setupPacket = {
                0x01, 0x0b,
                (byte)alternateSetting, 0x00,
                0x01, 0x00,
                0x00, 0x00
            };
            emulator.writeMemory(address(SETUP), setupPacket);
            emulator.writeRegister("sp", STACK + 0x800);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("pc", ENTRY);
            emulator.setContextRegister(
                currentProgram.getRegister("ISA_MODE"),
                BigInteger.ONE
            );

            int steps = 0;
            for (; steps < 100000; steps++) {
                long pc = register(emulator, "pc");
                if ((pc & ~1L) == STOP) {
                    break;
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "EP0 handler stopped at 0x%08x: %s",
                        pc,
                        emulator.getLastError()
                    ));
                }
            }

            require(steps < 100000, "EP0 handler did not return");
            require((byteAt(emulator, ACK_REGISTER) & 0x02) != 0,
                "SET_INTERFACE did not acknowledge the request");

            for (int index = 0; index < RING_POINTERS.length; index++) {
                long value = index < 3
                    ? wordAt(emulator, RING_POINTERS[index])
                    : byteAt(emulator, RING_POINTERS[index]);
                if (expectReset) {
                    require(value == 0,
                        String.format("ring pointer %d was not cleared", index));
                }
                else {
                    byte[] expected = Arrays.copyOf(new byte[] {
                        (byte)(0x41 + index), (byte)(0x51 + index),
                        (byte)(0x61 + index), (byte)(0x71 + index)
                    }, RING_POINTER_WIDTHS[index]);
                    byte[] actual = emulator.readMemory(
                        address(RING_POINTERS[index]),
                        RING_POINTER_WIDTHS[index]
                    );
                    require(Arrays.equals(actual, expected),
                        String.format("ring pointer %d changed for alternate %d", index, alternateSetting));
                }
            }

            println(String.format(
                "USOUND SET_INTERFACE P-CODE PASS alt=%d reset=%s steps=%d ack=1",
                alternateSetting,
                expectReset,
                steps
            ));
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: RunUsoundInterfaceProbe.java USOUND.DRV");
        }

        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        if (DATA_FILE_OFFSET + DATA_LENGTH > driver.length) {
            throw new Exception("usound data segment is truncated");
        }
        byte[] dataSegment = Arrays.copyOfRange(
            driver,
            DATA_FILE_OFFSET,
            DATA_FILE_OFFSET + DATA_LENGTH
        );

        Memory memory = currentProgram.getMemory();
        MemoryBlock ramBlock = memory.createInitializedBlock(
            "usound_interface_probe_ram",
            address(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]),
            RAM_SIZE,
            monitor,
            false
        );
        ramBlock.setRead(true);
        ramBlock.setWrite(true);

        MemoryBlock mmioBlock = memory.createInitializedBlock(
            "usound_interface_probe_mmio",
            address(MMIO),
            new ByteArrayInputStream(new byte[MMIO_SIZE]),
            MMIO_SIZE,
            monitor,
            false
        );
        mmioBlock.setRead(true);
        mmioBlock.setWrite(true);

        MemoryBlock stackBlock = memory.createInitializedBlock(
            "usound_interface_probe_stack",
            address(STACK),
            new ByteArrayInputStream(new byte[0x1000]),
            0x1000,
            monitor,
            false
        );
        stackBlock.setRead(true);
        stackBlock.setWrite(true);

        runFrequencyCase(dataSegment);
        runFeatureControlCase(dataSegment, 0x82, 0xe3a0);
        runFeatureControlCase(dataSegment, 0x83, 0xfff0);
        runFeatureControlCase(dataSegment, 0x84, 0x0030);
        runCurrentControlCase(dataSegment, 0x09, 0x0100, 0x1d4,
            new byte[] {0x01});
        runCurrentControlCase(dataSegment, 0x0b, 0x0100, 0x1d5,
            new byte[] {0x00});
        runCurrentControlCase(dataSegment, 0x09, 0x0200, 0x1e0,
            new byte[] {0x34, 0x12});
        runCurrentControlCase(dataSegment, 0x09, 0x0201, 0x1e2,
            new byte[] {0x78, 0x56});
        runCurrentControlCase(dataSegment, 0x09, 0x0202, 0x1e4,
            new byte[] {(byte)0xbc, (byte)0x9a});
        runCase(dataSegment, 1, true);
        runCase(dataSegment, 0, false);
    }
}

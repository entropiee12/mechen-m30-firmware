// Execute the real M30 descriptor patcher with synthetic config-item buffers.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class RunUsoundDescriptorPatchProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc31f94L;
    private static final long COPY_STUB = 0xbfc326a8L;
    private static final long FW_INFO_STUB = 0xbfc326acL;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc33c00L;
    private static final int RAM_SIZE = 0x400;
    private static final long STACK = 0x9fc35000L;
    private static final long VID = RAM + 0x200;
    private static final long PID = RAM + 0x208;
    private static final long MANUFACTURER = RAM + 0xfe;
    private static final long PRODUCT = RAM + 0xda;
    private static final long INTERFACE_FLAGS = RAM + 0x21c;
    private static final long FORMAT_FLAG = RAM + 0x220;
    private static final int DATA_FILE_OFFSET = 0x2900;
    private static final int DATA_LENGTH = 0x154;
    private static final long DEVICE_DESCRIPTOR = RAM + 0x34;
    private static final long CONFIG_DESCRIPTOR = RAM + 0x48;
    private static final long STRING_MANUFACTURER = RAM + 0xfc;
    private static final long STRING_PRODUCT = RAM + 0xd8;
    private static final long STRING_SERIAL = RAM + 0x110;
    private static final long SERIAL_BUFFER = RAM + 0x112;
    private static final byte[] SERIAL_VALUE = "M30-SERIAL-12345".getBytes(
        java.nio.charset.StandardCharsets.US_ASCII
    );
    private static final byte[] EMPTY_SERIAL_VALUE = new byte[0x10];
    private int lastFwInfoCalls;
    private long lastSerialBuffer;
    private int syntheticFwInfoStatus = 2;
    private byte[] syntheticSerialValue = SERIAL_VALUE;

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private int byteAt(EmulatorHelper emulator, long location) throws Exception {
        return emulator.readMemory(address(location), 1)[0] & 0xff;
    }

    private byte[] bytesAt(EmulatorHelper emulator, long location, int length) throws Exception {
        return emulator.readMemory(address(location), length);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private void writeConfigCase(
        EmulatorHelper emulator,
        String vid,
        String pid,
        String manufacturer,
        String product,
        int interfaceFlags,
        int formatFlag
    ) throws Exception {
        emulator.writeMemory(address(VID), nulTerminated(vid));
        emulator.writeMemory(address(PID), nulTerminated(pid));
        emulator.writeMemory(address(MANUFACTURER), nulTerminated(manufacturer));
        emulator.writeMemory(address(PRODUCT), nulTerminated(product));
        emulator.writeMemory(address(INTERFACE_FLAGS), new byte[] {
            (byte)interfaceFlags, 0, 0, 0
        });
        emulator.writeMemory(address(FORMAT_FLAG), new byte[] {(byte)formatFlag});
    }

    private byte[] nulTerminated(String value) {
        byte[] text = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return Arrays.copyOf(text, text.length + 1);
    }

    private int runPatcher(EmulatorHelper emulator) throws Exception {
        int copyCalls = 0;
        int fwInfoCalls = 0;
        lastSerialBuffer = 0;
        emulator.writeRegister("sp", STACK + 0x800);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("s1", 0);
        emulator.writeRegister("pc", ENTRY);

        for (int steps = 0; steps < 100000; steps++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc == STOP) {
                require(copyCalls > 0, "descriptor patcher did not call the copy stub");
                lastFwInfoCalls = fwInfoCalls;

                return steps;
            }

            if (pc == COPY_STUB) {
                long destination = register(emulator, "a0");
                long source = register(emulator, "a1");
                long length = register(emulator, "a2");
                require(length <= 0x200, "copy stub received an unexpected length");
                byte[] copied = bytesAt(emulator, source, (int)length);
                emulator.writeMemory(address(destination), copied);
                emulator.writeRegister("v0", 0);
                emulator.writeRegister("pc", register(emulator, "ra") & ~1L);
                copyCalls++;

                continue;
            }

            if (pc == FW_INFO_STUB) {
                long output = register(emulator, "a0");
                long property = register(emulator, "a1");
                long length = register(emulator, "a2");
                println(String.format(
                    "USOUND FW INFO CALL a0=0x%08x a1=0x%08x a2=0x%08x a3=0x%08x",
                    output,
                    property,
                    length,
                    register(emulator, "a3")
                ));

                if (property == 0x4f && length == 1) {
                    emulator.writeMemory(address(output),
                        new byte[] {(byte)syntheticFwInfoStatus});
                }
                else if (property == 0x50 && length == 0x10) {
                    require(syntheticSerialValue.length == length,
                        "synthetic serial width");
                    emulator.writeMemory(address(output), syntheticSerialValue);
                    lastSerialBuffer = output;
                }
                else {
                    throw new Exception(String.format(
                        "unexpected firmware-info request property=0x%x length=0x%x",
                        property,
                        length
                    ));
                }

                emulator.writeRegister("v0", 0);
                emulator.writeRegister("pc", register(emulator, "ra") & ~1L);
                fwInfoCalls++;

                continue;
            }

            if (!emulator.step(monitor)) {
                throw new Exception(String.format(
                    "descriptor patcher stopped at 0x%08x: %s",
                    pc,
                    emulator.getLastError()
                ));
            }
        }

        throw new Exception(String.format(
            "descriptor patcher did not return (copy=%d fw-info=%d)",
            copyCalls,
            fwInfoCalls
        ));
    }

    private void runCase(byte[] dataSegment, String name, int fwInfoStatus,
        int formatFlag, int interfaceFlags,
        String vid, String pid, String manufacturer, String product, int expectedVid,
        int expectedPid, int expectedInterfaces, int expectedSubframeBytes,
        int expectedResolution, byte[] expectedRate, int expectedPacket,
        byte[] expectedManufacturerDescriptor, byte[] expectedProductDescriptor
    ) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        syntheticFwInfoStatus = fwInfoStatus;

        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.writeMemory(address(RAM), new byte[RAM_SIZE]);
            emulator.writeMemory(address(RAM), dataSegment);
            emulator.writeMemory(address(STACK), new byte[0x1000]);
            writeConfigCase(emulator, vid, pid, manufacturer, product, interfaceFlags, formatFlag);
            byte[] initialSerialDescriptor = bytesAt(emulator, STRING_SERIAL, 18);
            int initialSerialIndex = byteAt(emulator, DEVICE_DESCRIPTOR + 16);
            emulator.setContextRegister(
                currentProgram.getRegister("ISA_MODE"),
                java.math.BigInteger.ONE
            );

            int steps = runPatcher(emulator);
            byte[] device = bytesAt(emulator, DEVICE_DESCRIPTOR, 18);
            byte[] configuration = bytesAt(emulator, CONFIG_DESCRIPTOR, 0x8a);
            byte[] vidPid = {device[8], device[9], device[10], device[11]};
            byte[] expectedVidPid = {
                (byte)expectedVid,
                (byte)(expectedVid >>> 8),
                (byte)expectedPid,
                (byte)(expectedPid >>> 8)
            };
            require(Arrays.equals(vidPid, expectedVidPid), name + ": VID/PID mismatch");
            require((configuration[0x36] & 0xff) == expectedInterfaces,
                name + ": interface count mismatch");
            require((configuration[0x58] & 0xff) == expectedSubframeBytes,
                name + ": subframe size mismatch");
            require((configuration[0x59] & 0xff) == expectedResolution,
                name + ": bit resolution mismatch");
            require(Arrays.equals(
                Arrays.copyOfRange(configuration, 0x5b, 0x5e), expectedRate
            ), name + ": sample-rate descriptor mismatch");
            require(Arrays.equals(
                Arrays.copyOfRange(configuration, 0x65, 0x67),
                new byte[] {(byte)expectedPacket, (byte)(expectedPacket >>> 8)}
            ), name + ": endpoint packet size mismatch");
            require(Arrays.equals(
                bytesAt(emulator, STRING_MANUFACTURER, expectedManufacturerDescriptor.length),
                expectedManufacturerDescriptor
            ), name + ": manufacturer string descriptor mismatch");
            require(Arrays.equals(
                bytesAt(emulator, STRING_PRODUCT, expectedProductDescriptor.length),
                expectedProductDescriptor
            ), name + ": product string descriptor mismatch");

            int expectedSerialLength = 0;
            if (fwInfoStatus == 1 || fwInfoStatus == 2) {
                require(lastFwInfoCalls == 2,
                    name + ": expected status and serial firmware-info calls");
                require(lastSerialBuffer == SERIAL_BUFFER,
                    String.format("%s: serial buffer 0x%08x != 0x%08x",
                        name, lastSerialBuffer, SERIAL_BUFFER));
                int serialLength = 0;
                while (serialLength < syntheticSerialValue.length
                    && syntheticSerialValue[serialLength] != 0) {
                    serialLength++;
                }

                if (serialLength == 0) {
                    require((device[16] & 0xff) == 0,
                        name + ": empty serial did not clear iSerialNumber");
                    byte[] expectedSerialDescriptor = Arrays.copyOf(
                        initialSerialDescriptor,
                        initialSerialDescriptor.length
                    );
                    Arrays.fill(expectedSerialDescriptor, 2,
                        expectedSerialDescriptor.length, (byte)0);
                    require(Arrays.equals(
                        bytesAt(emulator, STRING_SERIAL, expectedSerialDescriptor.length),
                        expectedSerialDescriptor
                    ), name + ": empty serial buffer mismatch");
                    expectedSerialLength = expectedSerialDescriptor.length;
                }
                else {
                    byte[] expectedSerialDescriptor = new byte[2 + serialLength * 2];
                    expectedSerialDescriptor[0] = (byte)expectedSerialDescriptor.length;
                    expectedSerialDescriptor[1] = 0x03;
                    for (int index = 0; index < serialLength; index++) {
                        expectedSerialDescriptor[2 + index * 2] = syntheticSerialValue[index];
                    }
                    require((device[16] & 0xff) == initialSerialIndex,
                        name + ": nonempty serial lookup changed iSerialNumber");
                    require(Arrays.equals(
                        bytesAt(emulator, STRING_SERIAL, expectedSerialDescriptor.length),
                        expectedSerialDescriptor
                    ), name + ": serial string descriptor mismatch");
                    expectedSerialLength = expectedSerialDescriptor.length;
                }
            }
            else {
                require(lastFwInfoCalls == 1,
                    name + ": unavailable status should skip serial lookup");
                require(lastSerialBuffer == 0,
                    name + ": unavailable status unexpectedly fetched a serial");
                require((device[16] & 0xff) == initialSerialIndex,
                    name + ": unavailable status changed iSerialNumber");
                require(Arrays.equals(
                    bytesAt(emulator, STRING_SERIAL, initialSerialDescriptor.length),
                    initialSerialDescriptor
                ), name + ": unavailable status changed the initial serial descriptor");
            }

            println(String.format(
                "USOUND DESCRIPTOR P-CODE PASS case=%s vid=%04x pid=%04x interfaces=%d format=%d steps=%d",
                name,
                expectedVid,
                expectedPid,
                expectedInterfaces,
                formatFlag,
                steps
            ));
            println(String.format(
                "USOUND FW INFO TRACE status=0x%02x calls=%d serial-buffer=0x%08x serial-descriptor-bytes=%d iSerialNumber=%d",
                fwInfoStatus,
                lastFwInfoCalls,
                lastSerialBuffer,
                expectedSerialLength,
                device[16] & 0xff
            ));
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: RunUsoundDescriptorPatchProbe.java USOUND.DRV");
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
            "usound_descriptor_patch_probe_ram",
            address(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]),
            RAM_SIZE,
            monitor,
            false
        );
        ramBlock.setRead(true);
        ramBlock.setWrite(true);

        MemoryBlock stackBlock = memory.createInitializedBlock(
            "usound_descriptor_patch_probe_stack",
            address(STACK),
            new ByteArrayInputStream(new byte[0x1000]),
            0x1000,
            monitor,
            false
        );
        stackBlock.setRead(true);
        stackBlock.setWrite(true);

        syntheticSerialValue = SERIAL_VALUE;
        runCase(dataSegment, "stock-config", 2, 0, 3, "0x10D6", "0xdd01", "M30",
            "USB AUDIO", 0x10d6, 0xdd01, 3, 2, 16,
            new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
        runCase(dataSegment, "24bit-96k", 2, 1, 3, "0x10D6", "0xdd01", "M30",
            "USB AUDIO", 0x10d6, 0xdd01, 3, 3, 24,
            new byte[] {0, 0x77, 1}, 0x320,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
        runCase(dataSegment, "interface-flag-clear", 2, 0, 0, "0x10D6", "0xdd01",
            "M30", "USB AUDIO", 0x10d6, 0xdd01, 1, 2, 16,
            new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
        runCase(dataSegment, "interface-flag-bit-zero-only", 2, 0, 1, "0x10D6", "0xdd01",
            "M30", "USB AUDIO", 0x10d6, 0xdd01, 1, 2, 16,
            new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
        runCase(dataSegment, "interface-flag-bit-one-only", 2, 0, 2, "0x10D6", "0xdd01",
            "M30", "USB AUDIO", 0x10d6, 0xdd01, 3, 2, 16,
            new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
        runCase(dataSegment, "empty-vid-pid", 2, 0, 3, "0x", "0x", "", "", 0x10d6,
            0xdd00, 3, 2, 16, new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {2, 3}, new byte[] {2, 3});
        runCase(dataSegment, "serial-unavailable", 0, 0, 3, "0x10D6", "0xdd01",
            "M30", "USB AUDIO", 0x10d6, 0xdd01, 3, 2, 16,
            new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
        runCase(dataSegment, "serial-status-one", 1, 0, 3, "0x10D6", "0xdd01",
            "M30", "USB AUDIO", 0x10d6, 0xdd01, 3, 2, 16,
            new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
        runCase(dataSegment, "serial-status-three", 3, 0, 3, "0x10D6", "0xdd01",
            "M30", "USB AUDIO", 0x10d6, 0xdd01, 3, 2, 16,
            new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
        syntheticFwInfoStatus = 2;
        syntheticSerialValue = EMPTY_SERIAL_VALUE;
        runCase(dataSegment, "serial-empty", 2, 0, 3, "0x10D6", "0xdd01",
            "M30", "USB AUDIO", 0x10d6, 0xdd01, 3, 2, 16,
            new byte[] {(byte)0x80, (byte)0xbb, 0}, 200,
            new byte[] {8, 3, 'M', 0, '3', 0, '0', 0},
            new byte[] {20, 3, 'U', 0, 'S', 0, 'B', 0, ' ', 0,
                'A', 0, 'U', 0, 'D', 0, 'I', 0, 'O', 0});
    }
}

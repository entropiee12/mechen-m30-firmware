import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.math.BigInteger;
import java.util.Arrays;

/** Run the real MIPS16 DAE factory, EQ builder, and aud_dev consumer in sequence. */
public class RunEqConfigDefaultsAuddevProbe extends GhidraScript {
    private static final long FACTORY = 0x60b10f7cL;
    private static final long BUILDER = 0x601d0d58L;
    private static final long AUDDEV = 0x9400L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L;
    private static final int RAM_SIZE = 0x6000;
    private static final long STRUCTURE = 0x9fc15dd4L;
    private static final long SELECTOR = 0x9fc15cc9L;
    private static final long ACTIVE = 0x9fc15db4L;
    private static final long CUSTOM = 0x9fc1a19cL;
    private static final long AUD_COPY_STUB = 0x10003000L;
    private static final long AUD_RAM = 0x10000000L;
    private static final long AUD_STATE = 0x9fc34000L;
    private static final long MENGINE_COPY_STUB = 0xbfc11cc0L;
    private static final long MENGINE_MESSAGE_STUB = 0xbfc11cc4L;
    private static final long MENGINE_SHM_STUB = 0xbfc11cc8L;
    private static final String CONFIG_SHA256 =
        "900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19";
    private static final String AUDDEV_SHA256 =
        "3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c";
    private static final int[] FREQUENCIES = {80, 200, 480, 980, 4000, 8000, 16000};
    private static final int[] Q_VALUES = {10, 6, 4, 6, 10, 5, 8};
    // Preset values are copied from RunEqBuilderProbe.GAINS; no gains are inferred here.
    private static final int[][] PRESET_GAINS = {
        {0, 0, 0, 0, 0, 0, 0},
        {5, 3, 0, -1, 0, 4, 5},
        {3, 0, 0, 0, 0, -1, -2},
        {0, 0, 1, 2, 0, 1, 1},
        {3, 0, 0, -1, 0, 2, 4},
        {0, 0, 0, -1, -1, -2, -4},
        {5, 2, 0, 0, 0, 0, 6}
    };

    private byte[] configBin;
    private long scalarGetter;
    private long itemGetter;
    private long configCopy;
    private long lastItemBuffer;
    private int scalarCalls;
    private int itemCalls;
    private int configCopyCalls;
    private int builderCopyCalls;
    private int messageCalls;
    private int shmCalls;
    private int audCopyCalls;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private long word(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
            | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16)
            | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private byte[] configItem(int itemId) throws Exception {
        int index = 0x10 + itemId * 8;
        require(index >= 0 && index + 8 <= configBin.length,
            String.format("config item 0x%x directory entry is outside config.bin", itemId));
        ByteBuffer directory = ByteBuffer.wrap(configBin).order(ByteOrder.LITTLE_ENDIAN);
        long offset = directory.getInt(index) & 0xffffffffL;
        long size = directory.getInt(index + 4) & 0xffffffffL;
        require(offset + size <= configBin.length,
            String.format("config item 0x%x payload is outside config.bin", itemId));
        return Arrays.copyOfRange(configBin, (int)offset, (int)(offset + size));
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private long programWord(long location) throws Exception {
        byte[] bytes = new byte[4];
        currentProgram.getMemory().getBytes(addr(location), bytes);
        return word(bytes, 0);
    }

    private byte[] read(EmulatorHelper emu, long location, int length) throws Exception {
        return emu.readMemory(addr(location), length);
    }

    private void write(EmulatorHelper emu, long location, byte[] bytes) throws Exception {
        emu.writeMemory(addr(location), bytes);
    }

    private void returnFromService(EmulatorHelper emu, long ra) throws Exception {
        require((ra & 1L) == 1L,
            String.format("MIPS16 service return address is not tagged: 0x%08x", ra));
        emu.writeRegister("pc", ra & ~1L);
    }

    private byte[] executeFactory(EmulatorHelper emu) throws Exception {
        byte[] clean = new byte[0xf0];
        write(emu, STRUCTURE, clean);
        emu.writeRegister("sp", RAM + RAM_SIZE - 0x100);
        emu.writeRegister("ra", STOP | 1L);
        emu.writeRegister("a0", STRUCTURE);
        emu.writeRegister("a1", 0);
        emu.writeRegister("a2", 0);
        emu.writeRegister("a3", 0);
        emu.writeRegister("s0", 0);
        emu.writeRegister("s1", 0);
        emu.writeRegister("pc", FACTORY);

        boolean returned = false;
        int steps = 0;
        for (; steps < 100000; steps++) {
            long pc = reg(emu, "pc") & ~1L;
            if (pc == STOP) {
                returned = true;
                break;
            }

            long a0 = reg(emu, "a0");
            long a1 = reg(emu, "a1");
            long a2 = reg(emu, "a2");
            long a3 = reg(emu, "a3");
            long ra = reg(emu, "ra");
            if (pc == (scalarGetter & ~1L)) {
                int itemId = (int)a0;
                byte[] payload = configItem(itemId);
                require(payload.length >= 2,
                    String.format("scalar item 0x%x has short payload", itemId));
                scalarCalls++;
                emu.writeRegister("v0", u16(payload, 0));
                returnFromService(emu, ra);
                continue;
            }

            if (pc == (itemGetter & ~1L)) {
                int itemId = (int)a0;
                byte[] payload = configItem(itemId);
                require(a2 == 0x0c && payload.length == a2,
                    String.format("item getter ABI/size mismatch id=0x%x len=%d payload=%d",
                        itemId, a2, payload.length));
                itemCalls++;
                lastItemBuffer = a1;
                emu.writeMemory(addr(a1), payload);
                emu.writeRegister("v0", 0);
                returnFromService(emu, ra);
                continue;
            }

            if (pc == (configCopy & ~1L)) {
                require(a2 == 8 && a3 == 0 && lastItemBuffer != 0
                        && a1 == lastItemBuffer + 4,
                    String.format("factory copy ABI differs: %08x,%08x,%08x,%08x sourceBase=%08x",
                        a0, a1, a2, a3, lastItemBuffer));
                byte[] copied = read(emu, a1, (int)a2);
                emu.writeMemory(addr(a0), copied);
                configCopyCalls++;
                emu.writeRegister("v0", 0);
                returnFromService(emu, ra);
                continue;
            }

            if (!emu.step(monitor)) {
                throw new Exception(String.format("factory stopped at 0x%08x: %s",
                    pc, emu.getLastError()));
            }
        }
        require(returned, "DAE factory exceeded its step limit");

        byte[] result = read(emu, STRUCTURE, 0xf0);
        require((result[3] & 0xff) == (configItem(0x82)[0] & 0xff),
            "factory scalar item 0x82 mismatch");
        require((result[7] & 0xff) == (configItem(0x85)[0] & 0xff),
            "factory scalar item 0x85 mismatch");
        require((result[0x0c] & 0xff) == (configItem(0x98)[0] & 0xff),
            "factory scalar item 0x98 mismatch");
        require((result[0x0d] & 0xff) == (configItem(0xa5)[0] & 0xff),
            "factory scalar item 0xa5 mismatch");
        for (int band = 0; band < 16; band++) {
            byte[] item = configItem(0x86 + band);
            require(item.length == 12, "factory band item size changed");
            require(Arrays.equals(Arrays.copyOfRange(result, 0x10 + band * 8,
                    0x18 + band * 8), Arrays.copyOfRange(item, 4, 12)),
                String.format("factory band %d does not match config item", band));
        }
        byte[] outputGain = configItem(0xa6);
        require(Arrays.equals(Arrays.copyOfRange(result, 0xba, 0xc0),
                Arrays.copyOfRange(outputGain, 4, 10)),
            "factory output-gain fields do not match config item 0xa6");
        require(scalarCalls == 4 && itemCalls == 17 && configCopyCalls == 16,
            String.format("unexpected factory API counts scalar=%d item=%d copy=%d",
                scalarCalls, itemCalls, configCopyCalls));
        println(String.format(
            "EQ DAE FACTORY PASS entry=0x%08x steps=%d config-sha256=%s scalar=%d blobs=%d copies=%d output=%s",
            FACTORY, steps, sha256(configBin), scalarCalls, itemCalls, configCopyCalls,
            sha256(result)));
        return result;
    }

    private void executeBuilder(EmulatorHelper emu, int selector, byte[] custom)
            throws Exception {
        write(emu, SELECTOR, new byte[] {(byte)selector});
        write(emu, ACTIVE, new byte[] {1});
        write(emu, CUSTOM, custom);
        builderCopyCalls = 0;
        messageCalls = 0;
        shmCalls = 0;
        emu.writeRegister("sp", RAM + RAM_SIZE - 0x100);
        emu.writeRegister("ra", STOP | 1L);
        emu.writeRegister("a0", 0);
        emu.writeRegister("a1", 0);
        emu.writeRegister("a2", 0);
        emu.writeRegister("a3", 0);
        emu.writeRegister("s0", 0);
        emu.writeRegister("s1", 0);
        emu.writeRegister("pc", BUILDER);

        boolean returned = false;
        int steps = 0;
        for (; steps < 200000; steps++) {
            long pc = reg(emu, "pc") & ~1L;
            if (pc == STOP) {
                returned = true;
                break;
            }

            long a0 = reg(emu, "a0");
            long a1 = reg(emu, "a1");
            long a2 = reg(emu, "a2");
            long a3 = reg(emu, "a3");
            long ra = reg(emu, "ra");
            if (pc == (MENGINE_COPY_STUB & ~1L)) {
                require(a1 == STRUCTURE && a2 == 0xf0,
                    "builder copy service arguments changed");
                builderCopyCalls++;
                emu.writeRegister("v0", 0);
                returnFromService(emu, ra);
                continue;
            }

            if (pc == (MENGINE_SHM_STUB & ~1L)) {
                require(a0 == 3 && a3 == 0x49,
                    String.format("builder shm ABI mismatch: %08x,%08x,%08x,%08x", a0,a1,a2,a3));
                shmCalls++;
                emu.writeRegister("v0", 0x9fc16000L);
                returnFromService(emu, ra);
                continue;
            }

            if (pc == (MENGINE_MESSAGE_STUB & ~1L)) {
                require(a1 == 0x1f && a2 == 0 && a3 == 0x49,
                    String.format("builder message ABI mismatch: %08x,%08x,%08x,%08x", a0,a1,a2,a3));
                messageCalls++;
                emu.writeRegister("v0", 0);
                returnFromService(emu, ra);
                continue;
            }

            if (!emu.step(monitor)) {
                throw new Exception(String.format("builder stopped at 0x%08x: %s",
                    pc, emu.getLastError()));
            }
        }
        require(returned, "EQ builder exceeded its step limit");
        println(String.format(
            "EQ BUILDER TRACE selector=%d steps=%d active=%02x busy=%02x ready=%02x header=%02x",
            selector, steps, read(emu, ACTIVE, 1)[0] & 0xff,
            read(emu, STRUCTURE + 0x0c, 1)[0] & 0xff,
            read(emu, STRUCTURE + 7, 1)[0] & 0xff,
            read(emu, STRUCTURE + 0x0d, 1)[0] & 0xff));
        require(builderCopyCalls == 1 && shmCalls == 1 && messageCalls == 1,
            String.format("builder service counts copy=%d shm=%d message=%d",
                builderCopyCalls, shmCalls, messageCalls));

        byte[] result = read(emu, STRUCTURE, 0xf0);
        require((result[7] & 0xff) == 1, "builder did not enable the EQ bank");
        require((result[0x0d] & 0xff) == (selector == 0 ? 0 : 1),
            "builder selector-enable byte mismatch");
        for (int band = 0; band < 7; band++) {
            int offset = 0x10 + band * 8;
            require(u16(result, offset) == FREQUENCIES[band],
                String.format("frequency mismatch in band %d", band));
            require(u16(result, offset + 2) == Q_VALUES[band],
                String.format("Q mismatch in band %d", band));
            int expectedGain = selector == 7
                ? (byte)custom[band] * 10
                : PRESET_GAINS[selector][band] * 10;
            require((short)u16(result, offset + 4) == expectedGain,
                String.format("gain mismatch in band %d", band));
            require(u16(result, offset + 6) == 1,
                String.format("band enable mismatch in band %d", band));
        }
        for (int offset = 0x48; offset < 0x90; offset++) {
            require(result[offset] == 0,
                String.format("builder failed to clear band-tail byte +0x%x", offset));
        }
        println(String.format(
            "EQ BUILDER CASE selector=%d mode=%s steps=%d gains-input=%s selector-flag=%d output=%s",
            selector, selectorLabel(selector), steps, gainCurve(result),
            result[0x0d] & 0xff, sha256(result)));
    }

    private int executeAuddev(EmulatorHelper emu) throws Exception {
        emu.writeRegister("sp", AUD_RAM + 0x4000);
        emu.writeRegister("ra", STOP | 1L);
        emu.writeRegister("a0", 1);
        emu.writeRegister("a1", STRUCTURE);
        emu.writeRegister("a2", 0);
        emu.writeRegister("a3", 0);
        emu.writeRegister("pc", AUDDEV);
        int steps = 0;
        for (; steps < 100000; steps++) {
            long pc = reg(emu, "pc") & ~1L;
            if (pc == STOP) {
                return steps;
            }
            if (pc >= AUDDEV && pc < AUDDEV + 0x600) {
                emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            }
            if (pc == AUD_COPY_STUB) {
                long destination = reg(emu, "a0");
                long source = reg(emu, "a1");
                long length = reg(emu, "a2");
                long flag = reg(emu, "a3");
                long ra = reg(emu, "ra");
                require(length == 8 && flag == 0,
                    String.format("aud_dev module-0x10 copy ABI mismatch len=%d flag=%d", length, flag));
                require(destination >= AUD_STATE + 0x94 && destination + length <= AUD_STATE + 0x114
                        && source >= STRUCTURE + 0x10 && source + length <= STRUCTURE + 0x90,
                    String.format("aud_dev copy outside EQ bank dest=%08x source=%08x", destination, source));
                byte[] copied = read(emu, source, (int)length);
                emu.writeMemory(addr(destination), copied);
                audCopyCalls++;
                emu.writeRegister("v0", 0);
                returnFromService(emu, ra);
                continue;
            }
            if (!emu.step(monitor)) {
                throw new Exception(String.format("aud_dev stopped at 0x%08x: %s",
                    pc, emu.getLastError()));
            }
        }
        throw new Exception("aud_dev entry exceeded its step limit");
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private String selectorLabel(int selector) {
        if (selector == 0) {
            return "Off";
        }

        if (selector == 7) {
            return "Custom(0)";
        }

        return "preset-" + selector;
    }

    private String gainCurve(byte[] structure) {
        StringBuilder result = new StringBuilder("[");
        for (int band = 0; band < 7; band++) {
            if (band > 0) {
                result.append(',');
            }
            short scaledGain = (short)u16(structure, 0x14 + band * 8);
            result.append(scaledGain / 10);
        }
        result.append(']');
        return result.toString();
    }

    private byte[] runCase(EmulatorHelper emu, byte[] factory, int selector)
            throws Exception {
        write(emu, STRUCTURE, factory);
        write(emu, ACTIVE, new byte[] {1});
        write(emu, STRUCTURE + 0x0c, new byte[] {0});
        write(emu, CUSTOM, new byte[7]);
        write(emu, AUD_STATE, new byte[0x200]);
        audCopyCalls = 0;
        executeBuilder(emu, selector, new byte[7]);
        byte[] built = read(emu, STRUCTURE, 0xf0);
        int auddevSteps = executeAuddev(emu);
        require(audCopyCalls == 16,
            String.format("aud_dev expected 16 band copies, observed %d", audCopyCalls));
        byte[] audState = read(emu, AUD_STATE, 0x200);
        require(Arrays.equals(Arrays.copyOfRange(audState, 0x94, 0x114),
                Arrays.copyOfRange(built, 0x10, 0x90)),
            "aud_dev band copy result differs from builder output");
        byte[] outputGain = Arrays.copyOfRange(audState, 0x50, 0x5a);
        if (selector == 0) {
            require(Arrays.equals(outputGain, new byte[10]),
                "Off unexpectedly applied output-gain group");
        }
        else {
            byte[] expectedOutputGain = new byte[10];
            System.arraycopy(factory, 0xba, expectedOutputGain, 0, 2);
            expectedOutputGain[2] = factory[0xbb];
            expectedOutputGain[3] = factory[0xbb];
            System.arraycopy(factory, 0xbc, expectedOutputGain, 4, 2);
            System.arraycopy(factory, 0xbe, expectedOutputGain, 8, 2);
            println(String.format("AUDDEV OUTPUT-GAIN TRACE selector=%d input=%s expected=%s actual=%s",
                selector, hex(Arrays.copyOfRange(factory, 0xba, 0xc0)),
                hex(expectedOutputGain), hex(outputGain)));
            require(Arrays.equals(outputGain, expectedOutputGain),
                "selector-enabled output-gain group did not expose config.bin item 0xa6 values");
        }
        println(String.format(
            "AUDDEV CASE selector=%d mode=%s steps=%d module0x10-copies=%d bands=%s output-gain-state+0x50=%s",
            selector, selectorLabel(selector), auddevSteps, audCopyCalls,
            sha256(Arrays.copyOfRange(audState, 0x94, 0x114)), hex(outputGain)));
        return audState;
    }

    private void addBlock(Memory memory, String name, long start, byte[] bytes,
            boolean executable) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            name, addr(start), new ByteArrayInputStream(bytes), bytes.length, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(executable);
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 2,
            "usage: RunEqConfigDefaultsAuddevProbe.java CONFIG.BIN AUD_DEV.DRV");
        configBin = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[1]));
        require(CONFIG_SHA256.equals(sha256(configBin)), "config.bin hash mismatch");
        require(AUDDEV_SHA256.equals(sha256(driver)), "aud_dev.drv hash mismatch");
        require(driver.length >= 0x2500, "aud_dev.drv is truncated before bank 5");

        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.createInitializedBlock("eq_config_defaults_ram", addr(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);
        addBlock(memory, "eq_auddev_bank5", AUDDEV,
            Arrays.copyOfRange(driver, 0x1f00, 0x2500), true);
        addBlock(memory, "eq_auddev_stub_ram", AUD_RAM, new byte[0x5000], true);
        addBlock(memory, "eq_auddev_state", AUD_STATE, new byte[0x200], false);

        MemoryBlock audCode = memory.getBlock(addr(AUDDEV));
        audCode.setWrite(true);
        byte[] copyPointer = new byte[] {
            (byte)(AUD_COPY_STUB | 1L), (byte)((AUD_COPY_STUB | 1L) >>> 8),
            (byte)((AUD_COPY_STUB | 1L) >>> 16), (byte)((AUD_COPY_STUB | 1L) >>> 24)
        };
        memory.setBytes(addr(0x98fc), copyPointer);
        currentProgram.getProgramContext().setValue(currentProgram.getRegister("ISA_MODE"),
            addr(AUDDEV), addr(AUDDEV + 0x5ff), BigInteger.ONE);

        scalarGetter = programWord(0x60b1101cL) & ~1L;
        itemGetter = programWord(0x60b11020L) & ~1L;
        configCopy = programWord(0x60b11024L) & ~1L;
        require(scalarGetter != 0 && itemGetter != 0 && configCopy != 0,
            "mengine factory callback pointers are empty");
        println(String.format("EQ FACTORY SERVICE TARGETS scalar=0x%08x item=0x%08x copy=0x%08x",
            scalarGetter, itemGetter, configCopy));

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            byte[] factory = executeFactory(emu);
            byte[] offState = runCase(emu, factory, 0);
            byte[] customState = runCase(emu, factory, 7);
            byte[] offBands = Arrays.copyOfRange(offState, 0x94, 0x114);
            byte[] customBands = Arrays.copyOfRange(customState, 0x94, 0x114);
            byte[] offGain = Arrays.copyOfRange(offState, 0x50, 0x5a);
            byte[] customGain = Arrays.copyOfRange(customState, 0x50, 0x5a);
            require(Arrays.equals(offBands, customBands),
                "Off and Custom(0) aud_dev EQ-band copies differ");
            println(String.format(
                "EQ OFF VS CUSTOM0 RESULT bands-identical=%s output-gain-identical=%s off-gain=%s custom0-gain=%s service-boundaries=config-bin-getter+memcpy,module0x10-memcpy,message/shm-stubs",
                Arrays.equals(offBands, customBands), Arrays.equals(offGain, customGain),
                hex(offGain), hex(customGain)));

            for (int selector = 1; selector <= 6; selector++) {
                runCase(emu, factory, selector);
            }

            println("EQ PRESET MATRIX PASS selectors=1..6 gains-source=RunEqBuilderProbe.GAINS 96khz-gate=not-run");
            println("EQ CONFIG DEFAULTS -> BUILDER -> AUDDEV P-CODE PASS real-mips16=factory,builder,aud_dev synthetic-services=true dsp-output=unverified");
        }
        finally {
            emu.dispose();
        }
    }
}

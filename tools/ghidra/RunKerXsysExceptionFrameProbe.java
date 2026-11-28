// Execute the real MIPS16 frame formatter with a synthetic ROM callback.
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RunKerXsysExceptionFrameProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc344f4L;
    private static final long CALLBACK_SLOT = 0xbfc34558L;
    private static final long CALLBACK = 0x00070000L;
    private static final long CALLBACK_TAGGED = CALLBACK | 1L;
    private static final long FRAME = 0x00013dccL;
    private static final long STATUS = 0x9fc1aa5cL;
    private static final long MMIO_REGISTER = 0xc012001cL;
    private static final long STACK_BASE = 0x9fc1b000L;
    private static final int STACK_SIZE = 0x2000;
    private static final long[] WORDS = {
        0x11223344L, 0x55667788L, 0x99aabbccL, 0xddeeff00L, 0x13579bdfL
    };

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void putWord(Memory memory, long at, long value) throws Exception {
        memory.setBytes(addr(at), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private long readWord(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private void seedCalleeSavedRegisters(EmulatorHelper emu) throws Exception {
        for (int index = 0; index < 8; index++) {
            emu.writeRegister("s" + index, 0x51510000L + index);
        }
        emu.writeRegister("s8", 0x51510008L);
    }

    private long readWord(EmulatorHelper emu, long at) throws Exception {
        byte[] bytes = emu.readMemory(addr(at), 4);
        return (bytes[0] & 0xffL) | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16) | ((bytes[3] & 0xffL) << 24);
    }

    private MemoryBlock mapRam(Memory memory, String name, long base, int size)
            throws Exception {
        MemoryBlock block = memory.createInitializedBlock(name, addr(base),
            new ByteArrayInputStream(new byte[size]), size, monitor, false);
        block.setRead(true);
        block.setWrite(true);
        return block;
    }

    private void runProfile(Memory memory, long status, long expectedMmio)
            throws Exception {
        for (int i = 0; i < WORDS.length; i++) putWord(memory, FRAME + i * 4L, WORDS[i]);
        putWord(memory, 0x00013dc8L, 0xcafebabeL);
        putWord(memory, 0x00013de0L, 0x0badf00dL);
        putWord(memory, STATUS, status);
        putWord(memory, MMIO_REGISTER, 0);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            seedCalleeSavedRegisters(emu);
            emu.writeRegister("sp", STACK_BASE + 0x1000);
            emu.writeRegister("ra", 0x80001235L);
            emu.writeRegister("pc", ENTRY);

            List<Long> observed = new ArrayList<>();
            boolean mmioWrite = false;
            for (int step = 0; step < 256; step++) {
                long pc = reg(emu, "pc");
                if (pc == CALLBACK) {
                    observed.add(reg(emu, "a1"));
                    require(reg(emu, "a2") == 2, "unexpected frame formatter width argument");
                    require(reg(emu, "a0") == 0xbfc3454cL,
                        "unexpected frame formatter descriptor argument");
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
                }
                if (observed.size() == WORDS.length
                        && readWord(emu, MMIO_REGISTER) == expectedMmio) {
                    mmioWrite = true;
                    break;
                }
            }

            require(observed.size() == WORDS.length, "did not observe five frame formatter calls");
            for (int i = 0; i < WORDS.length; i++) {
                require(observed.get(i) == WORDS[i], String.format(
                    "frame word %d mismatch: expected 0x%08x got 0x%08x",
                    i, WORDS[i], observed.get(i)));
            }
            require(mmioWrite, String.format(
                "expected MMIO write 0x%08x, observed 0x%08x",
                expectedMmio, readWord(emu, MMIO_REGISTER)));
            require(readWord(emu, 0x00013dc8L) == 0xcafebabeL,
                "pre-frame canary changed");
            require(readWord(emu, 0x00013de0L) == 0x0badf00dL,
                "post-frame canary changed");
            println(String.format(
                "KER XSYS EXCEPTION FRAME PROFILE PASS state=0x%08x frame-words=%d mmio-write=0x%02x",
                status, observed.size(), expectedMmio));
        }
        finally {
            emu.dispose();
        }
    }

    private MemoryBlock mapMips16Stub(Memory memory, long base, byte[] code, String name)
            throws Exception {
        MemoryBlock block = mapRam(memory, name, base, code.length);
        memory.setBytes(addr(base), code);
        block.setExecute(true);
        Register isa = currentProgram.getProgramContext().getRegister("ISA_MODE");
        currentProgram.getProgramContext().setValue(isa, addr(base), addr(base + code.length - 1),
            BigInteger.ONE);
        require(disassemble(addr(base)), "could not disassemble " + name);
        return block;
    }

    private MemoryBlock mapMips32Stub(Memory memory, long base, byte[] code, String name)
            throws Exception {
        MemoryBlock block = mapRam(memory, name, base, code.length);
        memory.setBytes(addr(base), code);
        block.setExecute(true);
        Register isa = currentProgram.getProgramContext().getRegister("ISA_MODE");
        currentProgram.getProgramContext().setValue(isa, addr(base), addr(base + code.length - 1),
            BigInteger.ZERO);
        require(disassemble(addr(base)), "could not disassemble " + name);
        return block;
    }

    private MemoryBlock mapMips32ConstantReturnStub(Memory memory, long base, int value,
            String name) throws Exception {
        return mapMips32Stub(memory, base, new byte[] {
            (byte)value, (byte)(value >>> 8), 0x02, 0x24, // addiu v0,zero,imm16
            0x08, 0x00, (byte)0xe0, 0x03, // jr ra
            0x00, 0x00, 0x00, 0x00 // delay slot
        }, name);
    }

    private void runSyntheticStackGuardFailure(Memory memory) throws Exception {
        mapRam(memory, "stack_guard_low_ram", 0x80L, 0x4000);
        mapRam(memory, "stack_guard_callback_state", 0x9fc1a900L, 0x100);
        mapMips16Stub(memory, 0xbfc196d0L,
            new byte[] {0x02, 0x6a, (byte)0xa0, (byte)0xe8}, "synthetic_rom_classify");
        mapMips32ConstantReturnStub(memory, 0x00070200L, 0x200,
            "synthetic_rom_stack_value_sentinel");
        mapMips32ConstantReturnStub(memory, 0x00070300L, 0x113,
            "synthetic_rom_stack_value_below_threshold");
        mapMips32ConstantReturnStub(memory, 0x00070400L, 0x114,
            "synthetic_rom_stack_value_at_threshold");

        // The distance is 0x180, so this profile fails only the guard-value check.
        putWord(memory, 0xbfc341e4L, 0x00070200L);
        putWord(memory, 0xbfc341ecL, 0x00001000L);
        putWord(memory, 0xbfc341f4L, 0x9fc1a9e8L);
        putWord(memory, 0xbfc341f8L, CALLBACK_TAGGED);
        putWord(memory, 0x00001010L, 0x00000080L);
        putWord(memory, 0x00001014L, 0);
        putWord(memory, 0x00000080L, 0);
        putWord(memory, 0x9fc1a9e8L, 0xffffffffL);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            seedCalleeSavedRegisters(emu);
            emu.writeRegister("sp", STACK_BASE + 0x1000);
            emu.writeRegister("a0", 0x12345678L);
            emu.writeRegister("a1", 0x55L);
            emu.writeRegister("ra", 0x80001235L);
            emu.writeRegister("pc", 0xbfc3416cL);

            long[] observedA0 = new long[2];
            long[] observedA1 = new long[2];
            long[] observedA2 = new long[2];
            int callbacks = 0;
            boolean reachedDefaultHandler = false;
            for (int step = 0; step < 256; step++) {
                long pc = reg(emu, "pc");
                if (pc == CALLBACK) {
                    require(callbacks < 2, "unexpected extra stack diagnostic callback");
                    observedA0[callbacks] = reg(emu, "a0");
                    observedA1[callbacks] = reg(emu, "a1");
                    observedA2[callbacks] = reg(emu, "a2");
                    callbacks++;
                }
                if (pc == 0xbfc19a74L) {
                    reachedDefaultHandler = true;
                    break;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("stack guard path stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
                }
            }

            require(reachedDefaultHandler, "stack guard path did not reach default ROM handler");
            require(callbacks == 2, "stack guard path did not issue two diagnostic callbacks");
            require(observedA0[0] == 0xbfc341d0L && observedA1[0] == 0x200
                    && observedA2[0] == 2,
                "unexpected stack-pointer diagnostic callback arguments");
            require(observedA0[1] == 0xbfc341d4L && observedA1[1] == 0x55
                    && observedA2[1] == 2,
                "unexpected stack-overflow diagnostic callback arguments");
            require(readWord(emu, 0x9fc1a9e8L) == 0xfffffeffL,
                "stack-guard failure did not clear its observed flag bit");
            println(String.format(
                "KER XSYS STACK GUARD SENTINEL P-CODE PASS callbacks=%d first=(0x%08x,0x%08x,2) second=(0x%08x,0x%08x,2) handler=0x%08x distance=0x180 synthetic-rom-services=1",
                callbacks, observedA0[0], observedA1[0], observedA0[1], observedA1[1],
                0xbfc19a74L));
        }
        finally {
            emu.dispose();
        }
    }

    private void runSyntheticStackGuardBoundary(Memory memory, long stackValue,
            long serviceStub, boolean expectFailure) throws Exception {
        putWord(memory, 0xbfc341e4L, serviceStub);
        putWord(memory, 0xbfc341ecL, 0x00001000L);
        putWord(memory, 0xbfc341f4L, 0x9fc1a9e8L);
        putWord(memory, 0xbfc341f8L, CALLBACK_TAGGED);
        putWord(memory, 0x00001010L, 0x00000080L);
        putWord(memory, 0x00001014L, 0);
        putWord(memory, 0x00000080L, 0x5a5a5a5aL);
        putWord(memory, 0x9fc1a9e8L, 0xffffffffL);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            seedCalleeSavedRegisters(emu);
            emu.writeRegister("sp", STACK_BASE + 0x1000);
            emu.writeRegister("a0", 0x12345678L);
            emu.writeRegister("a1", 0x55L);
            emu.writeRegister("ra", 0x80001235L);
            emu.writeRegister("pc", 0xbfc3416cL);

            int callbacks = 0;
            boolean returned = false;
            boolean reachedDefaultHandler = false;
            for (int step = 0; step < 256; step++) {
                long pc = reg(emu, "pc");
                if (pc == CALLBACK) callbacks++;
                if (pc == 0xbfc19a74L) {
                    reachedDefaultHandler = true;
                    break;
                }
                if ((pc & ~1L) == 0x80001234L) {
                    returned = true;
                    break;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("stack guard success path stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
                }
            }

            if (expectFailure) {
                require(!returned && reachedDefaultHandler,
                    "below-threshold guard profile did not reach the default handler");
                require(callbacks == 2, "below-threshold guard profile missed diagnostic calls");
                require(readWord(emu, 0x9fc1a9e8L) == 0xfffffeffL,
                    "below-threshold guard profile did not clear its state bit");
                println(String.format(
                    "KER XSYS STACK GUARD THRESHOLD P-CODE PASS stack=0x%08x distance=0x%03x outcome=fail callbacks=%d synthetic-rom-services=1",
                    stackValue, stackValue - 0x80L, callbacks));
            }
            else {
                require(returned && !reachedDefaultHandler,
                    "at-threshold guard profile did not return normally");
                require(callbacks == 0, "at-threshold guard profile issued a diagnostic callback");
                require(readWord(emu, 0x9fc1a9e8L) == 0xffffffffL,
                    "at-threshold guard profile changed its state flag");
                println(String.format(
                    "KER XSYS STACK GUARD THRESHOLD P-CODE PASS stack=0x%08x distance=0x%03x outcome=pass callbacks=%d synthetic-rom-services=1",
                    stackValue, stackValue - 0x80L, callbacks));
            }
        }
        finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1, "usage: RunKerXsysExceptionFrameProbe.java XSYS");
        byte[] input = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        require(input.length == 0x800, "unexpected ker_xsys.bin size");
        require(readWord(input, 0x1f8) == 0xbfc04841L,
            "stack diagnostic callback literal changed");
        require(new String(input, 0x1d0, 4, StandardCharsets.US_ASCII).equals("sp \0")
                && new String(input, 0x1d4, 13, StandardCharsets.US_ASCII)
                    .equals("stk overflow\0"),
            "stack diagnostic strings changed");
        require(readWord(input, 0x1e8) == 0x0003ffffL,
            "stack-check mask literal changed");
        require(readWord(input, 0x1f0) == 0x5a5a5a5aL,
            "stack guard sentinel literal changed");
        Memory memory = currentProgram.getMemory();
        MemoryBlock xsys = memory.getBlock(addr(ENTRY));
        require(xsys != null, "ker_xsys segment is not mapped");
        xsys.setWrite(true);

        mapRam(memory, "exception_frame_probe_ram", 0x00013d00L, 0x100);
        mapRam(memory, "exception_frame_probe_stack", STACK_BASE, STACK_SIZE);
        mapRam(memory, "exception_frame_probe_state", 0x9fc1aa00L, 0x100);
        mapRam(memory, "mmio_probe", 0xc0120000L, 0x100);
        MemoryBlock callback = mapRam(memory, "synthetic_rom_callback", CALLBACK, 2);
        memory.setBytes(addr(CALLBACK), new byte[] {(byte)0xa0, (byte)0xe8}); // jrc ra
        callback.setExecute(true);
        Register isa = currentProgram.getProgramContext().getRegister("ISA_MODE");
        currentProgram.getProgramContext().setValue(isa, addr(CALLBACK), addr(CALLBACK + 1),
            BigInteger.ONE);
        require(disassemble(addr(ENTRY)), "could not disassemble MIPS16 frame function");
        require(createFunction(addr(ENTRY), null) != null,
            "could not create MIPS16 frame function");
        require(disassemble(addr(CALLBACK)), "could not disassemble synthetic callback");

        putWord(memory, CALLBACK_SLOT, CALLBACK_TAGGED);
        runProfile(memory, 0, 0x5d);
        runProfile(memory, 2, 0x69);
        runSyntheticStackGuardFailure(memory);
        runSyntheticStackGuardBoundary(memory, 0x113L, 0x00070300L, true);
        runSyntheticStackGuardBoundary(memory, 0x114L, 0x00070400L, false);
        println(String.format(
            "KER XSYS EXCEPTION FRAME P-CODE PASS entry=0x%08x frame=0x%08x profiles=2 callback=0x%08x synthetic-rom-callback=1",
            ENTRY, FRAME, CALLBACK_TAGGED));
    }
}

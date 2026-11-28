// Execute the real MIPS16 ADC classifier against a caller-supplied RAM table.
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

public class RunKeyClassifierMatrix extends GhidraScript {
    private static final long ENTRY = 0xbfc10178L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long KEY_BSS = 0x9fc14328L;
    private static final long NORMALIZED_TABLE = KEY_BSS + 0x0c;
    private static final long MMIO = 0xc0020000L;
    private static final long ADC_SAMPLE = MMIO + 0x28;
    private static final long OVERRIDE_FLAG = MMIO + 0x54;
    private static final long OUTPUT = RAM + 0x230;
    private static final long STACK = RAM + 0x3000;
    private static final int OVERRIDE_VALUE = 0x7e;
    private static final int[] NORMALIZED_VALUES = {
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77
    };

    private Address addr(long value) throws Exception {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void putByte(Memory memory, long address, int value) throws Exception {
        memory.setBytes(addr(address), new byte[] {(byte)value});
    }

    private void putWord(Memory memory, long address, long value) throws Exception {
        memory.setBytes(addr(address), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private void expectWord(Memory memory, long address, long expected) throws Exception {
        byte[] bytes = new byte[4];
        memory.getBytes(addr(address), bytes);
        long actual = (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
        if (actual != (expected & 0xffffffffL)) {
            throw new Exception(String.format(
                "key literal at 0x%08x: expected 0x%08x, got 0x%08x",
                address,
                expected,
                actual
            ));
        }
    }

    private long readRegister(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private void initializeInputs(Memory memory, int sample, int overrideFlag) throws Exception {
        memory.setBytes(addr(RAM), new byte[0x4000]);
        memory.setBytes(addr(KEY_BSS), new byte[0xd6]);
        memory.setBytes(addr(MMIO), new byte[0x1000]);
        putByte(memory, ADC_SAMPLE, sample);
        putByte(memory, NORMALIZED_TABLE + 8, OVERRIDE_VALUE);
        for (int index = 0; index < NORMALIZED_VALUES.length; index++) {
            putByte(memory, NORMALIZED_TABLE + 9 + index, NORMALIZED_VALUES[index]);
        }
        putWord(memory, OVERRIDE_FLAG, overrideFlag);
        putByte(memory, OUTPUT, 0xff);
    }

    private void runCase(Memory memory, Register isa, int sample, int overrideFlag) throws Exception {
        initializeInputs(memory, sample, overrideFlag);
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", OUTPUT);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 1000; steps++) {
                long pc = readRegister(emu, "pc");
                if ((pc & ~1L) == STOP) {
                    int result = (int)(readRegister(emu, "v0") & 0xffL);
                    println(String.format(
                        "CASE sample=%02x override=%d result=%02x",
                        sample,
                        overrideFlag,
                        result
                    ));
                    return;
                }

                if (!emu.step(monitor)) {
                    throw new Exception(String.format(
                        "classifier stopped for sample 0x%02x at 0x%08x: %s",
                        sample,
                        pc,
                        emu.getLastError()
                    ));
                }
            }

            throw new Exception(String.format("classifier exceeded step limit for 0x%02x", sample));
        }
        finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 0) {
            throw new Exception("RunKeyClassifierMatrix.java takes no arguments");
        }

        Memory memory = currentProgram.getMemory();
        if (memory.getBlock(addr(ENTRY)) == null) {
            throw new Exception("key.drv code block is not mapped");
        }

        MemoryBlock ram = memory.createInitializedBlock(
            "key_classifier_matrix_ram",
            addr(RAM),
            new ByteArrayInputStream(new byte[0x4000]),
            0x4000,
            monitor,
            false
        );
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        MemoryBlock bss = memory.createInitializedBlock(
            "key_classifier_matrix_bss",
            addr(KEY_BSS),
            new ByteArrayInputStream(new byte[0xd6]),
            0xd6,
            monitor,
            false
        );
        bss.setRead(true);
        bss.setWrite(true);

        MemoryBlock mmio = memory.createInitializedBlock(
            "key_classifier_matrix_mmio",
            addr(MMIO),
            new ByteArrayInputStream(new byte[0x1000]),
            0x1000,
            monitor,
            false
        );
        mmio.setRead(true);
        mmio.setWrite(true);

        expectWord(memory, 0xbfc101c0L, 0xc0020028L);
        expectWord(memory, 0xbfc101c4L, 0xbfc103c0L);
        expectWord(memory, 0xbfc101c8L, 0x9fc14334L);
        expectWord(memory, 0xbfc101ccL, 0xc0020054L);
        expectWord(memory, 0xbfc103c0L, 0x3a2a1803L);
        expectWord(memory, 0xbfc103c4L, 0x006f5f4aL);

        Register isa = currentProgram.getRegister("ISA_MODE");
        for (int overrideFlag = 0; overrideFlag <= 1; overrideFlag++) {
            for (int sample = 0; sample <= 0xff; sample++) {
                runCase(memory, isa, sample, overrideFlag);
            }
        }

        println("KEY CLASSIFIER MIPS16 MATRIX PASS cases=512 thresholds=7 override-register=0xc0020054 bits=0,1");
    }
}

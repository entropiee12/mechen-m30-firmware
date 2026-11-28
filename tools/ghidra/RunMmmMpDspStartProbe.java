import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Execute mmm_mp.al's PLAY-time DSP choice with synthetic external services. */
public class RunMmmMpDspStartProbe extends GhidraScript {
    private static final long ENTRY = 0x68312d00L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc00000L;
    private static final int RAM_SIZE = 0x20000;
    private static final long CONTEXT = 0x9fc18000L;
    private static final long SHM = 0x9fc1a400L;
    private static final long SHM_CELL = 0x9fc13b04L;
    private static final long COPY_STUB = 0xbfc13948L;
    private static final long OPEN_STUB = 0xbfc1394cL;
    private static final long AUDIO_DEVICE_STUB = 0xbfc13944L;
    private static final long DECODER_STUB = 0xbfc13940L;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private long reg(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private void word(EmulatorHelper emulator, long at, long value) throws Exception {
        emulator.writeMemory(addr(at), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private long programWord(long at) throws Exception {
        byte[] bytes = new byte[4];
        currentProgram.getMemory().getBytes(addr(at), bytes);
        return (bytes[0] & 255L) | ((bytes[1] & 255L) << 8)
            | ((bytes[2] & 255L) << 16) | ((bytes[3] & 255L) << 24);
    }

    private String cString(EmulatorHelper emulator, long at) throws Exception {
        StringBuilder name = new StringBuilder();
        for (int index = 0; index < 64; index++) {
            int value = emulator.readMemory(addr(at + index), 1)[0] & 255;
            if (value == 0) {
                return name.toString();
            }
            name.append((char)value);
        }
        throw new Exception("unterminated DSP filename at 0x" + Long.toHexString(at));
    }

    private void returnFromStub(EmulatorHelper emulator, long value) throws Exception {
        long ra = reg(emulator, "ra");
        require((ra & 1L) == 1L, "external call returned to untagged MIPS16 address");
        emulator.writeRegister("v0", value);
        emulator.writeRegister("pc", ra & ~1L);
    }

    private void runCase(int processingFlag, int sampleRate) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> openedNames = new ArrayList<>();
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            word(emulator, SHM_CELL, SHM);
            emulator.writeMemory(addr(SHM + 2), new byte[] {(byte)processingFlag});
            word(emulator, CONTEXT + 0x80, 2);
            word(emulator, CONTEXT + 0x90, sampleRate);
            emulator.writeRegister("sp", RAM + RAM_SIZE - 0x100);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", CONTEXT);
            emulator.writeRegister("pc", ENTRY);

            boolean returned = false;
            for (int step = 0; step < 30000; step++) {
                long pc = reg(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    returned = true;
                    break;
                }
                if (pc == COPY_STUB) {
                    long a0 = reg(emulator, "a0");
                    long a1 = reg(emulator, "a1");
                    long a2 = reg(emulator, "a2");
                    long a3 = reg(emulator, "a3");
                    require(a1 == 0 && a2 == 0x20 && a3 == 1,
                        String.format("unexpected copy/fill service: %08x,%08x,%08x,%08x", a0,a1,a2,a3));
                    emulator.writeMemory(addr(a0), new byte[(int)a2]);
                    returnFromStub(emulator, 0);
                    continue;
                }
                if (pc == OPEN_STUB) {
                    long a0 = reg(emulator, "a0");
                    long a1 = reg(emulator, "a1");
                    long a3 = reg(emulator, "a3");
                    if (a3 == 3) {
                        openedNames.add(cString(emulator, a0));
                        require(a1 == openedNames.size() - 1,
                            "unexpected DSP open order at " + openedNames);
                        returnFromStub(emulator, 0x1100 + openedNames.size());
                    } else {
                        require(a3 == 0x14 && a0 == 0x1d,
                            String.format("unexpected file service: %08x,%08x,%08x", a0,a1,a3));
                        returnFromStub(emulator, 0);
                    }
                    continue;
                }
                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emulator.getLastError()));
                }
            }
            require(returned, "DSP start did not return");
            require(openedNames.size() == 2, "expected two DSP opens: " + openedNames);
            String expected = processingFlag == 0 ? "adppext.dsp"
                : sampleRate <= 48000 ? "adppdrc.dsp" : "audiopp.dsp";
            require(openedNames.get(1).equals(expected),
                String.format("flag=%d rate=%d got %s expected %s",
                    processingFlag, sampleRate, openedNames.get(1), expected));
            println(String.format("MMM_MP DSP CASE flag=%d rate=%d decoder=%s second=%s",
                processingFlag, sampleRate, openedNames.get(0), openedNames.get(1)));
        } finally {
            emulator.dispose();
        }
    }

    private void runEqCommand() throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            word(emulator, SHM_CELL, SHM);
            emulator.writeRegister("sp", RAM + RAM_SIZE - 0x100);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", CONTEXT);
            emulator.writeRegister("a1", 0x1f);
            emulator.writeRegister("a2", SHM);
            emulator.writeRegister("pc", 0x68092d00L);
            int deviceCalls = 0;
            boolean returned = false;
            for (int step = 0; step < 3000; step++) {
                long pc = reg(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    returned = true;
                    break;
                }
                if (pc == AUDIO_DEVICE_STUB) {
                    require(reg(emulator, "a0") == 1 && reg(emulator, "a1") == SHM
                            && reg(emulator, "a2") == 0 && reg(emulator, "a3") == 0x12,
                        "EQ command did not pass the shared pointer to aud_dev op 0x12");
                    deviceCalls++;
                    returnFromStub(emulator, 0);
                    continue;
                }
                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("EQ command stopped at 0x%08x: %s",
                        pc, emulator.getLastError()));
                }
            }
            require(returned && deviceCalls == 1, "EQ command did not reach aud_dev exactly once");
            println(String.format("MMM_MP EQ COMMAND P-CODE PASS command=0x1f auddev=(1,0x%08x,0,0x12) synthetic=SHM-cell,module-return",
                SHM));
        } finally {
            emulator.dispose();
        }
    }

    private void runPlayDispatch() throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emulator.writeRegister("sp", RAM + RAM_SIZE - 0x100);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", CONTEXT);
            emulator.writeRegister("a1", 0x19);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("pc", 0x68092d00L);
            for (int step = 0; step < 3000; step++) {
                long pc = reg(emulator, "pc") & ~1L;
                if (pc == 0x68012da0L) {
                    require(reg(emulator, "a0") == CONTEXT,
                        "PLAY dispatch did not pass the decoder context");
                    println(String.format("MMM_MP PLAY DISPATCH P-CODE PASS command=0x19 target=0x%08x context=0x%08x",
                        pc, CONTEXT));
                    return;
                }
                if (pc == STOP) {
                    throw new Exception("PLAY dispatch returned before the PLAY handler");
                }
                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("PLAY dispatch stopped at 0x%08x: %s",
                        pc, emulator.getLastError()));
                }
            }
            throw new Exception("PLAY dispatch exceeded step limit");
        } finally {
            emulator.dispose();
        }
    }

    private void runPlayHandlerDispatch() throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> serviceCalls = new ArrayList<>();
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            word(emulator, CONTEXT + 0x80, 2);
            word(emulator, CONTEXT + 400, 2);
            word(emulator, CONTEXT + 0x8c, 0x30);
            word(emulator, CONTEXT + 0x84, 1);
            word(emulator, CONTEXT + 0x90, 44100);
            emulator.writeRegister("sp", RAM + RAM_SIZE - 0x100);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", CONTEXT);
            emulator.writeRegister("pc", 0x68012da0L);
            for (int step = 0; step < 10000; step++) {
                long pc = reg(emulator, "pc") & ~1L;
                if (pc == ENTRY) {
                    require(reg(emulator, "a0") == CONTEXT,
                        "PLAY handler did not pass context to DSP start");
                    println(String.format("MMM_MP PLAY HANDLER P-CODE PASS target=0x%08x context=0x%08x services=%s",
                        pc, CONTEXT, serviceCalls));
                    return;
                }
                if (pc == AUDIO_DEVICE_STUB || pc == DECODER_STUB) {
                    serviceCalls.add(String.format("%08x:%02x", pc, reg(emulator, "a3")));
                    returnFromStub(emulator, 0);
                    continue;
                }
                if (pc == STOP) {
                    throw new Exception("PLAY handler returned before DSP start");
                }
                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("PLAY handler stopped at 0x%08x: %s",
                        pc, emulator.getLastError()));
                }
            }
            throw new Exception("PLAY handler exceeded step limit");
        } finally {
            emulator.dispose();
        }
    }

    @Override public void run() throws Exception {
        require(programWord(0x68312e18L) == 0xbfc1394dL, "open stub pool changed");
        require(programWord(0x68312e20L) == SHM_CELL, "SHM pointer cell changed");
        require(programWord(0x68113300L) == SHM_CELL, "EQ command SHM cell changed");
        require(programWord(0x68113304L) == AUDIO_DEVICE_STUB + 1,
            "EQ command aud_dev stub changed");
        Memory memory = currentProgram.getMemory();
        memory.createInitializedBlock("dsp_probe_ram", addr(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false)
            .setWrite(true);
        memory.createInitializedBlock("dsp_probe_mmio0", addr(0xc0000000L),
            new ByteArrayInputStream(new byte[0x2000]), 0x2000, monitor, false)
            .setWrite(true);
        memory.createInitializedBlock("dsp_probe_mmio1", addr(0xc00b0000L),
            new ByteArrayInputStream(new byte[0x100]), 0x100, monitor, false)
            .setWrite(true);
        runEqCommand();
        runPlayDispatch();
        runPlayHandlerDispatch();
        for (int flag : new int[] {0, 1}) {
            for (int rate : new int[] {44100, 48000, 48001, 96000}) {
                runCase(flag, rate);
            }
        }
        println("MMM_MP DSP START P-CODE PASS cases=8 synthetic=fill,open,MMIO,RAM");
    }
}

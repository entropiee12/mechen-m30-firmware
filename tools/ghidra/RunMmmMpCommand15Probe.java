import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.Arrays;

/** Replay the mmm_mp command-0x15/0x16 p-code paths with a synthetic service shim. */
public class RunMmmMpCommand15Probe extends GhidraScript {
    private static final long ENTRY = 0xbfc13864L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc00000L;
    private static final int RAM_SIZE = 0x30000;
    private static final long STACK = RAM + RAM_SIZE - 0x1000;
    private static final long CONTEXT = RAM + 0x4000;
    private static final long INPUT = RAM + 0x1000;
    private static final long OUTPUT = RAM + 0x2000;
    private static final long HANDLE = RAM + 0x3000;
    private static final long COPY_SERVICE = 0xbfc13948L;
    private static final int PAYLOAD_SIZE = 0x0c;
    private static final int MAX_STEPS = 10000;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private byte[] bytes(EmulatorHelper emu, long at, int length) throws Exception {
        return emu.readMemory(addr(at), length);
    }

    private long word(EmulatorHelper emu, long at) throws Exception {
        byte[] b = bytes(emu, at, 4);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private long programWord(long at) throws Exception {
        byte[] b = new byte[4];
        currentProgram.getMemory().getBytes(addr(at), b);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private byte[] pattern(int seed) {
        byte[] b = new byte[PAYLOAD_SIZE];
        for (int i = 0; i < b.length; i++) b[i] = (byte)(seed + i * 17);
        return b;
    }

    private void runCase(long command, byte[] seed, byte[] expectedSource,
                         String label) throws Exception {
        Memory memory = currentProgram.getMemory();
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int steps = 0;
        int lockCalls = 0;
        int unlockCalls = 0;
        int copyCalls = 0;
        boolean returned = false;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            memory.setBytes(addr(CONTEXT), new byte[0x200]);
            memory.setBytes(addr(CONTEXT + 0x178), new byte[] {
                (byte)HANDLE, (byte)(HANDLE >>> 8), (byte)(HANDLE >>> 16), (byte)(HANDLE >>> 24)
            });
            memory.setBytes(addr(CONTEXT + 0x12c), expectedSource);
            memory.setBytes(addr(CONTEXT + 0x104), new byte[] {0x55, 0x55, 0x55, 0x55});
            memory.setBytes(addr(INPUT), seed);
            memory.setBytes(addr(OUTPUT), new byte[PAYLOAD_SIZE]);

            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", CONTEXT);
            emu.writeRegister("a1", command);
            emu.writeRegister("a2", command == 0x15 ? INPUT : OUTPUT);
            emu.writeRegister("a3", 0);
            for (int i = 0; i <= 7; i++) emu.writeRegister("s" + i, 0);
            for (int i = 0; i <= 9; i++) emu.writeRegister("t" + i, 0);
            emu.writeRegister("v0", 0);
            emu.writeRegister("v1", 0);
            emu.writeRegister("pc", ENTRY);

            for (steps = 0; steps < MAX_STEPS; steps++) {
                long pc = reg(emu, "pc");
                long target = pc & ~1L;
                if (target == STOP) {
                    returned = true;
                    break;
                }
                if (target == COPY_SERVICE) {
                    long a0 = reg(emu, "a0"), a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2"), a3 = reg(emu, "a3");
                    long ra = reg(emu, "ra");
                    if (a3 == 0x19 || a3 == 0x1a) {
                        require(a0 == HANDLE && a1 == 0 && a2 == 0,
                            String.format("unexpected lock boundary args %08x,%08x,%08x,%08x", a0,a1,a2,a3));
                        if (a3 == 0x19) lockCalls++; else unlockCalls++;
                    } else if (a3 == 0) {
                        require(a2 == PAYLOAD_SIZE,
                            String.format("unexpected copy length %08x", a2));
                        byte[] copySource = bytes(emu, a1, PAYLOAD_SIZE);
                        if (command == 0x15) {
                            require(a0 == CONTEXT + 0x12c && a1 == INPUT,
                                String.format("unexpected command-0x15 copy %08x <- %08x",a0,a1));
                        } else {
                            require(a0 == OUTPUT && a1 == CONTEXT + 0x12c,
                                String.format("unexpected command-0x16 copy %08x <- %08x",a0,a1));
                        }
                        emu.writeMemory(addr(a0), copySource);
                        copyCalls++;
                    } else {
                        throw new Exception(String.format("unexpected module service op 0x%08x", a3));
                    }
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
            }

            require(returned, "mmm_mp command did not return before step limit");
            require(lockCalls == 1 && unlockCalls == 1,
                "expected one lock and one unlock around command dispatch");
            require(copyCalls == 1, "expected exactly one 12-byte copy");
            require(reg(emu, "v0") == 0, "command dispatcher returned unexpected status");
            if (command == 0x15) {
                require(Arrays.equals(bytes(emu, CONTEXT + 0x12c, PAYLOAD_SIZE), seed),
                    "command-0x15 payload was not copied into context");
                require(word(emu, CONTEXT + 0x104) == word(emu, CONTEXT + 0x12c),
                    "command-0x15 mirror field does not match the copied first word");
            } else {
                require(Arrays.equals(bytes(emu, OUTPUT, PAYLOAD_SIZE), expectedSource),
                    "command-0x16 payload was not copied out of context");
                require(bytes(emu, CONTEXT + 0x12c, PAYLOAD_SIZE)[0] == expectedSource[0],
                    "command-0x16 unexpectedly changed its source context");
            }
            println(String.format("MMM_MP COMMAND %02x PASS steps=%d payload_bytes=%d copy_calls=%d mirror=%08x service=synthetic case=%s",
                command, steps, PAYLOAD_SIZE, copyCalls, word(emu, CONTEXT + 0x104), label));
        } finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(programWord(0xbfc13938L) == 0x68012d5dL,
            "mmm_mp command 0x13 handler pointer changed");
        require(programWord(0xbfc1393cL) == 0x68092d01L,
            "mmm_mp default command handler pointer changed");
        require(programWord(0x68092f04L) == 0xbfc13949L,
            "mmm_mp resident copy/lock service pointer changed");

        MemoryBlock ram = currentProgram.getMemory().createInitializedBlock("mmm_mp_command_probe_ram",
            addr(RAM), new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        byte[] setPayload = pattern(0x27);
        byte[] getPayload = pattern(0x91);
        runCase(0x15, setPayload, new byte[PAYLOAD_SIZE], "set-candidate");
        runCase(0x16, new byte[PAYLOAD_SIZE], getPayload, "get-candidate");
        println("MMM_MP COMMAND 0x15/0x16 PAIR PASS meaning=unresolved resident_copy=synthetic");
    }
}

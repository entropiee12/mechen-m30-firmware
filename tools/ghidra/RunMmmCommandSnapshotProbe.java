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
import java.util.ArrayList;
import java.util.List;

/** Execute the real mmm_mp.al 0x1c snapshot handler with an intercepted OS boundary. */
public class RunMmmCommandSnapshotProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc13864L;
    private static final long SYSCALL_STUB = 0xbfc13948L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc20000L;
    private static final long RAM_SIZE = 0x4000L;
    private static final long CONTEXT = RAM;
    private static final long OUTPUT = RAM + 0x1000L;
    private static final long HANDLE = 0x13572468L;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void bytes(EmulatorHelper emu, long address, byte[] data) throws Exception {
        emu.writeMemory(addr(address), data);
    }

    private void word(EmulatorHelper emu, long address, long value) throws Exception {
        bytes(emu, address, new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        });
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private long readWord(EmulatorHelper emu, long address) throws Exception {
        byte[] data = emu.readMemory(addr(address), 4);
        return (data[0] & 255L) | ((data[1] & 255L) << 8)
            | ((data[2] & 255L) << 16) | ((data[3] & 255L) << 24);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void returnFromMips16(EmulatorHelper emu) throws Exception {
        long ra = reg(emu, "ra");
        require((ra & 1L) == 1L, String.format("service return is not MIPS16-tagged: %08x", ra));
        emu.writeRegister("pc", ra & ~1L);
    }

    private void copyForInterceptedService(EmulatorHelper emu, long dst, long src, int size)
        throws Exception {
        require(size >= 0 && size <= 0x100, "unexpected intercepted copy size " + size);
        byte[] data = emu.readMemory(addr(src), size);
        bytes(emu, dst, data);
    }

    private void verifyProgramImage() throws Exception {
        Memory memory = currentProgram.getMemory();
        byte[] pointer = new byte[4];
        memory.getBytes(addr(0xbfc13934L), pointer);
        long value = (pointer[0] & 255L) | ((pointer[1] & 255L) << 8)
            | ((pointer[2] & 255L) << 16) | ((pointer[3] & 255L) << 24);
        require(value == 0xbfc13949L,
            String.format("unexpected transfer-service pointer at bfc13934: %08x", value));
        Register isa = currentProgram.getRegister("ISA_MODE");
        BigInteger mode = currentProgram.getProgramContext().getValue(isa, addr(ENTRY), false);
        require(BigInteger.ONE.equals(mode), "snapshot handler does not have MIPS16 context");
    }

    @Override
    public void run() throws Exception {
        verifyProgramImage();
        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.getBlock(addr(RAM));
        if (ram == null) {
            ram = memory.createInitializedBlock("mmm_snapshot_probe_ram", addr(RAM),
                new ByteArrayInputStream(new byte[(int) RAM_SIZE]), RAM_SIZE, monitor, false);
        }
        require(ram.getStart().equals(addr(RAM)) && ram.getSize() == RAM_SIZE
                && "mmm_snapshot_probe_ram".equals(ram.getName()),
            "refusing unexpected memory block at synthetic RAM base");
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        List<String> serviceCalls = new ArrayList<>();
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

            // Distinct synthetic words make each source field observable in the copy-out.
            word(emu, CONTEXT + 0xd0, 0xd0d0d0d0L);
            word(emu, CONTEXT + 0xd4, 0xd4d4d4d4L);
            word(emu, CONTEXT + 0xd8, 0xd8d8d8d8L);
            word(emu, CONTEXT + 0xdc, 0xdcdcdcdcL);
            word(emu, CONTEXT + 0xe0, 0xe0e0e0e0L);
            word(emu, CONTEXT + 0xe4, 0xe4e4e4e4L);
            word(emu, CONTEXT + 0x104, 0x10410410L);
            word(emu, CONTEXT + 0x108, 0x10810810L);
            word(emu, CONTEXT + 0x10c, 0x10c10c10L);
            word(emu, CONTEXT + 0x140, 0x14014014L);
            word(emu, CONTEXT + 0x178, HANDLE);
            bytes(emu, OUTPUT, new byte[0x18]);

            emu.writeRegister("sp", RAM + RAM_SIZE - 0x100);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", CONTEXT);
            emu.writeRegister("a1", 0x1c);
            emu.writeRegister("a2", OUTPUT);
            emu.writeRegister("a3", 0);
            emu.writeRegister("pc", ENTRY);

            boolean returned = false;
            for (int step = 0; step < 3000; step++) {
                long pc = reg(emu, "pc");
                long base = pc & ~1L;
                if (base == STOP) {
                    returned = true;
                    break;
                }
                if (base == SYSCALL_STUB) {
                    long a0 = reg(emu, "a0"), a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2"), a3 = reg(emu, "a3");
                    if (a3 == 0x19 || a3 == 0x1a) {
                        require(a0 == HANDLE && a1 == 0 && a2 == 0,
                            String.format("bad playback boundary call: %08x %08x %08x %08x", a0, a1, a2, a3));
                        serviceCalls.add(String.format("cmd:%02x", a3));
                    } else if (a3 == 0) {
                        require(a0 == OUTPUT && a1 == CONTEXT + 0xd0 && a2 == 0x18,
                            String.format("bad snapshot copy tuple: %08x %08x %08x %08x", a0, a1, a2, a3));
                        serviceCalls.add("copy:18");
                        copyForInterceptedService(emu, a0, a1, (int) a2);
                    } else {
                        throw new Exception(String.format("unexpected external service call: %08x %08x %08x %08x", a0, a1, a2, a3));
                    }
                    emu.writeRegister("v0", 0);
                    returnFromMips16(emu);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("stopped at %08x: %s", pc, emu.getLastError()));
                }
            }
            require(returned, "FUN_bfc13864 did not return");
            require(serviceCalls.equals(List.of("cmd:19", "copy:18", "cmd:1a")),
                "unexpected service order " + serviceCalls);

            long[] expected = {
                0xd0d0d0d0L, 0xd4d4d4d4L, 0x14014014L,
                0x10410410L, 0x10810810L, 0x10c10c10L
            };
            for (int i = 0; i < expected.length; i++) {
                long actual = readWord(emu, OUTPUT + 4L * i);
                require(actual == expected[i], String.format("snapshot[%d]=%08x expected=%08x", i, actual, expected[i]));
            }
            println("MMM COMMAND SNAPSHOT DYNAMIC PASS entry=bfc13864 command=0x1c "
                + "copy=0x18 external-service=stubbed-return-0 "
                + "observed-request-sequence=0x19,copy,0x1a fields=verified");
        } finally {
            emu.dispose();
        }
    }
}

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

/** Execute the extracted MMM_BP entry through its init dispatcher, stopping at resident stubs. */
public class RunMmmBpInitProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc218a8L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc20000L;
    private static final int RAM_SIZE = 0x8000;
    private static final long CONTEXT = 0x9fc21b08L;
    private static final long RESULT = RAM + 0x100L;
    private static final long STUB_CLEAR = 0xbfc21a38L;
    private static final long STUB_SERVICE = 0xbfc21a3cL;
    private static final long HANDLE_DAE = RAM + 0x5000L;
    private static final long HANDLE_PIPE = RAM + 0x5100L;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long reg(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private long readWord(EmulatorHelper emulator, long address) throws Exception {
        byte[] data = emulator.readMemory(addr(address), 4);
        return (data[0] & 255L) | ((data[1] & 255L) << 8)
            | ((data[2] & 255L) << 16) | ((data[3] & 255L) << 24);
    }

    private int readHalf(EmulatorHelper emulator, long address) throws Exception {
        byte[] data = emulator.readMemory(addr(address), 2);
        return (data[0] & 255) | ((data[1] & 255) << 8);
    }

    private void writeWord(EmulatorHelper emulator, long address, long value) throws Exception {
        emulator.writeMemory(addr(address), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private void returnFromMips16(EmulatorHelper emulator) throws Exception {
        long ra = reg(emulator, "ra");
        require((ra & 1L) == 1L, String.format("untagged MIPS16 return address %08x", ra));
        emulator.writeRegister("pc", ra & ~1L);
    }

    private void verifyImage() throws Exception {
        Register isa = currentProgram.getRegister("ISA_MODE");
        for (long address : new long[] {ENTRY, 0x680a1ed4L, 0x68061c00L}) {
            BigInteger mode = currentProgram.getProgramContext().getValue(isa, addr(address), false);
            require(BigInteger.ONE.equals(mode), String.format("expected MIPS16 context at %08x, got %s", address, mode));
        }
        MemoryBlock entryBlock = currentProgram.getMemory().getBlock(addr(ENTRY));
        MemoryBlock initBlock = currentProgram.getMemory().getBlock(addr(0x68061c00L));
        require(entryBlock != null && initBlock != null, "entry or init block is not mapped");
    }

    @Override
    public void run() throws Exception {
        verifyImage();
        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.getBlock(addr(RAM));
        if (ram == null) {
            ram = memory.createInitializedBlock("mmm_bp_init_probe_ram", addr(RAM),
                new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        }
        require(ram.getStart().equals(addr(RAM)) && ram.getSize() == RAM_SIZE
                && "mmm_bp_init_probe_ram".equals(ram.getName()),
            "refusing unexpected block at synthetic RAM base: " + ram.getName());
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> calls = new ArrayList<>();
        int[] initRangeCalls = {0};
        long[] registration = {0, 0, 0, 0};
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

            byte[] initialContext = new byte[0xec];
            java.util.Arrays.fill(initialContext, (byte)0xa5);
            emulator.writeMemory(addr(CONTEXT), initialContext);
            writeWord(emulator, RESULT, 0xdeadbeefL);
            emulator.writeRegister("sp", RAM + RAM_SIZE - 0x100L);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("a0", RESULT);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", RAM + 0x200L);
            emulator.writeRegister("a3", 0);
            // Enable Ghidra's memory-write tracker only after synthetic state
            // has been seeded, so it records AP execution rather than setup.
            emulator.enableMemoryWriteTracking(true);
            emulator.writeRegister("pc", ENTRY);

            boolean returned = false;
            for (int step = 0; step < 20000; step++) {
                long pc = reg(emulator, "pc");
                long target = pc & ~1L;
                if (target == STOP) {
                    returned = true;
                    break;
                }
                if (target == STUB_CLEAR || target == STUB_SERVICE) {
                    long a0 = reg(emulator, "a0");
                    long a1 = reg(emulator, "a1");
                    long a2 = reg(emulator, "a2");
                    long a3 = reg(emulator, "a3");
                    long v0 = 0;
                    calls.add(String.format("%08x:%08x,%08x,%08x,%08x", target, a0, a1, a2, a3));

                    if (target == STUB_CLEAR && a3 == 1) {
                        require(a0 == CONTEXT && a1 == 0 && a2 == 0xec,
                            String.format("unexpected init range call %08x,%08x,%08x,%08x", a0, a1, a2, a3));
                        initRangeCalls[0]++;
                    }
                    if (target == STUB_SERVICE && a3 == 0x49) {
                        if (a0 == 3) v0 = HANDLE_DAE;
                        else if (a0 == 0) v0 = HANDLE_PIPE;
                        else throw new Exception(String.format("unexpected shared-memory selector a0=%08x", a0));
                    }
                    if (target == STUB_SERVICE && a3 == 0x4a) {
                        require(a0 >= RAM + RAM_SIZE - 0x1000L && a0 < RAM + RAM_SIZE,
                            "0x4a registration record is not in the bounded stack");
                        registration[0] = readWord(emulator, a0);
                        registration[1] = readWord(emulator, a0 + 4);
                        registration[2] = readWord(emulator, a0 + 8);
                        registration[3] = readHalf(emulator, a0 + 12);
                        require(registration[0] == 0xbfc27735L
                                && registration[1] == CONTEXT
                                && registration[2] == 0x9fc1bdf0L
                                && registration[3] == 0x4b0,
                            String.format("unexpected 0x4a record %08x,%08x,%08x,%04x",
                                registration[0], registration[1], registration[2], registration[3]));
                    }
                    // Resident service effects are deliberately omitted. We record their ABI
                    // requests and provide only nonzero synthetic handles needed to continue.
                    emulator.writeRegister("v0", v0);
                    returnFromMips16(emulator);
                    continue;
                }
                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("stopped at %08x: %s", pc, emulator.getLastError()));
                }
            }

            require(returned, "MMM_BP entry did not return before step limit");
            require(initRangeCalls[0] == 1, "initializer did not request exactly one 0xec-byte range call");
            List<String> expectedCalls = List.of(
                String.format("%08x:%08x,00000000,000000ec,00000001", STUB_CLEAR, CONTEXT),
                String.format("%08x:00000003,00000000,00000000,00000049", STUB_SERVICE),
                String.format("%08x:00000000,00000000,00000000,00000049", STUB_SERVICE),
                String.format("%08x:%08x,00000000,00000000,00000017", STUB_CLEAR, CONTEXT + 0x40),
                String.format("%08x:%08x,00000000,00000000,00000012", STUB_CLEAR, CONTEXT + 0x44),
                String.format("%08x:00000000,00000000,00000000,00000033", STUB_SERVICE),
                String.format("%08x:00000000,00000000,00000000,00000034", STUB_SERVICE));
            require(calls.size() == expectedCalls.size() + 1,
                "unexpected resident call count: " + calls.size());
            for (int i = 0; i < expectedCalls.size(); i++) {
                int actualIndex = i < 6 ? i : i + 1;
                require(calls.get(actualIndex).equals(expectedCalls.get(i)),
                    "unexpected resident call at index " + i + ": " + calls.get(i));
            }
            String[] registerCall = calls.get(6).split(":")[1].split(",");
            long registrationRecord = Long.parseUnsignedLong(registerCall[0], 16);
            require(registrationRecord >= RAM + RAM_SIZE - 0x1000L
                    && registrationRecord < RAM + RAM_SIZE
                    && Long.parseUnsignedLong(registerCall[1], 16) == 3
                    && Long.parseUnsignedLong(registerCall[2], 16) == 0xff
                    && Long.parseUnsignedLong(registerCall[3], 16) == 0x4a,
                "unexpected 0x4a registration arguments: " + calls.get(6));
            println(String.format("MMM BP INIT OBS result=%08x context+28=%08x context+58=%08x "
                + "context+70=%08x context+d0=%08x", readWord(emulator, RESULT),
                readWord(emulator, CONTEXT + 0x28), readWord(emulator, CONTEXT + 0x58),
                readWord(emulator, CONTEXT + 0x70), readWord(emulator, CONTEXT + 0xd0)));
            println(String.format("MMM BP INIT REGISTRATION callback=%08x context=%08x "
                + "record_field_2=%08x record_field_3_u16=%04x operation=0x4a service=synthetic",
                registration[0], registration[1], registration[2], registration[3]));
            for (String call : calls) println("MMM BP INIT CALL " + call);
            require(readWord(emulator, RESULT) == CONTEXT,
                String.format("entry did not return the init context: %08x", readWord(emulator, RESULT)));
            require(readWord(emulator, CONTEXT + 0xd0) == 0xa5a5a5a5L,
                "the no-effect resident stub model unexpectedly changed context+0xd0");
            ghidra.program.model.address.AddressSetView trackedWrites =
                emulator.getTrackedMemoryWriteSet();
            require(trackedWrites != null
                    && trackedWrites.contains(addr(RESULT))
                    && trackedWrites.contains(addr(CONTEXT))
                    && trackedWrites.contains(addr(CONTEXT + 0x58)),
                "memory-write tracker missed known entry/context/handle stores");
            boolean statusSlotWritten = false;
            for (int offset = 0; offset < 4; offset++) {
                if (trackedWrites.contains(addr(CONTEXT + 0xd0 + offset))) {
                    statusSlotWritten = true;
                }
            }
            require(!statusSlotWritten,
                "AP p-code wrote at least one byte of context+0xd0 under the no-effect resident model");
            require(readWord(emulator, CONTEXT) == 0x2c
                    && readWord(emulator, CONTEXT + 4) == 2
                    && readWord(emulator, CONTEXT + 0x28) == 0x32
                    && readWord(emulator, CONTEXT + 0x58) == HANDLE_DAE
                    && readWord(emulator, CONTEXT + 0x70) == HANDLE_PIPE,
                "expected explicit init fields/handles were not written");

            println("MMM BP INIT PCODE PASS entry=bfc218a8 dispatcher=680a1ed4 init=68061c00 "
                + "calls=" + calls.size() + " init-range=0xec resident-effects=opaque "
                + "context+0xd0=write-tracked-untouched-under-no-effect-stub");
        } finally {
            emulator.dispose();
        }
    }
}

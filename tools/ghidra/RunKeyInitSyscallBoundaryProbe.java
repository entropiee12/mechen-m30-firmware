// Capture the key driver's caller-side arguments at its fixed-ROM syscall trap.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class RunKeyInitSyscallBoundaryProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc10008L;
    private static final long SYSCALL_TRAP = 0xbfc10004L;
    private static final long STOP = 0x700300L;
    private static final long BSS = 0x9fc14328L;
    private static final long MMIO_9 = 0xc0090000L;
    private static final long MMIO_B = 0xc00b0000L;
    private static final long STACK = 0x10003f00L;
    private static final String EXPECTED_SHA256 =
        "858d56ff19106fccb75e35552c9cecca99dce0579c7daac5dce376d742f67ba1";

    private final List<String> calls = new ArrayList<>();

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

    private long word(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private String memorySha256(
            EmulatorHelper emulator,
            long start,
            int length
    ) throws Exception {
        byte[] bytes = emulator.readMemory(address(start), length);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return HexFormat.of().formatHex(digest);
    }

    private void addBlock(
            Memory memory,
            long start,
            int size,
            String name,
            boolean executable
    ) throws Exception {
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
        block.setExecute(executable);
    }

    private void returnFromSyntheticRom(
            EmulatorHelper emulator,
            long returnValue
    ) throws Exception {
        long returnAddress = register(emulator, "ra");
        require((returnAddress & 1L) == 1L,
            String.format("syscall return address lacks MIPS16 tag: 0x%08x", returnAddress));
        emulator.writeRegister("v0", returnValue);
        emulator.writeRegister("pc", returnAddress & ~1L);
    }

    private void invokeInit(
            EmulatorHelper emulator,
            int selectedCall,
            long selectedReturn
    ) throws Exception {
        emulator.writeRegister("sp", STACK);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("a0", 0);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("s0", 0);
        emulator.writeRegister("s1", 0);
        emulator.writeRegister("pc", ENTRY);

        for (int step = 0; step < 500; step++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc == STOP) {
                return;
            }

            if (pc == SYSCALL_TRAP) {
                long v1AtTrap = register(emulator, "v1");
                long argument0 = register(emulator, "a0");
                long argument1 = register(emulator, "a1");
                long argument2 = register(emulator, "a2");
                long argument3 = register(emulator, "a3");
                long returnAddress = register(emulator, "ra");
                require((returnAddress & 1L) == 1L,
                    String.format("syscall return address lacks MIPS16 tag: 0x%08x", returnAddress));
                long payloadWord = argument1 >= STACK - 0x100 && argument1 < STACK + 0x100
                    ? word(emulator, argument1)
                    : 0;
                int callIndex = calls.size();
                calls.add(String.format(
                    "v1_at_trap=0x%08x ra=0x%08x args=(0x%x,0x%08x,0x%x,0x%x) payload_word=0x%08x",
                    v1AtTrap,
                    returnAddress,
                    argument0,
                    argument1,
                    argument2,
                    argument3,
                    payloadWord
                ));
                long returnValue = callIndex == selectedCall ? selectedReturn : 0;
                returnFromSyntheticRom(emulator, returnValue);
                continue;
            }

            if (!emulator.step(monitor)) {
                throw new Exception(String.format(
                    "key init stopped at 0x%08x: %s",
                    pc,
                    emulator.getLastError()
                ));
            }
        }

        throw new Exception("key init did not return within 500 instructions");
    }

    private List<String> runCase(int selectedCall, long selectedReturn)
            throws Exception {
        calls.clear();
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            invokeInit(emulator, selectedCall, selectedReturn);
            require(calls.size() == 2,
                String.format("expected exactly two init syscalls, got %d: %s",
                    calls.size(), calls));

            List<String> observation = new ArrayList<>(calls);
            observation.add(String.format(
                "exit_regs=v0:%08x,v1:%08x,a0:%08x,a1:%08x,a2:%08x,a3:%08x,sp:%08x,ra:%08x",
                register(emulator, "v0"),
                register(emulator, "v1"),
                register(emulator, "a0"),
                register(emulator, "a1"),
                register(emulator, "a2"),
                register(emulator, "a3"),
                register(emulator, "sp"),
                register(emulator, "ra")
            ));
            observation.add("bss_sha256=" + memorySha256(emulator, BSS, 0xd6));
            observation.add("mmio9_sha256=" + memorySha256(emulator, MMIO_9, 0x20));
            observation.add("mmiob_sha256=" + memorySha256(emulator, MMIO_B, 0x20));
            observation.add("stack_sha256=" + memorySha256(emulator, STACK, 0x100));
            return observation;
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKeyInitSyscallBoundaryProbe.java KEY.DRV");
        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver)
        );
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);

        Memory memory = currentProgram.getMemory();
        require(memory.getBlock(address(ENTRY)) != null, "key.drv code is not mapped");
        addBlock(memory, BSS, 0xd6, "key_init_boundary_bss", false);
        addBlock(memory, MMIO_9, 0x20, "key_init_boundary_mmio_9", false);
        addBlock(memory, MMIO_B, 0x20, "key_init_boundary_mmio_b", false);
        addBlock(memory, STACK, 0x100, "key_init_boundary_stack", false);

        List<String> baseline = runCase(-1, 0);
        require(baseline.size() == 7, "expected two calls and final state: " + baseline);
        require(baseline.get(0).contains("args=(0x6,0x00000000,0x0,0x1a)"),
            "first initialization call arguments changed: " + baseline.get(0));
        require(baseline.get(1).contains("args=(0x6,0x10003ee8,0x0,0x18)"),
            "second initialization call arguments changed: " + baseline.get(1));
        require(baseline.get(1).endsWith("payload_word=0x20000a00"),
            "second initialization payload changed: " + baseline.get(1));
        require(baseline.get(0).contains("v1_at_trap=0xbfc10005 ra=0xbfc1002d"),
            "first initialization trap observation changed: " + baseline.get(0));
        require(baseline.get(1).contains("v1_at_trap=0xbfc10005 ra=0xbfc10041"),
            "second initialization trap observation changed: " + baseline.get(1));

        for (int index = 0; index < 2; index++) {
            println("KEY INIT ROM CALL " + index + " " + baseline.get(index));
        }

        long[] returnValues = {1, 0xffffffffL, 0xdeadbeefL};
        for (int callIndex = 0; callIndex < 2; callIndex++) {
            for (long returnValue : returnValues) {
                List<String> observation = runCase(callIndex, returnValue);
                boolean sameAsBaseline = baseline.equals(observation);
                require(sameAsBaseline, String.format(
                    "init syscall return changed caller-visible state call=%d return=%08x\nbaseline=%s\nactual=%s",
                    callIndex, returnValue, baseline, observation));
                println(String.format(
                    "KEY INIT RETURN SWEEP call=%d value=0x%08x same-as-baseline=%s observation=%s",
                    callIndex,
                    returnValue,
                    sameAsBaseline,
                    observation
                ));
            }
        }

        println("KEY INIT SYSCALL BOUNDARY PASS calls=2 return-sweep=6 "
            + "observed=registers,bss,mmio,stack synthetic-mmio=1 synthetic-return=1");
    }
}

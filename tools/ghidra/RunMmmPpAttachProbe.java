import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Execute mmm_pp shared-memory attach and its module-0x0c callback. */
public class RunMmmPpAttachProbe extends GhidraScript {
    private static final long ATTACH = 0x68012d00L;
    private static final long DISPATCHER = 0xbfc138c0L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc13000L;
    private static final int RAM_SIZE = 0x8000;
    private static final long CONTEXT = 0x9fc13c00L;
    private static final long SHM_HANDLE = 0x9fc18000L;
    private static final long MODULE_0C_TRAP = 0xbfc13a5cL;
    private static final long MODULE_0F_TRAP = 0xbfc13a64L;
    private static final long MODULE_10_TRAP = 0xbfc13a60L;
    private static final String EXPECTED_SHA256 =
        "9a7a6c2215b2594e2ef816b8a3e592968921afff7747f3cadbd6e71bf74047d6";
    private static final int MAX_STEPS = 100000;

    private final List<String> serviceCalls = new ArrayList<>();
    private int shmAttachCalls;
    private int callbackCalls;
    private int module10Calls;
    private int registrationCalls;
    private int dispatcherGuardCalls;
    private long registrationRecord;

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

    private long word(byte[] data, int offset) {
        return (data[offset] & 0xffL)
            | ((data[offset + 1] & 0xffL) << 8)
            | ((data[offset + 2] & 0xffL) << 16)
            | ((data[offset + 3] & 0xffL) << 24);
    }

    private long programWord(long location) throws Exception {
        byte[] data = new byte[4];
        currentProgram.getMemory().getBytes(addr(location), data);
        return word(data, 0);
    }

    private long readWord(EmulatorHelper emu, long location) throws Exception {
        return word(emu.readMemory(addr(location), 4), 0);
    }

    private int readHalf(EmulatorHelper emu, long location) throws Exception {
        byte[] data = emu.readMemory(addr(location), 2);
        return (data[0] & 0xff) | ((data[1] & 0xff) << 8);
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder text = new StringBuilder();
        for (byte value : digest) {
            text.append(String.format("%02x", value & 0xff));
        }
        return text.toString();
    }

    private String hex(long value) {
        return String.format("0x%08x", value & 0xffffffffL);
    }

    private void returnFromService(EmulatorHelper emu, long returnAddress, long result)
            throws Exception {
        require((returnAddress & 1L) == 1L,
            "service caller return address does not have the MIPS16 mode bit");
        emu.writeRegister("v0", result);
        emu.writeRegister("pc", returnAddress & ~1L);
    }

    private boolean service(EmulatorHelper emu, long pc) throws Exception {
        long a0 = reg(emu, "a0");
        long a1 = reg(emu, "a1");
        long a2 = reg(emu, "a2");
        long a3 = reg(emu, "a3");
        long ra = reg(emu, "ra");

        if (pc == MODULE_0F_TRAP) {
            long result = 0;
            String label;
            if (a3 == 0x49) {
                require(a0 == 3 && a1 == 0 && a2 == 0,
                    String.format("unexpected SHM attach ABI: %s,%s,%s,%s",
                        hex(a0), hex(a1), hex(a2), hex(a3)));
                result = SHM_HANDLE;
                shmAttachCalls++;
                label = "SHM-attach";
            } else if (a3 == 0x4a) {
                require(a1 == 3 && a2 == 0xff,
                    String.format("unexpected callback registration ABI: %s,%s,%s,%s",
                        hex(a0), hex(a1), hex(a2), hex(a3)));
                require(a0 >= RAM + RAM_SIZE - 0x200 && a0 + 0x10 <= RAM + RAM_SIZE,
                    "callback registration record is outside the bounded stack");
                require(readWord(emu, a0) == 0xbfc13769L
                        && readWord(emu, a0 + 4) == CONTEXT
                        && readWord(emu, a0 + 8) == 0x9fc1c480L
                        && readHalf(emu, a0 + 12) == 0x500,
                    "callback registration record fields changed");
                registrationCalls++;
                registrationRecord = a0;
                label = "callback-register";
            } else if (a3 == 0x33 || a3 == 0x34 || a3 == 0x4b) {
                label = String.format("op-%02x", a3);
            } else {
                throw new Exception(String.format("unexpected module-0x0f op 0x%08x", a3));
            }

            serviceCalls.add(String.format("module=0x0f op=0x%02x args=%s,%s,%s,%s result=%s %s",
                a3, hex(a0), hex(a1), hex(a2), hex(a3), hex(result), label));
            returnFromService(emu, ra, result);
            return true;
        }

        if (pc == MODULE_10_TRAP) {
            require(a3 == 1 || a3 == 0x17 || a3 == 0x19 || a3 == 0x1a,
                String.format("unexpected module-0x10 op 0x%08x", a3));
            if (a3 == 1) {
                require(a0 == CONTEXT && a1 == 0 && a2 == 0xcc,
                    "initial context clear arguments changed");
                emu.writeMemory(addr(a0), new byte[(int)a2]);
            }

            if (a3 == 0x19 || a3 == 0x1a) {
                require(a0 == readWord(emu, CONTEXT + 0x7c) && a1 == 0 && a2 == 0,
                    "dispatcher module-0x10 guard arguments changed");
                dispatcherGuardCalls++;
            }

            module10Calls++;
            serviceCalls.add(String.format("module=0x10 op=0x%02x args=%s,%s,%s,%s result=0x00000000 synthetic",
                a3, hex(a0), hex(a1), hex(a2), hex(a3)));
            returnFromService(emu, ra, 0);
            return true;
        }

        if (pc == MODULE_0C_TRAP) {
            require(a0 == 1 && a1 == SHM_HANDLE && a2 == 0 && a3 == 0x12,
                String.format("module-0x0c op-0x12 ABI mismatch: %s,%s,%s,%s",
                    hex(a0), hex(a1), hex(a2), hex(a3)));
            callbackCalls++;
            serviceCalls.add(String.format("module=0x0c op=0x12 args=%s,%s,%s,%s result=0x00000000 synthetic",
                hex(a0), hex(a1), hex(a2), hex(a3)));
            returnFromService(emu, ra, 0);
            return true;
        }

        return false;
    }

    private int execute(
        EmulatorHelper emu,
        long entry,
        long a0,
        long a1,
        long a2
    ) throws Exception {
        emu.writeRegister("sp", RAM + RAM_SIZE - 0x100);
        emu.writeRegister("ra", STOP | 1L);
        emu.writeRegister("a0", a0);
        emu.writeRegister("a1", a1);
        emu.writeRegister("a2", a2);
        emu.writeRegister("a3", 0);
        emu.writeRegister("s0", 0);
        emu.writeRegister("s1", 0);
        emu.writeRegister("v0", 0);
        emu.writeRegister("v1", 0);
        emu.writeRegister("pc", entry);

        int steps;
        for (steps = 0; steps < MAX_STEPS; steps++) {
            long pc = reg(emu, "pc") & ~1L;
            if (pc == STOP) {
                return steps;
            }

            if (service(emu, pc)) {
                continue;
            }

            if (!emu.step(monitor)) {
                throw new Exception(String.format("execution stopped at %s: %s",
                    hex(pc), emu.getLastError()));
            }
        }

        throw new Exception(String.format("entry %s exceeded the step limit", hex(entry)));
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunMmmPpAttachProbe.java MMM_PP.AL");
        byte[] input = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        require(EXPECTED_SHA256.equals(sha256(input)), "mmm_pp.al SHA-256 mismatch");
        require(programWord(0x68012e14L) == CONTEXT,
            "mmm_pp context pointer pool entry changed");
        require(programWord(0x68012e1cL) == 0xbfc13a65L,
            "mmm_pp module-0x0f service pointer changed");
        require(programWord(0x6801313cL) == 0xbfc13a5dL,
            "mmm_pp module-0x0c service pointer changed");

        Memory memory = currentProgram.getMemory();
        memory.createInitializedBlock("mmm_pp_attach_ram", addr(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false)
            .setWrite(true);
        byte[] sentinel = new byte[0xcc];
        Arrays.fill(sentinel, (byte)0xa5);
        memory.setBytes(addr(CONTEXT), sentinel);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int attachSteps;
        int callbackSteps;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

            attachSteps = execute(emu, ATTACH, 0, 0, 0);
            require(shmAttachCalls == 1, "expected one id-3/op-0x49 attach call");
            require(registrationCalls == 1, "expected one op-0x4a callback registration");
            require(module10Calls == 2, "expected the two module-0x10 initialization calls");
            require(reg(emu, "v0") == CONTEXT,
                String.format("attach returned %s instead of the context pointer", hex(reg(emu, "v0"))));
            require(word(emu.readMemory(addr(CONTEXT), 0xcc), 0x9c) == SHM_HANDLE,
                "attach result was not stored in context+0x9c");

            serviceCalls.add(String.format("verified context+0x9c=%s after attach",
                hex(word(emu.readMemory(addr(CONTEXT), 0xcc), 0x9c))));
            callbackSteps = execute(emu, DISPATCHER, CONTEXT, 0x11, RAM + 0x100);
            require(callbackCalls == 1,
                "command-0x11 dispatch did not make one module-0x0c op-0x12 request");
            require(dispatcherGuardCalls == 2,
                "command dispatch did not execute its module-0x10 enter/leave guards");
            require(shmAttachCalls == 1,
                "callback unexpectedly issued another id-3/op-0x49 request");

            for (String call : serviceCalls) {
                println("MMM_PP ATTACH SERVICE " + call);
            }
            println(String.format(
                "MMM_PP ATTACH P-CODE PASS attach-steps=%d dispatch-steps=%d command=0x11 context=%s handle=%s registration-record=%s callback-pointer=%s attach-calls=%d callback-calls=%d registrations=%d synthetic-services=true shared-memory-identity=modeled-handle",
                attachSteps, callbackSteps, hex(CONTEXT), hex(SHM_HANDLE),
                hex(registrationRecord), hex(readWord(emu, registrationRecord)),
                shmAttachCalls, callbackCalls, registrationCalls));
        } finally {
            emu.dispose();
        }
    }
}

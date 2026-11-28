// Execute manager.ap's actual 0x201 handler on the captured 24-byte sender buffer.
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
import java.util.ArrayList;
import java.util.List;

/** The transport into manager is a fixture; only the manager handler is real. */
public class RunManager201ReceiverProbe extends GhidraScript {
    private static final long ENTRY = 0x7f196200L;
    private static final long SERVICE = 0xbfc13de8L;
    private static final long RAM = 0x9fc1a000L;
    private static final int RAM_SIZE = 0x1000;
    private static final long MESSAGE = RAM + 0x300;
    private static final long STACK = RAM + 0xf00;
    private static final long STOP = 0x700300L;
    private static final long MANAGER_STATE = 0x9fc1a1b1L;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            if (out.length() > 0) out.append(' ');
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    private void returnFromResident(EmulatorHelper emu) throws Exception {
        long ra = reg(emu, "ra");
        require((ra & 1L) == 1L,
            String.format("resident return address lacks MIPS16 tag: 0x%08x", ra));
        emu.writeRegister("v0", 0);
        emu.writeRegister("pc", ra & ~1L);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunManager201ReceiverProbe.java CAPTURED_MESSAGE.bin");
        byte[] message = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        require(message.length == 0x18, "captured sender buffer must be 24 bytes");
        require((message[0] & 0xff) == 1 && (message[1] & 0xff) == 2,
            "captured message is not ID 0x0201");

        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.getBlock(addr(RAM));
        if (ram == null) {
            ram = memory.createInitializedBlock("manager_201_probe_ram", addr(RAM),
                new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
            ram.setRead(true);
            ram.setWrite(true);
            ram.setExecute(true);
        }
        memory.setBytes(addr(RAM), new byte[RAM_SIZE]);
        memory.setBytes(addr(MESSAGE), message);
        // Keep the 0x201 handler on its no-event branch by matching the state
        // byte to message[+4]. This is explicit synthetic manager state.
        memory.setBytes(addr(MANAGER_STATE), new byte[] {message[4]});

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        List<Long> serviceOps = new ArrayList<>();
        int steps = 0;
        boolean returned = false;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", MESSAGE);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s0", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", ENTRY);

            for (; steps < 3000; steps++) {
                long pc = reg(emu, "pc");
                long target = pc & ~1L;
                if (target == STOP) { returned = true; break; }
                if (target == SERVICE) {
                    long a0 = reg(emu, "a0");
                    long a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2");
                    long a3 = reg(emu, "a3");
                    long expectedOp = serviceOps.isEmpty() ? 7 : 2;
                    require(a0 == 0x0c && a1 == expectedOp && a2 == 0 && a3 == 5,
                        String.format("unexpected module-0x0c request #%d: %08x,%08x,%08x,%08x",
                            serviceOps.size(), a0, a1, a2, a3));
                    serviceOps.add(a1);
                    returnFromResident(emu);
                    continue;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("manager stopped at 0x%08x: %s", pc, emu.getLastError()));
            }

            require(returned, "manager handler did not return");
            require(serviceOps.size() == 2 && serviceOps.get(0) == 7 && serviceOps.get(1) == 2,
                "0x201 equal-state branch did not issue the expected start/finish service pair");
            println(String.format(
                "MANAGER 0x201 EQUAL-STATE BRANCH PASS captured=%s payloadByte0=0x%02x stateByte=0x%02x module0cOps=7,2 steps=%d event-path=not-tested transport=synthetic residentReturns=synthetic",
                hex(message), message[4] & 0xff, message[4] & 0xff, steps));
        }
        finally { emu.dispose(); }
    }
}

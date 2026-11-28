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

/** Execute the real UI callback path that constructs a message 0x304 byte. */
public class RunMengineStateProducerProbe extends GhidraScript {
    private static final long ENTRY = 0x40394f48L;
    private static final long MESSAGE_SERVICE = 0xbfc16408L;
    private static final long VALUE_SERVICE = 0xbfc164dcL;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L;
    private static final long RAM_SIZE = 0x7000L;
    private static final long SETTINGS = 0x9fc156f4L;
    private static final long MODE_BYTE = SETTINGS + 0x25L;
    private static final long FLAG_BYTE = SETTINGS + 0x26L;
    private static final long MESSAGE_FLAGS = 0x9fc15418L;
    private static final long CALLBACK_RESULT = 0x9fc1542cL;
    private static final long LAST_VALUE = 0x9fc15730L;

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

    private int readByte(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xff;
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
        require((ra & 1L) == 1L, String.format("callback return is not MIPS16-tagged: %08x", ra));
        emu.writeRegister("pc", ra & ~1L);
    }

    private int runCase(String label, int flags, int initialUpperBits, long serviceValue,
        int expectedPayload) throws Exception {
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int messageCalls = 0;
        int valueCalls = 0;
        int steps = 0;
        try {
            bytes(emu, RAM, new byte[(int) RAM_SIZE]);
            word(emu, MESSAGE_FLAGS, flags);
            word(emu, LAST_VALUE, 300);
            bytes(emu, MODE_BYTE, new byte[] {0x55});
            bytes(emu, FLAG_BYTE, new byte[] {(byte) initialUpperBits});

            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emu.writeRegister("sp", RAM + RAM_SIZE - 0x100);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", 0);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("pc", ENTRY);

            boolean returned = false;
            for (steps = 0; steps < 3000; steps++) {
                long pc = reg(emu, "pc");
                long base = pc & ~1L;
                if (base == STOP) {
                    returned = true;
                    break;
                }
                if (base == MESSAGE_SERVICE) {
                    messageCalls++;
                    long payloadPtr = reg(emu, "a0");
                    require(reg(emu, "a1") == 0 && reg(emu, "a2") == 0x304,
                        String.format("unexpected message envelope: %08x %08x %08x", payloadPtr, reg(emu, "a1"), reg(emu, "a2")));
                    require(readByte(emu, payloadPtr) == expectedPayload,
                        String.format("payload=%02x expected=%02x", readByte(emu, payloadPtr), expectedPayload));
                    emu.writeRegister("v0", 0);
                    returnFromMips16(emu);
                    continue;
                }
                if (base == VALUE_SERVICE) {
                    valueCalls++;
                    long out = reg(emu, "a0");
                    word(emu, out, serviceValue);
                    emu.writeRegister("v0", 0);
                    returnFromMips16(emu);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("%s stopped at %08x: %s", label, pc, emu.getLastError()));
                }
            }
            require(returned, label + " callback did not return");
            require(messageCalls == 1, label + " message-service call count=" + messageCalls);
            require(valueCalls == ((flags & 0x4000) != 0 ? 1 : 0),
                label + " value-service call count=" + valueCalls);
            require(readByte(emu, MODE_BYTE) == 3, label + " did not store low-nibble selection 3");
            if ((flags & 0x4000) != 0) {
                require(readWord(emu, CALLBACK_RESULT) == serviceValue - 300,
                    label + " follow-up callback result mismatch");
            }
            println(String.format("MENGINE STATE PRODUCER CASE %s steps=%d payload=0x%02x value-service-calls=%d",
                label, steps, expectedPayload, valueCalls));
            return messageCalls;
        } finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.getBlock(addr(RAM));
        if (ram == null) {
            ram = memory.createInitializedBlock("mengine_producer_probe_ram", addr(RAM),
                new ByteArrayInputStream(new byte[(int) RAM_SIZE]), RAM_SIZE, monitor, false);
        }
        require(ram.getStart().equals(addr(RAM)) && ram.getSize() == RAM_SIZE
                && "mengine_producer_probe_ram".equals(ram.getName()),
            "refusing unexpected synthetic RAM block");
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        Register isa = currentProgram.getRegister("ISA_MODE");
        BigInteger mode = currentProgram.getProgramContext().getValue(isa, addr(ENTRY), false);
        require(BigInteger.ONE.equals(mode), "UI producer callback does not have MIPS16 context");

        runCase("preserve-upper-bits", 0, 0xa0, 0, 0xa3);
        runCase("flag-adds-0x40", 0x4000, 0x10, 2000, 0x53);
        println("MENGINE STATE PRODUCER DYNAMIC PASS entry=40394f48 message-id=0x304 "
            + "low-nibble=3 external-message-service=stubbed");
    }
}

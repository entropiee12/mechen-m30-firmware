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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Feed captured music.ap Custom output through the real mengine 0x303 receiver. */
public class RunEqCustomMengineDeliveryProbe extends GhidraScript {
    private static final long ENTRY = 0x60090d64L;
    private static final long INIT_ENTRY = 0x60051278L;
    private static final long INIT_NEXT = 0x60051034L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc14000L;
    private static final int RAM_SIZE = 0x8000;
    private static final long STACK = RAM + RAM_SIZE - 0x100;
    private static final long MESSAGE = RAM + 0x1000;
    private static final long PAYLOAD = RAM + 0x1020;
    private static final long CUSTOM = 0x9fc1a19cL;
    private static final long SELECTOR = 0x9fc15cc9L;
    private static final long EQ_STATE_BASE = 0x9fc15cb0L;
    private static final long ACTIVE = 0x9fc15db4L;
    private static final long SELECTOR_MIRROR = 0x9fc15db5L;
    private static final long ENGINE_STATE_POINTER_CELL = 0x9fc15ec8L;
    private static final long STRUCTURE = 0x9fc15dd4L;
    private static final long OUTPUT = RAM + 0x2000;
    private static final long HANDLE = 0x9fc15c0cL;
    private static final long COPY_SERVICE = 0xbfc11cc0L;
    private static final long MESSAGE_SERVICE = 0xbfc11cc4L;
    private static final long SHM_SERVICE = 0xbfc11cc8L;
    private static final long ACK_SERVICE = 0xbfc1197cL;
    private static final int[] FREQUENCIES = {80, 200, 480, 980, 4000, 8000, 16000};
    private static final int[] Q_VALUES = {10, 6, 4, 6, 10, 5, 8};

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
        byte[] data = bytes(emu, at, 4);
        return (data[0] & 0xffL) | ((data[1] & 0xffL) << 8)
            | ((data[2] & 0xffL) << 16) | ((data[3] & 0xffL) << 24);
    }

    private int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private long programWord(long at) throws Exception {
        byte[] data = new byte[4];
        currentProgram.getMemory().getBytes(addr(at), data);
        return (data[0] & 0xffL) | ((data[1] & 0xffL) << 8)
            | ((data[2] & 0xffL) << 16) | ((data[3] & 0xffL) << 24);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void verifyStructure(byte[] structure, byte[] gains) throws Exception {
        for (int band = 0; band < 7; band++) {
            int offset = 0x10 + band * 8;
            require(u16(structure, offset) == FREQUENCIES[band], "EQ frequency mismatch at band " + band);
            require(u16(structure, offset + 2) == Q_VALUES[band], "EQ Q mismatch at band " + band);
            int signedGain = (byte) gains[band];
            require((short)u16(structure, offset + 4) == signedGain * 10,
                "Custom gain mismatch at band " + band);
            require(u16(structure, offset + 6) == 1, "EQ active flag mismatch at band " + band);
        }
        for (int offset = 0x48; offset < 0x90; offset++)
            require(structure[offset] == 0, "unused EQ band data is not zero");
        for (int offset = 0x90; offset < 0xf0; offset++)
            require((structure[offset] & 0xff) == 0xaa, "builder changed trailing sentinel data");
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunEqCustomMengineDeliveryProbe.java MUSIC_CAPTURE.bin");
        byte[] seed = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        require(seed.length == 11, "capture must contain four message bytes and seven Custom gains");
        byte[] messagePayload = Arrays.copyOfRange(seed, 0, 4);
        byte[] gains = Arrays.copyOfRange(seed, 4, 11);
        require((messagePayload[0] & 0xff) == 7 && (messagePayload[1] & 0xff) == 7,
            "captured music producer payload is not {7,7}");

        require(programWord(0x60091014L) == 0x601d0f31L, "0x303 dispatcher table entry changed");
        require(programWord(0x601d0f08L) == 0xbfc11cc1L, "EQ output copy-service pointer changed");
        require(programWord(0x601d0f70L) == 0xbfc11cc1L, "0x303 payload copy-service pointer changed");
        require(programWord(0x601d0f74L) == 0x601d0d59L, "EQ builder callback pointer changed");
        require(programWord(0x601d0f78L) == 0xbfc1197dL, "message-ack pointer changed");
        require(programWord(0x60051388L) == 0xbfc11cc9L, "EQ state shared-memory service pointer changed");
        require(programWord(0x60051390L) == ENGINE_STATE_POINTER_CELL,
            "EQ state pointer-cell address changed");
        require(programWord(0x60051394L) == EQ_STATE_BASE, "EQ state base literal changed");

        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.createInitializedBlock("eq_custom_delivery_ram", addr(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int steps = 0;
        int initSteps = 0;
        int copyCalls = 0;
        int messageCalls = 0;
        int shmCalls = 0;
        int ackCalls = 0;
        byte[] copiedStructure = null;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

            byte[] initialStructure = new byte[0xf0];
            Arrays.fill(initialStructure, (byte)0xaa);
            memory.setBytes(addr(STRUCTURE), initialStructure);
            memory.setBytes(addr(CUSTOM), gains);
            memory.setBytes(addr(SELECTOR), new byte[] {0x55});
            memory.setBytes(addr(ACTIVE), new byte[] {1});
            memory.setBytes(addr(STRUCTURE + 0x0c), new byte[] {0});
            memory.setBytes(addr(ENGINE_STATE_POINTER_CELL), new byte[] {
                (byte)0xef, (byte)0xbe, (byte)0xad, (byte)0xde
            });
            memory.setBytes(addr(OUTPUT), new byte[0xf0]);
            memory.setBytes(addr(HANDLE), new byte[] {
                0x68, 0x24, 0x57, 0x13
            });
            memory.setBytes(addr(MESSAGE), new byte[] {
                0x03, 0x03, 0, 0,
                (byte)PAYLOAD, (byte)(PAYLOAD >>> 8), (byte)(PAYLOAD >>> 16), (byte)(PAYLOAD >>> 24)
            });
            memory.setBytes(addr(PAYLOAD), messagePayload);

            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", 0);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s0", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", INIT_ENTRY);

            // Execute the real state setup through the resident SHM boundary.
            // Stop at its next internal call, after it writes the EQ-state base.
            boolean initReachedNext = false;
            for (initSteps = 0; initSteps < 20000; initSteps++) {
                long pc = reg(emu, "pc");
                long target = pc & ~1L;
                if (target == INIT_NEXT) {
                    initReachedNext = true;
                    break;
                }
                if (target == SHM_SERVICE) {
                    shmCalls++;
                    require(reg(emu, "a0") == 3 && reg(emu, "a3") == 0x49,
                        "unexpected EQ state initialization shared-memory request");
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", OUTPUT);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("state init stopped at 0x%08x: %s", pc, emu.getLastError()));
            }
            require(initReachedNext, "EQ state initializer did not reach its next internal function");
            require(word(emu, ENGINE_STATE_POINTER_CELL) == EQ_STATE_BASE,
                "real state initializer did not set the EQ context pointer cell");
            require((bytes(emu, SELECTOR, 1)[0] & 0xff) == 0x55,
                "EQ selector sentinel changed before the 0x303 payload copy");

            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", MESSAGE);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s0", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", ENTRY);

            boolean returned = false;
            for (steps = 0; steps < 20000; steps++) {
                long pc = reg(emu, "pc");
                long target = pc & ~1L;
                if (target == STOP) {
                    returned = true;
                    break;
                }
                if (target == COPY_SERVICE) {
                    // This resident ROM service is unavailable; the probe models
                    // its observed copy ABI with a synthetic memcpy shim.
                    copyCalls++;
                    long a0 = reg(emu, "a0"), a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2"), a3 = reg(emu, "a3");
                    long ra = reg(emu, "ra");
                    if (a0 == EQ_STATE_BASE + 0x18 && a1 == PAYLOAD && a2 == 4 && a3 == 0) {
                        byte[] copiedPayload = bytes(emu, a1, 4);
                        emu.writeMemory(addr(a0), copiedPayload);
                    } else {
                        require(a1 == STRUCTURE && a2 == 0xf0 && a3 == 0,
                            String.format("unexpected EQ structure copy ABI %08x,%08x,%08x,%08x", a0, a1, a2, a3));
                        copiedStructure = bytes(emu, a1, 0xf0);
                        require(a0 == OUTPUT, "EQ structure copy destination mismatch");
                        emu.writeMemory(addr(a0), copiedStructure);
                    }
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (target == SHM_SERVICE) {
                    shmCalls++;
                    require(reg(emu, "a0") == 3 && reg(emu, "a3") == 0x49,
                        "unexpected shared-memory service arguments");
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0x9fc16000L);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (target == MESSAGE_SERVICE) {
                    messageCalls++;
                    require(reg(emu, "a1") == 0x1f && reg(emu, "a2") == 0 && reg(emu, "a3") == 0x49,
                        "unexpected EQ-engine notification arguments");
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (target == ACK_SERVICE) {
                    ackCalls++;
                    require(reg(emu, "a0") == MESSAGE && reg(emu, "a1") == 1,
                        "unexpected 0x303 message acknowledgement arguments");
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
            }

            require(returned, "0x303 dispatcher did not return");
            require(copyCalls == 2 && shmCalls == 2 && messageCalls == 1 && ackCalls == 1,
                String.format("unexpected service counts copy=%d shm=%d message=%d ack=%d",
                    copyCalls, shmCalls, messageCalls, ackCalls));
            require(Arrays.equals(bytes(emu, EQ_STATE_BASE + 0x18, 4), messagePayload),
                "real 0x303 handler did not copy the captured message bytes into engine state");
            require((bytes(emu, SELECTOR, 1)[0] & 0xff) == (messagePayload[1] & 0xff),
                "0x303 payload byte +1 did not set selector at state base +0x19");
            require((bytes(emu, SELECTOR_MIRROR, 1)[0] & 0xff) == 7,
                "real EQ builder did not mirror selector 7");
            require(copiedStructure != null && Arrays.equals(bytes(emu, OUTPUT, 0xf0), copiedStructure),
                "EQ builder structure was not copied through the service boundary");
            verifyStructure(copiedStructure, gains);
            println(String.format(
                "EQ CUSTOM MESSAGE-TO-BUILDER PASS init_steps=%d state_init=0x%08x state_base=0x%08x context_pointer=real-store dispatch_steps=%d dispatcher=0x%08x message=0x303 payload={%d,%d} copy_destination_overlap=selector+1 selector_after_copy_shim=0x%02x builder=0x601d0d58 custom_gain_bytes=%d bands_verified=7 resident_services=synthetic",
                initSteps, INIT_ENTRY, EQ_STATE_BASE, steps, ENTRY,
                messagePayload[0] & 0xff, messagePayload[1] & 0xff,
                bytes(emu, SELECTOR, 1)[0] & 0xff,
                gains.length));
        } finally {
            emu.dispose();
        }
    }
}

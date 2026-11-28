// Execute the real usound status callback and sender path up to opaque resident IPC calls.
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
import java.util.Arrays;

/**
 * Runs FUN_40054de0 and FUN_48494758 as real AP code. Resident services are
 * intercepted; the apparent eight-byte copy is injected only as a synthetic
 * boundary behavior and is not asserted to match the ROM implementation.
 */
public class RunUsoundManagerMessageProbe extends GhidraScript {
    private static final long CALLBACK = 0x40054de0L;
    private static final long COPY_SERVICE = 0xbfc16404L;
    private static final long RESIDENT_SERVICE = 0xbfc16400L;
    private static final long ENDPOINT_TABLE_POINTER = 0x48494674L;
    private static final long ENDPOINT_TABLE = 0x9fc1a1a4L;
    private static final long RAM = 0x9fc14000L;
    private static final int RAM_SIZE = 0x4000;
    private static final long STACK = RAM + RAM_SIZE - 0x100;
    private static final long STOP = 0x700300L;
    private static final long STATE = 0x9fc155c4L;

    private int stateReads;
    private int copies;
    private int sends;
    private byte[] sourceMessage;
    private byte[] sentMessage;
    private long copySource;
    private long copyDestination;
    private int stepCount;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private byte[] read(EmulatorHelper emu, long at, int length) throws Exception {
        byte[] bytes = emu.readMemory(addr(at), length);
        if (bytes == null || bytes.length != length)
            throw new Exception(String.format("cannot read %d bytes at 0x%08x", length, at));
        return bytes;
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void ensureRam(Memory memory) throws Exception {
        if (memory.getBlock(addr(RAM)) == null) {
            MemoryBlock ram = memory.createInitializedBlock("usound_message_probe_ram",
                addr(RAM), new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE,
                monitor, false);
            ram.setRead(true);
            ram.setWrite(true);
            ram.setExecute(true);
        }
        if (memory.getBlock(addr(ENDPOINT_TABLE)) == null) {
            MemoryBlock table = memory.createInitializedBlock("usound_endpoint_probe_table",
                addr(ENDPOINT_TABLE), new ByteArrayInputStream(new byte[0x100]),
                0x100, monitor, false);
            table.setRead(true);
            table.setWrite(true);
        }
    }

    private void returnFromResident(EmulatorHelper emu, long value) throws Exception {
        long ra = reg(emu, "ra");
        require((ra & 1L) == 1L,
            String.format("resident return address lacks MIPS16 tag: 0x%08x", ra));
        emu.writeRegister("v0", value);
        emu.writeRegister("pc", ra & ~1L);
    }

    private byte[] runCase(int input, boolean activeFlag, int expectedPayload) throws Exception {
        Memory memory = currentProgram.getMemory();
        memory.setBytes(addr(RAM), new byte[RAM_SIZE]);
        // Seed only the state bit read by the callback; the first resident
        // query is a no-op, so this is a controlled input, not ROM state.
        byte[] state = new byte[4];
        int stateValue = activeFlag ? 0x00010000 : 0;
        state[0] = (byte)stateValue;
        state[1] = (byte)(stateValue >>> 8);
        state[2] = (byte)(stateValue >>> 16);
        state[3] = (byte)(stateValue >>> 24);
        memory.setBytes(addr(STATE), state);

        MemoryBlock table = memory.getBlock(addr(ENDPOINT_TABLE));
        require(table != null && table.contains(addr(ENDPOINT_TABLE)),
            "endpoint table is not mapped in usound.ap");
        byte[] pointerBytes = new byte[4];
        memory.getBytes(addr(ENDPOINT_TABLE_POINTER), pointerBytes);
        long actualTable = (pointerBytes[0] & 0xffL) | ((pointerBytes[1] & 0xffL) << 8)
            | ((pointerBytes[2] & 0xffL) << 16) | ((pointerBytes[3] & 0xffL) << 24);
        require(actualTable == ENDPOINT_TABLE,
            String.format("endpoint pointer cell changed: 0x%08x", actualTable));
        byte[] endpoints = new byte[3 * 12];
        endpoints[0] = 1;       // active record
        endpoints[1] = 8;       // manager app ID
        endpoints[3] = 0x2a;    // synthetic endpoint/channel byte

        stateReads = copies = sends = stepCount = 0;
        sourceMessage = sentMessage = null;
        copySource = 0;
        copyDestination = 0;
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.writeMemory(addr(ENDPOINT_TABLE), endpoints);
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", input & 0xffffffffL);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s0", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", CALLBACK);

            boolean returned = false;
            for (; stepCount < 5000; stepCount++) {
                long pc = reg(emu, "pc");
                long target = pc & ~1L;
                if (target == STOP) { returned = true; break; }

                if (target == COPY_SERVICE) {
                    long dst = reg(emu, "a0");
                    long src = reg(emu, "a1");
                    long length = reg(emu, "a2");
                    long mode = reg(emu, "a3");
                    require(dst >= STACK - 0x100 && dst + 8 <= STACK + 0x100,
                        String.format("copy destination is not callback-local: 0x%08x", dst));
                    require(src >= STACK - 0x100 && src + 8 <= STACK + 0x100,
                        String.format("copy source is not callback-local: 0x%08x", src));
                    require(length == 8 && mode == 0,
                        String.format("unexpected copy-service tuple %08x,%08x,%08x,%08x", dst, src, length, mode));
                    sourceMessage = read(emu, src, 8);
                    copySource = src;
                    copyDestination = dst;
                    emu.writeMemory(addr(dst), sourceMessage);
                    copies++;
                    returnFromResident(emu, 0);
                    continue;
                }

                if (target == RESIDENT_SERVICE) {
                    long a0 = reg(emu, "a0");
                    long a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2");
                    long a3 = reg(emu, "a3");
                    if (a0 == STATE && a1 == 0x002f0000L && a2 == 4 && a3 == 0x12) {
                        stateReads++;
                        returnFromResident(emu, 0);
                        continue;
                    }
                    if (a0 == 0x2a && a2 == 0 && a3 == 0x18) {
                        long buffer = a1;
                        sentMessage = read(emu, buffer, 0x18);
                        require(sourceMessage != null && buffer == copyDestination,
                            String.format("send buffer 0x%08x differs from synthetic copy destination 0x%08x",
                                buffer, copyDestination));
                        require(Arrays.equals(sourceMessage,
                                Arrays.copyOf(sentMessage, sourceMessage.length)),
                            "the synthetic copy did not propagate all eight message bytes to send buffer");
                        for (int i = 8; i < sentMessage.length; i++)
                            require(sentMessage[i] == 0,
                                String.format("nonzero byte in synthetic zero-filled send tail at +0x%x", i));
                        println(String.format("USOUND MESSAGE SYNTHETIC COPY PASS source=%08x destination=%08x copied=%s sendLength=0x18 tail=zero-seeded-host-RAM",
                            copySource, buffer,
                            hex(sourceMessage)));
                        sends++;
                        returnFromResident(emu, 0);
                        continue;
                    }
                    throw new Exception(String.format(
                        "unexpected resident service at 0x%08x args=%08x,%08x,%08x,%08x",
                        target, a0, a1, a2, a3));
                }

                if (!emu.step(monitor))
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
            }
            require(returned, "callback did not return within instruction limit");
            require(stateReads == 2, "expected two synthetic state-query returns, got " + stateReads);
            require(copies == 1 && sends == 1,
                String.format("expected one copy/send boundary, observed copy=%d send=%d", copies, sends));
            require(sentMessage != null && sentMessage.length == 0x18
                    && sentMessage[0] == 1 && sentMessage[1] == 2,
                "callback did not construct message ID 0x0201 in little-endian order");
            long payload = (sentMessage[4] & 0xffL) | ((sentMessage[5] & 0xffL) << 8)
                | ((sentMessage[6] & 0xffL) << 16) | ((sentMessage[7] & 0xffL) << 24);
            require(payload == (expectedPayload & 0xffffffffL),
                String.format("callback payload 0x%08x != expected 0x%08x", payload, expectedPayload));
            long callbackStatus = reg(emu, "v0") & 0xffffL;
            long expectedStatus = activeFlag ? 0x861c : 0x800f;
            require(callbackStatus == expectedStatus,
                String.format("callback return 0x%04x != expected 0x%04x", callbackStatus, expectedStatus));
            println(String.format(
                "USOUND STATUS MESSAGE PASS input=0x%08x activeFlag=%d message=%s return=0x%04x stateQueries=%d syntheticCopy=1 managerEndpoint=8 endpointByte=0x2a steps=%d",
                input, activeFlag ? 1 : 0, hex(sentMessage), callbackStatus, stateReads, stepCount));
            return sentMessage;
        }
        finally { emu.dispose(); }
    }

    private String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            if (out.length() > 0) out.append(' ');
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunUsoundManagerMessageProbe.java CAPTURE_PATH");
        ensureRam(currentProgram.getMemory());
        byte[] message = runCase(0, false, 0x1100);
        Files.write(Paths.get(getScriptArgs()[0]), message);
        runCase(0x10000, false, 0x1000);
        runCase(3, false, 0x0e03);
        runCase(1, false, 0x0f09);
        byte[] stateActive = runCase(0, true, 0x4f0a);
        require(!Arrays.equals(message, stateActive), "state-bit change did not alter callback message");
        println("USOUND STATUS MESSAGE BRANCH MATRIX PASS inputs=0,0x10000,3,1 state-bit=0/1");
    }
}

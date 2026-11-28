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
import java.util.Arrays;
import java.util.HexFormat;

/** Execute the music AP's shared 0xb0 handler and its 0x861c return dispatcher. */
public class RunMusicB0App10HandoffProbe extends GhidraScript {
    private static final long EVENT_ENTRY = 0x48bd4600L;
    private static final long RETURN_ENTRY = 0x40614fc0L;
    private static final long SEND_SERVICE = 0x48494758L;
    private static final long RECORD_SERVICE = 0xbfc16bb0L;
    private static final long COPY_SERVICE = 0xbfc16400L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L;
    private static final int RAM_SIZE = 0x7000;
    private static final long STACK = RAM + RAM_SIZE - 0x100L;
    private static final long RETURN_STATE = 0x9fc15584L;
    private static final long CLEANUP_RECORD = 0x9fc15628L;
    private static final String EXPECTED_SHA256 =
        "662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3";

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private long programWord(long location) throws Exception {
        byte[] bytes = new byte[4];
        currentProgram.getMemory().getBytes(address(location), bytes);

        return word(bytes);
    }

    private long word(byte[] bytes) {
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private void returnFromService(EmulatorHelper emulator) throws Exception {
        long returnAddress = register(emulator, "ra");
        require((returnAddress & 1L) == 1L,
            String.format("service return address lacks MIPS16 tag: %08x", returnAddress));
        emulator.writeRegister("v0", 0);
        emulator.writeRegister("pc", returnAddress & ~1L);
    }

    private void setEntry(EmulatorHelper emulator, long entry, long argument) throws Exception {
        emulator.writeRegister("sp", STACK);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("a0", argument);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("s0", 0);
        emulator.writeRegister("s1", 0);
        emulator.writeRegister("pc", entry);
    }

    private int executeEvent(EmulatorHelper emulator) throws Exception {
        setEntry(emulator, EVENT_ENTRY, 0xb0);

        for (int steps = 0; steps < 10000; steps++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc == STOP) {
                int result = (int)register(emulator, "v0") & 0xffff;
                require(result == 0x861c,
                    String.format("0xb0 returned %04x instead of 861c", result));
                println(String.format("MUSIC B0 EVENT result=%04x steps=%d", result, steps));

                return result;
            }

            if (!emulator.step(monitor)) {
                throw new Exception(String.format("0xb0 handler stopped at %08x: %s",
                    pc, emulator.getLastError()));
            }
        }

        throw new Exception("0xb0 handler exceeded step limit");
    }

    private void executeReturn(EmulatorHelper emulator, int returnCode, int state,
            int expectedParameter) throws Exception {
        emulator.writeMemory(address(RETURN_STATE + 4), new byte[] {(byte)state});
        byte[] cleanup = new byte[0xa4];
        Arrays.fill(cleanup, (byte)0x5a);
        emulator.writeMemory(address(CLEANUP_RECORD), cleanup);
        setEntry(emulator, RETURN_ENTRY, returnCode);

        int sends = 0;
        int reads = 0;
        int writes = 0;
        int copies = 0;

        for (int steps = 0; steps < 20000; steps++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc == STOP) {
                require(sends == 1 && reads == 1 && writes == 1 && copies == 1,
                    String.format("service counts send=%d read=%d write=%d copy=%d",
                        sends, reads, writes, copies));
                println(String.format(
                    "MUSIC B0 HANDOFF PASS state=%02x event-return=%04x app=10 param=%02x sends=%d record-reads=%d record-writes=%d copies=%d steps=%d",
                    state, returnCode, expectedParameter, sends, reads, writes, copies, steps));

                return;
            }

            if (pc == SEND_SERVICE) {
                long operation = register(emulator, "a0");
                long pointer = register(emulator, "a1");
                require(operation == 8 && pointer >= STACK - 0x200 && pointer + 6 <= STACK,
                    String.format("unexpected launch send arguments op=%08x ptr=%08x",
                        operation, pointer));
                byte[] message = emulator.readMemory(address(pointer), 6);
                require(word(Arrays.copyOf(message, 4)) == 0x201
                        && (message[4] & 0xff) == 10
                        && (message[5] & 0xff) == expectedParameter,
                    String.format("unexpected launch message %s", HexFormat.of().formatHex(message)));
                sends++;
                returnFromService(emulator);
                continue;
            }

            if (pc == RECORD_SERVICE) {
                long pointer = register(emulator, "a0");
                long recordId = register(emulator, "a1");
                long length = register(emulator, "a2");
                long operation = register(emulator, "a3");
                require(pointer >= STACK - 0x200 && pointer + length <= STACK
                        && recordId == 0x70000 && length == 0xa8,
                    String.format("unexpected record request ptr=%08x id=%08x len=%d op=%d",
                        pointer, recordId, length, operation));
                if (operation == 0x12) {
                    reads++;
                    emulator.writeMemory(address(pointer), new byte[0xa8]);
                }
                else if (operation == 0x13) {
                    writes++;
                }
                else {
                    throw new Exception(String.format("unexpected record operation %d", operation));
                }
                returnFromService(emulator);
                continue;
            }

            if (pc == COPY_SERVICE) {
                long destination = register(emulator, "a0");
                long source = register(emulator, "a1");
                long length = register(emulator, "a2");
                long operation = register(emulator, "a3");
                require(destination >= STACK - 0x200 && destination + length <= STACK
                        && source == CLEANUP_RECORD && length == 0xa4 && operation == 0,
                    String.format("unexpected cleanup copy dst=%08x src=%08x len=%d op=%d",
                        destination, source, length, operation));
                emulator.writeMemory(address(destination),
                    emulator.readMemory(address(source), (int)length));
                copies++;
                returnFromService(emulator);
                continue;
            }

            if (!emulator.step(monitor)) {
                throw new Exception(String.format("return dispatcher stopped at %08x: %s",
                    pc, emulator.getLastError()));
            }
        }

        throw new Exception("0x861c return dispatcher exceeded step limit");
    }

    private void runCase(int state, int expectedParameter) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw operation) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            int returnCode = executeEvent(emulator);
            executeReturn(emulator, returnCode, state, expectedParameter);
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunMusicB0App10HandoffProbe.java MUSIC.AP");
        byte[] input = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(input));
        require(EXPECTED_SHA256.equals(sha256), "music.ap SHA-256 mismatch: " + sha256);
        require(currentProgram.getMemory().getBlock(address(EVENT_ENTRY)) != null
                && currentProgram.getMemory().getBlock(address(RETURN_ENTRY)) != null,
            "music shared handler or return dispatcher is not mapped");
        require(programWord(0x40615144L) == (SEND_SERVICE | 1L)
                && programWord(0x40615148L) == RETURN_STATE
                && programWord(0x40615150L) == (RECORD_SERVICE | 1L)
                && programWord(0x40615158L) == (COPY_SERVICE | 1L),
            "music return-dispatcher pool values changed");

        Memory memory = currentProgram.getMemory();
        require(memory.getBlock(address(RAM)) == null,
            "probe RAM overlaps an existing music image block");
        MemoryBlock ram = memory.createInitializedBlock("music_b0_handoff_ram", address(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        runCase(0x10, 0x42);
        runCase(0x07, 0x82);
        runCase(0x00, 0xc2);
        println("MUSIC B0 APP10 HANDOFF MATRIX PASS real-mips16=event-handler,return-dispatcher synthetic-services=message,record,copy event-producer=unverified indexing-effect=unverified");
    }
}

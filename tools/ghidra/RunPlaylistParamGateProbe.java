// Execute the real playlist update gate while replacing resident services with controlled returns.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class RunPlaylistParamGateProbe extends GhidraScript {
    private static final long ENTRY = 0x40054c54L;
    private static final long MUSIC_PASS = 0x40055194L;
    private static final long M3U_PASS = 0x400551d0L;
    private static final long CREATE_FILE = 0x40194c78L;
    private static final long FINALIZE_FILE = 0x40194fe4L;
    private static final long STOP = 0x700000L;
    private static final long STACK = 0x9fc3c000L;
    private static final long RAM = 0x9fc00000L;
    private final List<String> events = new ArrayList<>();
    private int op9Result;
    private boolean sawOp9;
    private int m3uCalls;
    private int musicCalls;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void returnFromCall(EmulatorHelper emu, long value) throws Exception {
        long ra = reg(emu, "ra");
        emu.writeRegister("v0", value);
        emu.writeRegister("pc", ra & ~1L);
    }

    private void runCase(Memory memory, long param, int serviceResult) throws Exception {
        events.clear(); sawOp9 = false; m3uCalls = 0; musicCalls = 0;
        op9Result = serviceResult;
        // Reset the AP's mutable globals touched by the update driver.
        memory.setBytes(addr(0x9fc158cdL), new byte[4]);
        memory.setBytes(addr(0x9fc15410L), new byte[4]);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", param);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            for (String name : new String[] { "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7" })
                emu.writeRegister(name, 0);
            emu.writeRegister("pc", ENTRY);

            for (int steps = 0; steps < 250000; steps++) {
                long pc = reg(emu, "pc") & ~1L;
                if (pc == STOP) {
                    boolean expectedPass = (param & 0x40) != 0 && serviceResult != -1;
                    require(sawOp9 == ((param & 0x40) != 0),
                        String.format("param 0x%02x op9-called=%s", param, sawOp9));
                    require((m3uCalls > 0) == expectedPass,
                        String.format("param 0x%02x op9=%d M3U calls=%d", param, serviceResult, m3uCalls));
                    require((musicCalls > 0) == expectedPass,
                        String.format("param 0x%02x op9=%d MUSIC calls=%d", param, serviceResult, musicCalls));
                    String expectedEvents = expectedPass
                        ? "[resident-op9, create-file, MUSIC.LIB, finalize-file, create-file, M3U.LIB, finalize-file]"
                        : sawOp9 ? "[resident-op9]" : "[]";
                    require(events.toString().equals(expectedEvents),
                        String.format("param 0x%02x event order=%s", param, events));
                    println(String.format(
                        "PLAYLIST PARAM P-CODE CASE param=0x%02x op9=%s m3u-callbacks=%d music-callbacks=%d events=%s",
                        param, sawOp9 ? Integer.toString(op9Result) : "not-called", m3uCalls, musicCalls, events));
                    return;
                }
                if (pc == M3U_PASS) {
                    m3uCalls++;
                    events.add("M3U.LIB");
                    returnFromCall(emu, 1);
                    continue;
                }
                if (pc == MUSIC_PASS) {
                    musicCalls++;
                    events.add("MUSIC.LIB");
                    returnFromCall(emu, 1);
                    continue;
                }
                if (pc == CREATE_FILE) {
                    events.add("create-file");
                    returnFromCall(emu, 1);
                    continue;
                }
                if (pc == FINALIZE_FILE) {
                    events.add("finalize-file");
                    returnFromCall(emu, 1);
                    continue;
                }
                if (pc >= 0xbfc00000L && pc < 0xbfc20000L) {
                    long a0 = reg(emu, "a0"), a1 = reg(emu, "a1"), a2 = reg(emu, "a2"), a3 = reg(emu, "a3");
                    if (pc == 0xbfc16bb0L && a0 == 1 && a1 == 0 && a2 == 0 && a3 == 9) {
                        sawOp9 = true;
                        events.add("resident-op9");
                        returnFromCall(emu, op9Result);
                    } else if (pc == 0xbfc167d8L) {
                        // Candidate resident directory walker: nonzero means one item is available.
                        returnFromCall(emu, 1);
                    } else {
                        returnFromCall(emu, 0);
                    }
                    continue;
                }
                MemoryBlock block = memory.getBlock(addr(pc));
                if (block == null || !block.isExecute()) {
                    returnFromCall(emu, 0);
                    continue;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emu.getLastError()));
            }
            throw new Exception(String.format("param 0x%02x exceeded step limit at 0x%08x",
                param, reg(emu, "pc")));
        } finally {
            emu.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunPlaylistParamGateProbe.java PLAYLIST.AP");
        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.createInitializedBlock("playlist_gate_ram", addr(RAM),
            new ByteArrayInputStream(new byte[0x40000]), 0x40000, monitor, false);
        ram.setRead(true); ram.setWrite(true); ram.setExecute(false);
        MemoryBlock stop = memory.createInitializedBlock("playlist_gate_stop", addr(STOP),
            new ByteArrayInputStream(new byte[0x100]), 0x100, monitor, false);
        stop.setRead(true); stop.setWrite(false); stop.setExecute(true);

        byte[] ap = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(getScriptArgs()[0]));
        require(ap.length > 0x5500, "playlist AP is truncated");
        long[] params = { 0x3f, 0x40, 0x41, 0x42, 0x4e, 0x7f, 0x80, 0x82, 0xc2 };
        for (long param : params) runCase(memory, param, 0);

        int[] serviceResults = {
            Integer.MIN_VALUE,
            -2,
            -1,
            0,
            1,
            Integer.MAX_VALUE,
        };
        for (int serviceResult : serviceResults) runCase(memory, 0x42, serviceResult);
        println("PLAYLIST PARAM P-CODE PASS service=resident-op9-0xbfc16bb1 semantics=synthetic-return callback=synthetic-indexer");
    }
}

import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class RunMainmenuStatusNotifierProbe extends GhidraScript {
    private static final long ENTRY = 0x48b949f4L;
    private static final long KEY_SERVICE = 0xbfc1690cL;
    private static final long MESSAGE_SERVICE = 0xbfc16918L;
    private static final long STOP = 0x700300L;
    private static final long STATE = 0x9fc1a1c8L;
    private static final long COUNTERS = 0x9fc1a180L;
    private static final long STACK = 0x10003f00L;
    private static final long OUTPUT = 0x10000200L;
    private static final String EXPECTED_SHA256 =
        "1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105";

    private final List<Integer> messages = new ArrayList<>();
    private int keyQueries;
    private int keyRefreshes;
    private int steps;
    private int level;
    private int status;

    private ghidra.program.model.address.Address address(long value) {
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

    private int readByte(EmulatorHelper emulator, long location) throws Exception {
        return emulator.readMemory(address(location), 1)[0] & 0xff;
    }

    private int readHalf(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 2);

        return (bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8);
    }

    private int readWord(EmulatorHelper emulator, long location) throws Exception {
        byte[] bytes = emulator.readMemory(address(location), 4);

        return (bytes[0] & 0xff)
            | ((bytes[1] & 0xff) << 8)
            | ((bytes[2] & 0xff) << 16)
            | ((bytes[3] & 0xff) << 24);
    }

    private void writeByte(EmulatorHelper emulator, long location, int value) throws Exception {
        emulator.writeMemory(address(location), new byte[] {(byte)value});
    }

    private void writeHalf(EmulatorHelper emulator, long location, int value) throws Exception {
        emulator.writeMemory(address(location), new byte[] {(byte)value, (byte)(value >>> 8)});
    }

    private void addOrEnableBlock(Memory memory, long start, int size, String name)
            throws Exception {
        long end = start + size;
        long cursor = start;
        int gapIndex = 0;
        List<MemoryBlock> blocks = new ArrayList<>(List.of(memory.getBlocks()));
        blocks.sort((left, right) -> Long.compare(
            left.getStart().getOffset(),
            right.getStart().getOffset()
        ));

        for (MemoryBlock block : blocks) {
            long blockStart = block.getStart().getOffset();
            long blockEnd = block.getEnd().getOffset() + 1;
            if (blockEnd <= cursor || blockStart >= end) {
                continue;
            }

            if (blockStart > cursor) {
                int gapSize = (int)(blockStart - cursor);
                MemoryBlock gap = memory.createInitializedBlock(
                    name + "_gap_" + gapIndex++,
                    address(cursor),
                    new ByteArrayInputStream(new byte[gapSize]),
                    gapSize,
                    monitor,
                    false
                );
                gap.setRead(true);
                gap.setWrite(true);
                gap.setExecute(true);
                cursor = blockStart;
            }

            block.setRead(true);
            block.setWrite(true);
            block.setExecute(true);
            cursor = Math.min(end, blockEnd);
        }

        if (cursor < end) {
            int gapSize = (int)(end - cursor);
            MemoryBlock gap = memory.createInitializedBlock(
                name + "_gap_" + gapIndex,
                address(cursor),
                new ByteArrayInputStream(new byte[gapSize]),
                gapSize,
                monitor,
                false
            );
            gap.setRead(true);
            gap.setWrite(true);
            gap.setExecute(true);
        }
    }

    private void returnFromMips16(EmulatorHelper emulator, long value) throws Exception {
        long returnAddress = register(emulator, "ra");
        require((returnAddress & 1L) == 1L,
            String.format("service return address lacks MIPS16 tag: 0x%08x", returnAddress));
        emulator.writeRegister("v0", value);
        emulator.writeRegister("pc", returnAddress & ~1L);
    }

    private void invoke(EmulatorHelper emulator) throws Exception {
        emulator.writeRegister("sp", STACK);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("a0", 0);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("pc", ENTRY);

        for (int invocationStep = 0; invocationStep < 2000; invocationStep++) {
            long pc = register(emulator, "pc") & ~1L;
            if (pc == STOP) {
                return;
            }

            if (pc == KEY_SERVICE) {
                long output = register(emulator, "a0");
                long mode = register(emulator, "a3");
                if (mode == 1) {
                    keyRefreshes++;
                    returnFromMips16(emulator, 0);
                } else {
                    require(mode == 0, "unexpected key service mode " + mode);
                    require(output != 0, "key query has a null output pointer");
                    keyQueries++;
                    writeByte(emulator, output, level);
                    returnFromMips16(emulator, status);
                }

                continue;
            }

            if (pc == MESSAGE_SERVICE) {
                long module = register(emulator, "a0");
                long payload = register(emulator, "a1");
                long operation = register(emulator, "a3");
                require(module == 5 && operation == 0x18,
                    String.format("unexpected message service args module=%d op=0x%x", module, operation));
                messages.add(readHalf(emulator, payload));
                returnFromMips16(emulator, 0);
                continue;
            }

            steps++;
            if (!emulator.step(monitor)) {
                throw new Exception(String.format(
                    "status notifier stopped at 0x%08x: %s",
                    pc,
                    emulator.getLastError()
                ));
            }
        }

        throw new Exception("status notifier did not return within 2000 instructions");
    }

    private EmulatorHelper createEmulator() throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
            @Override
            public boolean pcodeCallback(PcodeOpRaw op) {
                return true;
            }
        });
        emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

        return emulator;
    }

    private void resetScenario(EmulatorHelper emulator, int initialStatus, int initialLevel,
            int sampledStatus, int sampledLevel) throws Exception {
        messages.clear();
        keyQueries = 0;
        keyRefreshes = 0;
        steps = 0;
        status = sampledStatus;
        level = sampledLevel;
        writeByte(emulator, STATE + 0x0f, initialStatus);
        writeByte(emulator, STATE + 0x10, initialLevel);
        writeHalf(emulator, COUNTERS + 0x0c, 0);
        writeHalf(emulator, COUNTERS + 0x0e, 0);
    }

    private void verifyStatusZeroNotifier(EmulatorHelper emulator) throws Exception {
        resetScenario(emulator, 0, 0, 0, 0);

        for (int tick = 1; tick <= 119; tick++) {
            invoke(emulator);
            require(messages.isEmpty(), "0x82 emitted before tick 120: " + tick);
            require(readHalf(emulator, COUNTERS + 0x0e) == tick,
                "status-0 timer mismatch at tick " + tick);
        }

        invoke(emulator);
        require(messages.equals(List.of(0x82)), "expected one 0x82 notification at tick 120");
        require(readHalf(emulator, COUNTERS + 0x0e) == 0,
            "status-0 timer did not reset after notification");
        println("STATUS NOTIFIER CASE status=0 level=0 event=0x82 tick=120");
    }

    private void verifyStatusTwoRefresh(EmulatorHelper emulator) throws Exception {
        resetScenario(emulator, 2, 5, 2, 5);

        for (int tick = 1; tick <= 120; tick++) {
            invoke(emulator);
            require(messages.isEmpty(), "status-2 low-level notification emitted unexpectedly");
            int expectedRefreshes = tick / 8;
            require(keyRefreshes == expectedRefreshes,
                String.format("status-2 refresh count at tick %d was %d, expected %d",
                    tick, keyRefreshes, expectedRefreshes));
            require(readHalf(emulator, COUNTERS + 0x0c) == tick % 8,
                "status-2 timer did not restart at its eighth-call refresh");
        }

        println("STATUS NOTIFIER CASE status=2 level=5 refreshes=15 event=none ticks=120");
    }

    private void verifyStatusTwoNotifier(EmulatorHelper emulator) throws Exception {
        resetScenario(emulator, 2, 6, 2, 6);

        for (int tick = 1; tick <= 119; tick++) {
            invoke(emulator);
            require(messages.isEmpty(), "0xf2 emitted before tick 120: " + tick);
            require(readHalf(emulator, COUNTERS + 0x0c) == tick,
                "status-2 timer mismatch at tick " + tick);
        }

        invoke(emulator);
        require(messages.equals(List.of(0xf2)), "expected one 0xf2 notification at tick 120");
        require(readHalf(emulator, COUNTERS + 0x0c) == 0,
            "status-2 timer did not reset after notification");
        println("STATUS NOTIFIER CASE status=2 level=6 event=0xf2 tick=120");
    }

    private void verifyStatusTransitions(EmulatorHelper emulator) throws Exception {
        resetScenario(emulator, 1, 3, 0, 0);
        writeHalf(emulator, COUNTERS + 0x0c, 71);
        writeHalf(emulator, COUNTERS + 0x0e, 44);
        invoke(emulator);
        require(messages.isEmpty(), "status transition emitted an idle notification");
        require(readByte(emulator, STATE + 0x0f) == 0
                && readByte(emulator, STATE + 0x10) == 0,
            "status transition did not store the new status and level");
        require(readHalf(emulator, COUNTERS + 0x0c) == 71,
            "status-0 transition changed the status-2 timer unexpectedly");
        require(readHalf(emulator, COUNTERS + 0x0e) == 0,
            "transition to status 0, level 0 did not reset its timer");

        resetScenario(emulator, 1, 3, 2, 5);
        writeHalf(emulator, COUNTERS + 0x0c, 119);
        invoke(emulator);
        require(messages.isEmpty(), "transition into status 2 emitted a notification");
        require(readHalf(emulator, COUNTERS + 0x0c) == 0,
            "transition into status 2 did not reset its timer");
        require(readByte(emulator, STATE + 0x0f) == 2
                && readByte(emulator, STATE + 0x10) == 5,
            "transition into status 2 did not store the new state");

        resetScenario(emulator, 2, 5, 2, 6);
        writeHalf(emulator, COUNTERS + 0x0c, 119);
        invoke(emulator);
        require(messages.isEmpty(), "status-2 level change emitted a notification directly");
        require(readHalf(emulator, COUNTERS + 0x0c) == 119,
            "status-2 level change unexpectedly reset the status-2 timer");
        invoke(emulator);
        require(messages.equals(List.of(0xf2)),
            "status-2 timer did not carry across a level-only transition");
        println("STATUS NOTIFIER TRANSITIONS PASS idle_reset=1 status2_entry_reset=1 level_change_carries_timer=1");
    }

    private void verifyStatusNormalization(EmulatorHelper emulator) throws Exception {
        resetScenario(emulator, 0, 2, 0, 6);
        writeHalf(emulator, COUNTERS + 0x0e, 41);
        invoke(emulator);
        require(messages.isEmpty(), "clamped status-0 level emitted a notification");
        require(readByte(emulator, STATE + 0x10) == 2,
            "status-0 level was not capped by the saved level");
        require(readHalf(emulator, COUNTERS + 0x0e) == 0,
            "stable nonzero status-0 level did not clear its timer");

        resetScenario(emulator, 1, 3, 4, 3);
        writeHalf(emulator, COUNTERS + 0x0e, 44);
        invoke(emulator);
        require(readByte(emulator, STATE + 0x0f) == 0
                && readByte(emulator, STATE + 0x10) == 6,
            "sample status 4 was not normalized to status 0, level 6");
        require(readHalf(emulator, COUNTERS + 0x0e) == 44,
            "nonzero-level status transition reset the idle timer unexpectedly");
        invoke(emulator);
        require(readHalf(emulator, COUNTERS + 0x0e) == 0,
            "stable normalized status 0, level 6 did not clear the idle timer");
        require(messages.isEmpty(), "status normalization emitted an unexpected message");
        println("STATUS NOTIFIER NORMALIZATION PASS status4=0:6 saved_level_cap=1");
    }

    private void verifyRawLevelRefreshThreshold(EmulatorHelper emulator) throws Exception {
        resetScenario(emulator, 2, 6, 2, 7);
        writeHalf(emulator, COUNTERS + 0x0c, 7);
        invoke(emulator);
        require(messages.isEmpty(), "raw level 7 emitted an unexpected notification");
        require(keyRefreshes == 0,
            "raw level 7 triggered the below-six key refresh");
        require(readHalf(emulator, COUNTERS + 0x0c) == 8,
            "raw level 7 did not leave the status-2 timer at eight");
        require(readByte(emulator, STATE + 0x10) == 6,
            "raw level 7 was not capped to saved display level 6");
        println("STATUS NOTIFIER RAW LEVEL PASS sampled=7 normalized=6 refresh=none");
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1, "usage: RunMainmenuStatusNotifierProbe.java MAINMENU.AP");
        byte[] ap = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(ap)
        );
        require(EXPECTED_SHA256.equals(sha256), "mainmenu.ap SHA-256 mismatch: " + sha256);

        Memory memory = currentProgram.getMemory();
        require(memory.getBlock(address(ENTRY)) != null, "mainmenu shared bank is not mapped");
        addOrEnableBlock(memory, STATE, 0x100, "status_notifier_state");
        addOrEnableBlock(memory, COUNTERS, 0x100, "status_notifier_counters");
        addOrEnableBlock(memory, STACK, 0x100, "status_notifier_stack");

        EmulatorHelper emulator = createEmulator();
        try {
            verifyStatusZeroNotifier(emulator);
            verifyStatusTwoRefresh(emulator);
            verifyStatusTwoNotifier(emulator);
            verifyStatusTransitions(emulator);
            verifyStatusNormalization(emulator);
            verifyRawLevelRefreshThreshold(emulator);
        } finally {
            emulator.dispose();
        }

        require(steps > 0, "AP machine instructions were not executed");
        println(String.format(
            "MAINMENU STATUS NOTIFIER P-CODE PASS timer_ticks=360 transitions=3 normalization=2 raw_level=1 instructions=%d synthetic_services=2 hardware_semantics=unmodeled",
            steps
        ));
    }
}

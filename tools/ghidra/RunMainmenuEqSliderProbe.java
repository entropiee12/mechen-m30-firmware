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
import java.util.Arrays;
import java.util.List;

/** Runs the real mainmenu EQ slider handler with synthetic event/service boundaries. */
public class RunMainmenuEqSliderProbe extends GhidraScript {
    private static final long ENTRY = 0x40154f44L;
    private static final long READY_CHECK = 0x40154cd4L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L;
    private static final int RAM_SIZE = 0x6000;
    private static final long STACK = 0x9fc1aff0L;
    private static final long PERSISTENT_BASE = 0x9fc155f4L;
    private static final long CUSTOM = 0x9fc15620L;
    private static final long ENGINE_CUSTOM = 0x9fc1a19cL;
    private static final long READY_FLAG = 0x9fc1a1c8L;
    private static final long COPY_STUB = 0xbfc16914L;
    private static final long DRAW_STUB = 0xbfc1691cL;
    private static final long WAIT_STUB = 0xbfc16918L;
    private static final long EVENT_GET_STUB = 0xbfc164bcL;
    private static final long EVENT_DECODE_STUB = 0x485547ecL;
    private static final long EVENT_SETUP_STUB = 0x484547acL;
    private static final long MESSAGE_STUB = 0x48494678L;

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

    private byte[] read(EmulatorHelper emulator, long start, int length) throws Exception {
        return emulator.readMemory(address(start), length);
    }

    private void write(EmulatorHelper emulator, long start, byte[] data) throws Exception {
        emulator.writeMemory(address(start), data);
    }

    private long readWord(EmulatorHelper emulator, long start) throws Exception {
        byte[] data = read(emulator, start, 4);
        return (data[0] & 0xffL)
            | ((data[1] & 0xffL) << 8)
            | ((data[2] & 0xffL) << 16)
            | ((data[3] & 0xffL) << 24);
    }

    private void runCase(
        Memory memory,
        String name,
        byte[] initial,
        int editEvent,
        int exitEvent,
        Integer expectedEditedFirst
    ) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        int[] events = {editEvent, exitEvent};
        int[] eventIndex = {0};
        int[] copyCalls = {0};
        int[] eventCalls = {0};
        int[] messageCalls = {0};
        int[] readyChecks = {0};
        byte[][] sentPayload = {null};
        List<String> serviceLog = new ArrayList<>();

        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", 0);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            write(emulator, CUSTOM, initial);
            write(emulator, ENGINE_CUSTOM, new byte[7]);
            write(emulator, PERSISTENT_BASE + 0x19, new byte[] {0});
            write(emulator, READY_FLAG, new byte[] {1});
            emulator.writeRegister("pc", ENTRY);

            boolean returned = false;
            for (int step = 0; step < 20000; step++) {
                long pc = register(emulator, "pc");
                long target = pc & ~1L;
                if (target == STOP) {
                    returned = true;
                    break;
                }

                long ra = register(emulator, "ra");
                if (name.startsWith("mips16-spec-slti-") && target == 0x401551d0L) {
                    long context = register(emulator, "s1");
                    int selectedBand = read(emulator, context + 2, 1)[0] & 0xff;
                    int gain = read(emulator, context + selectedBand + 8, 1)[0];
                    int signedLessThanMinusTwo = gain < -2 ? 1 : 0;

                    // MIPS16e extended SLTI sign-extends its 16-bit immediate.
                    // Ghidra 12.2's p-code path leaves 0xfffe positive here.
                    emulator.writeRegister("t8", signedLessThanMinusTwo);
                    println(String.format(
                        "MAINMENU EQ MIPS16 SLTI OVERRIDE pc=%08x gain=%d comparison=%d (signed gain < -2)",
                        target,
                        gain,
                        signedLessThanMinusTwo
                    ));
                }

                if (target == READY_CHECK) {
                    readyChecks[0]++;
                    emulator.writeRegister("v0", 1);
                    emulator.writeRegister("pc", ra & ~1L);
                    serviceLog.add("producer-ready-check=1 (synthetic)");
                    continue;
                }

                if (target == COPY_STUB) {
                    long destination = register(emulator, "a0");
                    long source = register(emulator, "a1");
                    int length = (int)register(emulator, "a2");
                    if (destination == 0 && source == 0 && length == 0) {
                        println(String.format("MAINMENU EQ COPY NOOP ra=0x%08x", ra));
                        emulator.writeRegister("v0", 0);
                        emulator.writeRegister("pc", ra & ~1L);
                        continue;
                    }
                    require(length >= 0 && length <= 0x20, "unexpected copy length " + length);
                    byte[] copied = read(emulator, source, length);
                    require(copied != null, String.format(
                        "copy source is unmapped pc=0x%08x ra=0x%08x dst=0x%08x src=0x%08x len=%d",
                        target, ra, destination, source, length));
                    write(emulator, destination, copied);
                    copyCalls[0]++;
                    serviceLog.add(String.format("copy dst=0x%08x src=0x%08x len=%d",
                        destination, source, length));
                    emulator.writeRegister("v0", 0);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (target == EVENT_GET_STUB) {
                    eventCalls[0]++;
                    emulator.writeRegister("v0", 1);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (target == EVENT_DECODE_STUB) {
                    require(eventIndex[0] < events.length, "unexpected extra event poll");
                    long output = register(emulator, "a1");
                    int event = events[eventIndex[0]++];
                    write(emulator, output, new byte[] {(byte)event, (byte)(event >>> 8)});
                    emulator.writeRegister("v0", 1);
                    byte[] storedEvent = read(emulator, output, 2);
                    serviceLog.add(String.format("event=0x%04x out=0x%08x stored=%02x%02x",
                        event, output, storedEvent[0] & 0xff, storedEvent[1] & 0xff));
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (target == MESSAGE_STUB) {
                    long message = register(emulator, "a1");
                    require(register(emulator, "a0") == 0x0d, "unexpected message module");
                    long command = readWord(emulator, message);
                    long payload = readWord(emulator, message + 4);
                    require(command == 0x303, "unexpected message command 0x"
                        + Long.toHexString(command));
                    sentPayload[0] = read(emulator, payload, 2);
                    messageCalls[0]++;
                    serviceLog.add(String.format("message module=0x0d cmd=0x303 payload=%02x%02x",
                        sentPayload[0][0] & 0xff, sentPayload[0][1] & 0xff));
                    emulator.writeRegister("v0", 0);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (target == DRAW_STUB || target == WAIT_STUB || target == EVENT_SETUP_STUB) {
                    serviceLog.add(String.format("ui-service pc=0x%08x", target));
                    emulator.writeRegister("v0", 0);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("emulation stopped at 0x%08x: %s",
                        pc, emulator.getLastError()));
                }
            }

            require(returned, "slider handler exceeded the instruction limit");
            byte[] finalGains = read(emulator, CUSTOM, 7);
            byte[] engineGains = read(emulator, ENGINE_CUSTOM, 7);
            println(String.format("MAINMENU EQ FINAL name=%s gains=%02x,%02x,%02x,%02x,%02x,%02x,%02x",
                name, finalGains[0] & 0xff, finalGains[1] & 0xff, finalGains[2] & 0xff,
                finalGains[3] & 0xff, finalGains[4] & 0xff, finalGains[5] & 0xff,
                finalGains[6] & 0xff));
            require((read(emulator, PERSISTENT_BASE + 0x19, 1)[0] & 0xff) == 7,
                "EQ selector was not set to 7 on editor exit");
            require(eventCalls[0] == 2 && eventIndex[0] == 2, "event sequence was not consumed exactly");
            require(messageCalls[0] <= 1, "one key event emitted multiple producer messages");
            boolean canceled = exitEvent == 0x2906;
            boolean previewUpdated = messageCalls[0] == 1;
            require(copyCalls[0] >= (previewUpdated ? 4 : 2), "unexpected copy count");

            if (previewUpdated) {
                require(sentPayload[0] != null
                    && (sentPayload[0][0] & 0xff) == 7
                    && (sentPayload[0][1] & 0xff) == 7,
                    "Custom message payload was not {7,7}");
            }

            byte[] expectedSaved;
            byte[] expectedEngine;

            if (!previewUpdated) {
                expectedSaved = Arrays.copyOf(initial, 7);
                expectedEngine = new byte[7];
            }

            else if (canceled) {
                expectedSaved = Arrays.copyOf(initial, 7);
                expectedEngine = Arrays.copyOf(initial, 7);
                expectedEngine[0] = engineGains[0];
            }

            else {
                expectedSaved = Arrays.copyOf(engineGains, 7);
                expectedEngine = Arrays.copyOf(engineGains, 7);
            }

            byte[] observedEditedGains = previewUpdated
                ? Arrays.copyOf(engineGains, 7)
                : Arrays.copyOf(initial, 7);
            if (expectedEditedFirst != null) {
                require((observedEditedGains[0] & 0xff) == (expectedEditedFirst & 0xff),
                    "edited gain differs from expected value: expected=0x"
                        + Integer.toHexString(expectedEditedFirst & 0xff) + " got=0x"
                        + Integer.toHexString(observedEditedGains[0] & 0xff));
            }

            for (int index = 1; index < 7; index++) {
                require(observedEditedGains[index] == initial[index], "unselected gain changed");
            }

            require(Arrays.equals(finalGains, expectedSaved),
                "saved Custom bytes do not match confirm/cancel behavior");
            require(Arrays.equals(engineGains, expectedEngine),
                "engine Custom bytes do not match the expected preview state");

            println("MAINMENU EQ SLIDER CASE name=" + name
                + " event=0x" + Integer.toHexString(editEvent)
                + " exit_event=0x" + Integer.toHexString(exitEvent)
                + " saved_gain0=" + (byte)finalGains[0]
                + " engine_gain0=" + (byte)engineGains[0]
                + " producer_updates=" + messageCalls[0]
                + " copies=" + copyCalls[0]);
            for (String entry : serviceLog) {
                println("MAINMENU EQ SERVICE " + entry);
            }
        } finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.createInitializedBlock(
            "mainmenu_eq_slider_probe_ram",
            address(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]),
            RAM_SIZE,
            monitor,
            false
        );
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);

        byte[] baseline = {0, -1, 1, -2, 2, -3, 3, 0, 0, 0, 0, 0};
        byte[] decrementBaseline = baseline.clone();
        decrementBaseline[0] = 1;
        runCase(memory, "increment-confirm", baseline, 0x2903, 0x2905, 1);
        runCase(memory, "mips16-spec-slti-confirm", decrementBaseline, 0x2904, 0x2905, 0);
        runCase(memory, "increment-cancel", baseline, 0x2903, 0x2906, 1);
        for (int startingGain = -3; startingGain <= 3; startingGain++) {
            byte[] sweepInput = baseline.clone();
            sweepInput[0] = (byte)startingGain;
            int expectedGain = Math.max(-3, startingGain - 1);
            runCase(memory, "mips16-spec-slti-" + startingGain,
                sweepInput, 0x2904, 0x2905, expectedGain);
        }
        println("MAINMENU EQ SLIDER P-CODE PASS cases=10 input_state=synthetic services=stubbed slti_isa_override=8");
    }
}

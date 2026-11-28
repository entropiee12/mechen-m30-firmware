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

/** Runs the mainmenu 0x82 and 0xf2 message handlers with synthetic UI/event services. */
public class RunMainmenuStatusReceiverProbe extends GhidraScript {
    private static final long ENTRY = 0x48bd4600L;
    private static final long UI_SERVICE = 0xbfc1691cL;
    private static final long DELAY_SERVICE = 0xbfc16918L;
    private static final long EVENT_SERVICE = 0xbfc16528L;
    private static final long STACK = 0x10003f00L;
    private static final long STOP = 0x700300L;
    private static final String EXPECTED_SHA256 =
        "1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105";

    private final List<String> uiCalls = new ArrayList<>();
    private final List<String> delayArguments = new ArrayList<>();
    private final List<Integer> polledEvents = new ArrayList<>();
    private final List<Integer> pollAvailability = new ArrayList<>();
    private int eventPolls;
    private int delayCalls;
    private int steps;

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

    private void writeWord(EmulatorHelper emulator, long location, long value) throws Exception {
        emulator.writeMemory(address(location), new byte[] {
            (byte)value,
            (byte)(value >>> 8),
            (byte)(value >>> 16),
            (byte)(value >>> 24)
        });
    }

    private void ensureWritableRange(Memory memory, long start, int size, String name)
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
                createWritableBlock(memory, cursor, (int)(blockStart - cursor),
                    name + "_gap_" + gapIndex++);
                cursor = blockStart;
            }

            block.setRead(true);
            block.setWrite(true);
            block.setExecute(true);
            cursor = Math.min(end, blockEnd);
        }

        if (cursor < end) {
            createWritableBlock(memory, cursor, (int)(end - cursor), name + "_gap_" + gapIndex);
        }
    }

    private void createWritableBlock(Memory memory, long start, int size, String name)
            throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            name,
            address(start),
            new ByteArrayInputStream(new byte[size]),
            size,
            monitor,
            false
        );
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private void returnFromMips16(EmulatorHelper emulator, long value) throws Exception {
        long returnAddress = register(emulator, "ra");
        require((returnAddress & 1L) == 1L,
            String.format("service return address lacks MIPS16 tag: 0x%08x", returnAddress));
        emulator.writeRegister("v0", value);
        emulator.writeRegister("pc", returnAddress & ~1L);
    }

    private String uiCall(EmulatorHelper emulator) throws Exception {
        long argument = register(emulator, "a0");
        long payload = argument;
        if (argument >= STACK - 0x100 && argument + 2 <= STACK + 0x100) {
            payload = readHalf(emulator, argument);
        }

        return String.format("%04x:%08x:%08x:%08x",
            payload & 0xffffL,
            register(emulator, "a1"),
            register(emulator, "a2"),
            register(emulator, "a3"));
    }

    private int runCase(EmulatorHelper emulator, int message) throws Exception {
        uiCalls.clear();
        delayArguments.clear();
        polledEvents.clear();
        pollAvailability.clear();
        eventPolls = 0;
        delayCalls = 0;
        steps = 0;
        emulator.writeRegister("sp", STACK);
        emulator.writeRegister("ra", STOP | 1L);
        emulator.writeRegister("a0", message);
        emulator.writeRegister("a1", 0);
        emulator.writeRegister("a2", 0);
        emulator.writeRegister("a3", 0);
        emulator.writeRegister("pc", ENTRY);

        boolean returned = false;
        for (; steps < 12000; steps++) {
            long pc = register(emulator, "pc");
            long target = pc & ~1L;
            if (target == STOP) {
                returned = true;
                break;
            }

            if (target == UI_SERVICE) {
                uiCalls.add(uiCall(emulator));
                returnFromMips16(emulator, 0);
                continue;
            }

            if (target == DELAY_SERVICE) {
                long a0 = register(emulator, "a0");
                long a1 = register(emulator, "a1");
                long a2 = register(emulator, "a2");
                long a3 = register(emulator, "a3");
                delayArguments.add(String.format("%08x,%08x,%08x,%08x", a0, a1, a2, a3));
                delayCalls++;
                returnFromMips16(emulator, 0);
                continue;
            }

            if (target == EVENT_SERVICE) {
                long output = register(emulator, "a0");
                require(output >= STACK - 0x100 && output + 4 <= STACK + 0x100,
                    "event poll output is outside the handler's local stack");
                int event = switch (eventPolls) {
                    case 0 -> 0x102;
                    case 1 -> 0x101;
                    default -> 0x102;
                };
                int available = eventPolls == 0 ? 0 : 1;
                writeWord(emulator, output, event);
                eventPolls++;
                polledEvents.add(event);
                pollAvailability.add(available);
                returnFromMips16(emulator, available);
                continue;
            }

            if (!emulator.step(monitor)) {
                throw new Exception(String.format(
                    "message handler stopped at 0x%08x: %s",
                    pc,
                    emulator.getLastError()
                ));
            }
        }

        require(returned, String.format("message 0x%04x did not return", message));

        return (int)(register(emulator, "v0") & 0xffffL);
    }

    private void verifyIdleScreen(EmulatorHelper emulator) throws Exception {
        int result = runCase(emulator, 0x82);
        String eventWaitArguments = "00000001,00000000,00000000,00000031";
        long eventWaitCount = delayArguments.stream()
            .filter(eventWaitArguments::equals)
            .count();
        require(result == 0x8015,
            String.format("0x82 returned 0x%04x instead of 0x8015", result));
        require(polledEvents.equals(List.of(0x102, 0x101, 0x102))
                && pollAvailability.equals(List.of(0, 1, 1)),
            String.format("0x82 accepted an unavailable or nonterminal event: events=%s availability=%s",
                polledEvents, pollAvailability));
        require(eventWaitCount == 3,
            "0x82 did not invoke its event wait service three times: " + delayArguments);
        require(uiCalls.size() == 5, "0x82 drew an unexpected number of UI items: " + uiCalls);
        require(uiCalls.get(0).startsWith("18c3:")
                && uiCalls.get(2).startsWith("1005:")
                && uiCalls.get(3).startsWith("1006:")
                && uiCalls.get(4).startsWith("2001:"),
            "0x82 UI item sequence changed: " + uiCalls);
        println(String.format(
            "MAINMENU 0x82 RECEIVER PASS result=0x%04x polls=%s availability=%s delays=%s ui=%s",
            result, polledEvents, pollAvailability, delayArguments, uiCalls));
    }

    private void verifyStatusTwoScreen(EmulatorHelper emulator) throws Exception {
        int result = runCase(emulator, 0xf2);
        String eventWaitArguments = "00000001,00000000,00000000,00000031";
        boolean waitedForInput = delayArguments.stream().anyMatch(eventWaitArguments::equals);
        require(result == 0x8014,
            String.format("0xf2 returned 0x%04x instead of 0x8014", result));
        require(eventPolls == 0 && !waitedForInput,
            String.format("0xf2 unexpectedly waited for input: polls=%d delays=%s",
                eventPolls, delayArguments));
        require(uiCalls.size() == 5, "0xf2 drew an unexpected number of UI items: " + uiCalls);
        require(uiCalls.get(0).startsWith("18c3:")
                && uiCalls.get(2).startsWith("1008:")
                && uiCalls.get(3).startsWith("1007:")
                && uiCalls.get(4).startsWith("2000:"),
            "0xf2 UI item sequence changed: " + uiCalls);
        println(String.format(
            "MAINMENU 0xf2 RECEIVER PASS result=0x%04x polls=none ui=%s",
            result, uiCalls));
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunMainmenuStatusReceiverProbe.java MAINMENU.AP");
        byte[] ap = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(ap)
        );
        require(EXPECTED_SHA256.equals(sha256), "mainmenu.ap SHA-256 mismatch: " + sha256);

        Memory memory = currentProgram.getMemory();
        require(memory.getBlock(address(ENTRY)) != null, "mainmenu shared bank is not mapped");
        ensureWritableRange(memory, STACK, 0x100, "status_receiver_stack");
        ensureWritableRange(memory, 0x9fc1a1bcL, 1, "status_receiver_runtime_state");

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emulator.writeMemory(address(0x9fc1a1bcL), new byte[] {0});
            verifyIdleScreen(emulator);
            emulator.writeMemory(address(0x9fc1a1bcL), new byte[] {0});
            verifyStatusTwoScreen(emulator);
        } finally {
            emulator.dispose();
        }

        println("MAINMENU STATUS RECEIVER P-CODE PASS messages=0x82,0xf2 synthetic_ui_event_services=1");
    }
}

// Execute manager.ap's real key.drv service-stub boundary with a synthetic config getter and exception return.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Execute manager driver-initialization callers through resident selector stubs. */
public class RunManagerKeyDriverServiceStubProbe extends GhidraScript {
    private static final long ENTRY = 0x7f056200L;
    private static final long MANAGER_INIT = 0x7f056250L;
    private static final long CONFIG_GETTER = 0x7f0962bcL;
    private static final long SERVICE_STUB = 0xbfc13de0L;
    private static final long SERVICE_BREAK = SERVICE_STUB + 2;
    private static final long EVENT_LOOP = 0xbfc13df4L;
    private static final long DRIVER_NAME = 0x7f056240L;
    private static final long STOP = 0x700300L;
    private static final long STACK = 0x10003f00L;
    private static final long MANAGER_INIT_STACK = 0x10005f00L;
    private static final String EXPECTED_SHA256 =
        "d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a";
    private static final String EXPECTED_CONFIG_SHA256 =
        "900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19";
    private static final List<String> EXPECTED_MANAGER_INIT_SERVICE_TRACE = List.of(
        "imp=15 args=00000000/00000000/7f0562b4/0000000a",
        "imp=16 args=9fc1a1a4/00000000/0000000c/00000001",
        "imp=16 args=9fc1a1b0/00000000/0000000c/00000001",
        "imp=16 args=9fc1a1bc/00000000/0000000c/00000001",
        "imp=16 args=10005ec8/7f2163c8/0000000c/00000000",
        "imp=15 args=10005ec8/00000000/00000000/0000000d",
        "imp=16 args=9fc1a1c8/00000000/00000019/00000001",
        "imp=16 args=9fc1a180/00000000/00000012/00000001",
        "imp=16 args=9fc1a1f0/00000000/00000009/00000001",
        "imp=16 args=9fc1a1f5/000000ff/00000004/00000001",
        "imp=0 args=0000000e/10005ea4/00000001/00000006",
        "imp=15 args=7f25628c/00000000/00000002/0000004b",
        "imp=15 args=00000000/00000007/00000000/00000036",
        "imp=15 args=00000009/01000202/7f056240/0000000a",
        "imp=9 args=0000000c/00000008/00000000/00000005",
        "imp=15 args=00000000/00000000/00000000/00000033",
        "imp=16 args=9fc1a1a4/00000000/0000000c/00000001",
        "imp=15 args=00000000/00000000/00000000/00000034",
        "imp=15 args=00000000/00000000/00000000/0000001a",
        "imp=15 args=7f0562c0/00000000/00000000/00000000");

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private long readLe32(byte[] data, int offset) {
        return Integer.toUnsignedLong((data[offset] & 0xff)
            | ((data[offset + 1] & 0xff) << 8)
            | ((data[offset + 2] & 0xff) << 16)
            | ((data[offset + 3] & 0xff) << 24));
    }

    private String readCString(byte[] data, int offset) throws Exception {
        require(offset >= 0 && offset < data.length, "string offset is outside manager.ap");
        int end = offset;
        while (end < data.length && data[end] != 0) end++;
        require(end < data.length, "unterminated manager.ap string");
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private int configFirstWord(byte[] config, int item) throws Exception {
        int index = 0x10 + item * 8;
        require(item >= 0 && index + 8 <= config.length,
            String.format("config item %02x directory entry is out of bounds", item));
        long offset = readLe32(config, index);
        long length = readLe32(config, index + 4);
        require(offset + length <= config.length,
            String.format("config item %02x payload is out of bounds: %x+%x", item, offset, length));
        require(length >= 2,
            String.format("config item %02x payload is shorter than one word: %x", item, length));
        return (config[(int)offset] & 0xff) | ((config[(int)offset + 1] & 0xff) << 8);
    }

    private void requireCStringAtMemory(long runtimeAddress, String expected) throws Exception {
        byte[] actual = new byte[expected.length() + 1];
        currentProgram.getMemory().getBytes(address(runtimeAddress), actual);
        byte[] wanted = (expected + "\0").getBytes(StandardCharsets.US_ASCII);
        require(java.util.Arrays.equals(actual, wanted),
            String.format("runtime string at 0x%08x does not match %s", runtimeAddress, expected));
    }

    private void requireMips16ListingContext(long pc, String boundary) throws Exception {
        BigInteger context = currentProgram.getProgramContext().getValue(
            currentProgram.getRegister("ISA_MODE"), address(pc), false);
        require(BigInteger.ONE.equals(context),
            String.format("%s has no MIPS16 listing context at 0x%08x: %s",
                boundary, pc, context));
    }

    private void addWritableBlock(Memory memory, long start, int size, String name)
            throws Exception {
        MemoryBlock block = memory.getBlock(address(start));
        if (block == null) {
            block = memory.createInitializedBlock(name, address(start),
                new ByteArrayInputStream(new byte[size]), size, monitor, false);
        }
        require(block.getEnd().compareTo(address(start + size - 1L)) >= 0,
            String.format("existing block %s does not cover requested range %08x..%08x",
                block.getName(), start, start + size - 1L));
        block.setRead(true);
        block.setWrite(true);
    }

    private void runProfile(int value50, int value51, int value52) throws Exception {
        Memory memory = currentProgram.getMemory();
        addWritableBlock(memory, STACK - 0x100, 0x300, "manager_key_service_stub_stack");

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<Integer> getterIds = new ArrayList<>();
        int[] serviceCalls = {0};
        int[] serviceStubEntries = {0};
        long[] serviceArguments = new long[4];
        int steps;
        boolean returned = false;
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", 0);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("pc", ENTRY);

            for (steps = 0; steps < 2000; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    returned = true;
                    break;
                }

                // The overlay is decoded under LoadAP's MIPS16 program context.
                emulator.setContextRegister(
                    currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

                if (pc == CONFIG_GETTER) {
                    requireMips16ListingContext(pc, "config getter boundary");
                    long item = register(emulator, "a0");
                    require(item == 0x52 || item == 0x51 || item == 0x50,
                        String.format("unexpected config item id 0x%08x", item));
                    long ra = register(emulator, "ra");
                    require((ra & 1L) == 1L,
                        String.format("config getter return address lacks MIPS16 tag: 0x%08x", ra));
                    getterIds.add((int)item);
                    int value = item == 0x52 ? value52 : item == 0x51 ? value51 : value50;
                    emulator.writeRegister("v0", value);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (pc == SERVICE_STUB) {
                    requireMips16ListingContext(pc, "resident service stub entry");
                    serviceStubEntries[0]++;
                    require(serviceStubEntries[0] == 1,
                        "unexpected repeated resident service stub entry");
                    require(emulator.step(monitor),
                        "resident service selector instruction failed: " + emulator.getLastError());
                    long nextPc = register(emulator, "pc") & ~1L;
                    require(nextPc == SERVICE_BREAK,
                        String.format("resident selector instruction advanced to %08x, expected %08x",
                            nextPc, SERVICE_BREAK));
                    require(register(emulator, "v1") == 15,
                        String.format("resident stub loaded selector %d, expected 15",
                            register(emulator, "v1")));
                    continue;
                }

                if (pc == SERVICE_BREAK) {
                    requireMips16ListingContext(pc, "resident service break boundary");
                    require(serviceStubEntries[0] == 1,
                        "break boundary was reached without executing the resident selector stub");
                    require(serviceCalls[0] == 0, "unexpected repeated key.drv service call");
                    require(register(emulator, "v1") == 15,
                        String.format("resident service stub selected %d, expected selector 15",
                            register(emulator, "v1")));
                    for (int index = 0; index < 4; index++)
                        serviceArguments[index] = register(emulator, "a" + index);
                    long ra = register(emulator, "ra");
                    require((ra & 1L) == 1L,
                        String.format("service return address lacks MIPS16 tag: 0x%08x", ra));
                    serviceCalls[0]++;
                    emulator.writeRegister("v0", 0);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (!emulator.step(monitor))
                    throw new Exception(String.format("manager key.drv service-stub path stopped at %08x: %s",
                        pc, emulator.getLastError()));
            }

            require(returned, "key.drv caller did not return within 2000 instructions");
            require(getterIds.equals(List.of(0x52, 0x51, 0x50)),
                "unexpected config-getter sequence: " + getterIds);
            require(serviceCalls[0] == 1, "expected one intercepted resident service break");
            require(serviceStubEntries[0] == 1,
                "expected one executed resident selector stub");
            long packed = ((long)(value50 & 0xffff) << 24)
                | ((long)(value51 & 0xffff) << 8)
                | (long)(value52 & 0xffff);
            require(serviceArguments[0] == 9
                    && serviceArguments[1] == (packed & 0xffffffffL)
                    && serviceArguments[2] == DRIVER_NAME
                    && serviceArguments[3] == 10,
                String.format("unexpected service call args: %08x/%08x/%08x/%08x expected=%08x/%08x/%08x/%08x",
                    serviceArguments[0], serviceArguments[1], serviceArguments[2], serviceArguments[3],
                    9, packed, DRIVER_NAME, 10));

            println(String.format(
                "MANAGER KEY-DRIVER SERVICE-STUB PASS getter-order=%s values=%04x,%04x,%04x packed=%08x selector-v1=%02x call-args=(%08x,%08x,%08x,%08x) steps=%d exception-return=synthetic",
                getterIds, value52, value51, value50, packed,
                15, serviceArguments[0], serviceArguments[1], serviceArguments[2],
                serviceArguments[3], steps));
        }
        finally {
            emulator.dispose();
        }
    }

    private void runManagerInit(byte[] config, int value50, int value51, int value52) throws Exception {
        Memory memory = currentProgram.getMemory();
        addWritableBlock(memory, 0x9fc00000L, 0x40000, "manager_init_ram");
        addWritableBlock(memory, 0xc0020000L, 0x1000, "manager_init_mmio");
        addWritableBlock(memory, MANAGER_INIT_STACK - 0x1000L, 0x2000, "manager_init_stack");

        Map<Long, Integer> stubs = Map.of(
            0xbfc13de0L, 15,
            0xbfc13de4L, 16,
            0xbfc13de8L, 9,
            0xbfc13decL, 0,
            0xbfc13df0L, 64);
        List<String> serviceTrace = new ArrayList<>();
        List<long[]> import15Calls = new ArrayList<>();
        List<Integer> getterIds = new ArrayList<>();
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        long activeStub = -1;
        int steps;
        boolean reachedEventLoop = false;
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            emulator.writeRegister("sp", MANAGER_INIT_STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", 0);
            emulator.writeRegister("a1", 0);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("pc", MANAGER_INIT);

            for (steps = 0; steps < 10000; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == EVENT_LOOP) {
                    reachedEventLoop = true;
                    break;
                }
                emulator.setContextRegister(
                    currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

                if (pc == CONFIG_GETTER) {
                    requireMips16ListingContext(pc, "manager init config getter");
                    long item = register(emulator, "a0");
                    require(item <= 0xffff,
                        String.format("manager init requested invalid config item %08x", item));
                    int value = configFirstWord(config, (int)item);
                    getterIds.add((int)item);
                    long ra = register(emulator, "ra");
                    require((ra & 1L) == 1L, "manager init config getter RA lacks MIPS16 tag");
                    emulator.writeRegister("v0", value);
                    emulator.writeRegister("pc", ra & ~1L);
                    continue;
                }

                if (stubs.containsKey(pc)) {
                    requireMips16ListingContext(pc, "manager init resident stub entry");
                    require(activeStub == -1,
                        String.format("nested resident stub entry at %08x", pc));
                    activeStub = pc;
                    require(emulator.step(monitor),
                        "manager init selector instruction failed: " + emulator.getLastError());
                    long nextPc = register(emulator, "pc") & ~1L;
                    require(nextPc == pc + 2,
                        String.format("stub %08x advanced to %08x, expected %08x", pc, nextPc, pc + 2));
                    require(register(emulator, "v1") == stubs.get(pc),
                        String.format("stub %08x loaded selector %d, expected %d",
                            pc, register(emulator, "v1"), stubs.get(pc)));
                    continue;
                }

                if (stubs.containsKey(pc - 2) && pc == activeStub + 2) {
                    requireMips16ListingContext(pc, "manager init resident break");
                    int selector = stubs.get(activeStub);
                    require(register(emulator, "v1") == selector,
                        String.format("break at %08x has selector %d, expected %d",
                            pc, register(emulator, "v1"), selector));
                    long[] args = new long[4];
                    for (int index = 0; index < 4; index++)
                        args[index] = register(emulator, "a" + index);
                    long ra = register(emulator, "ra");
                    require((ra & 1L) == 1L,
                        String.format("manager init service RA lacks MIPS16 tag: %08x", ra));
                    serviceTrace.add(String.format("imp=%d args=%08x/%08x/%08x/%08x",
                        selector, args[0], args[1], args[2], args[3]));
                    if (selector == 15) import15Calls.add(args);
                    emulator.writeRegister("v0", 0); // exception/service return is synthetic
                    emulator.writeRegister("pc", ra & ~1L);
                    activeStub = -1;
                    continue;
                }

                if (!emulator.step(monitor))
                    throw new Exception(String.format(
                        "manager init stopped at %08x: %s", pc, emulator.getLastError()));
            }

            require(reachedEventLoop,
                String.format("manager init did not reach event loop within 10000 steps; trace=%s",
                    serviceTrace));
        requireMips16ListingContext(EVENT_LOOP, "manager init event-loop boundary");
            require(getterIds.equals(List.of(4, 5, 100, 0x52, 0x51, 0x50)),
                "unexpected manager-init config getter order: " + getterIds);
        require(serviceTrace.equals(EXPECTED_MANAGER_INIT_SERVICE_TRACE),
            "manager-init service trace differs from the hash-pinned zero-MMIO profile: "
                + serviceTrace);
            List<long[]> selector10 = import15Calls.stream()
                .filter(args -> args[3] == 10)
                .toList();
            require(selector10.size() == 2,
                "expected two import-15/a3-10 calls before event loop; trace=" + serviceTrace);
            long[] norDriver = selector10.get(0);
            long[] keyDriver = selector10.get(1);
            require(norDriver[0] == 0 && norDriver[1] == 0
                    && norDriver[2] == 0x7f0562b4L && norDriver[3] == 10,
                String.format("unexpected first selector-10 call: %08x/%08x/%08x/%08x",
                    norDriver[0], norDriver[1], norDriver[2], norDriver[3]));
            long packed = ((long)(value50 & 0xffff) << 24)
                | ((long)(value51 & 0xffff) << 8)
                | (long)(value52 & 0xffff);
            require(keyDriver[0] == 9 && keyDriver[1] == (packed & 0xffffffffL)
                    && keyDriver[2] == DRIVER_NAME && keyDriver[3] == 10,
                String.format("unexpected key-driver selector-10 call: %08x/%08x/%08x/%08x",
                    keyDriver[0], keyDriver[1], keyDriver[2], keyDriver[3]));
            println(String.format(
                "MANAGER INIT SERVICE TRACE PASS getter-order=%s selector10-calls=[(0,0,%08x,10),(9,%08x,%08x,10)] services=%s steps=%d stop-before-event-loop=true synthetic-exception-returns=true config-payloads=hash-pinned",
                getterIds, norDriver[2], packed, keyDriver[2], serviceTrace, steps));
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 2,
            "usage: RunManagerKeyDriverServiceStubProbe.java MANAGER.AP CONFIG.BIN");
        byte[] manager = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manager));
        require(EXPECTED_SHA256.equals(digest), "manager.ap SHA-256 mismatch: " + digest);
        require(readCString(manager, 0x1eb4).equals("nor_ud.drv")
                && readCString(manager, 0x1e40).equals("key.drv"),
            "manager.ap driver-name strings differ from the parent-call trace expectations");
        require(manager.length >= 0x2b8
                && readLe32(manager, 0x08) == 0x200
                && readLe32(manager, 0x0c) == 0xb8
                && readLe32(manager, 0x10) == SERVICE_STUB,
            "manager.ap resident segment does not map file 0x200..0x2b7 to the selector stub");
        byte[] expectedStubBytes = {(byte)0x0f, (byte)0x6b, (byte)0x05, (byte)0xe8};
        byte[] mappedStubBytes = new byte[expectedStubBytes.length];
        currentProgram.getMemory().getBytes(address(SERVICE_STUB), mappedStubBytes);
        require(java.util.Arrays.equals(expectedStubBytes, mappedStubBytes)
                && java.util.Arrays.equals(expectedStubBytes,
                    java.util.Arrays.copyOfRange(manager, 0x200, 0x204)),
            "resident selector stub bytes differ from the hash-pinned manager.ap segment");
        MemoryBlock stubBlock = currentProgram.getMemory().getBlock(address(SERVICE_STUB));
        require(stubBlock != null && stubBlock.getStart().getOffset() == SERVICE_STUB
                && stubBlock.getSize() == 0xb8,
            "LoadAP did not map the expected 0xb8-byte resident stub segment");
        require(currentProgram.getMemory().getBlock(address(ENTRY)) != null
                && currentProgram.getMemory().getBlock(address(MANAGER_INIT)) != null
                && currentProgram.getMemory().getBlock(address(EVENT_LOOP)) != null
                && currentProgram.getMemory().getBlock(address(CONFIG_GETTER)) != null,
            "manager entry/init/event-loop or config getter code is not mapped");
        requireMips16ListingContext(MANAGER_INIT, "manager init entry");
        require(readLe32(manager, 0x1ed8) == (EVENT_LOOP | 1L),
            "manager event-loop function pointer at file offset 0x1ed8 is unexpected");
        byte[] expectedEventLoopEntry = {(byte)0xd9, 0x64};
        byte[] eventLoopEntryImage = java.util.Arrays.copyOfRange(manager, 0x214, 0x216);
        byte[] eventLoopEntryMapped = new byte[expectedEventLoopEntry.length];
        currentProgram.getMemory().getBytes(address(EVENT_LOOP), eventLoopEntryMapped);
        require(java.util.Arrays.equals(eventLoopEntryImage, expectedEventLoopEntry)
                && java.util.Arrays.equals(eventLoopEntryMapped, expectedEventLoopEntry),
            "event-loop entry bytes do not match the hash-pinned MIPS16 prologue");
        Instruction eventLoopEntryInstruction = currentProgram.getListing()
            .getInstructionAt(address(EVENT_LOOP));
        require(eventLoopEntryInstruction != null
                && eventLoopEntryInstruction.getLength() == 2
                && "save".equalsIgnoreCase(eventLoopEntryInstruction.getMnemonicString()),
            "0xbfc13df4 is not decoded as the expected MIPS16 save instruction");
        requireMips16ListingContext(EVENT_LOOP, "manager event-loop entry");
        requireCStringAtMemory(0x7f0562b4L, "nor_ud.drv");
        requireCStringAtMemory(DRIVER_NAME, "key.drv");
        byte[] config = Files.readAllBytes(Paths.get(getScriptArgs()[1]));
        String configSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(config));
        require(EXPECTED_CONFIG_SHA256.equals(configSha256),
            "config.bin SHA-256 mismatch: " + configSha256);
        require(config.length > 0x2a8, "config.bin is truncated before items 0x50..0x52");

        // Verified config.bin layout: item directory begins at 0x10, each record is offset/length.
        int[] values = new int[3];
        int[] ids = {0x50, 0x51, 0x52};
        for (int i = 0; i < ids.length; i++) {
            values[i] = configFirstWord(config, ids[i]);
        }
        require(values[0] == 1 && values[1] == 2 && values[2] == 2,
            String.format("unexpected config item first words: %04x,%04x,%04x",
                values[0], values[1], values[2]));
        require(getScriptArgs().length == 2, "unexpected args");
        runProfile(values[0], values[1], values[2]);
        runProfile(0x11, 0x22, 0x33);
        runManagerInit(config, values[0], values[1], values[2]);
        println(String.format("MANAGER KEY-DRIVER SERVICE-STUB COMPLETE manager-sha256=%s config-sha256=%s config-items=4,5,100,50,51,52 selector-15-service-semantics=unknown",
            digest, configSha256));
    }
}

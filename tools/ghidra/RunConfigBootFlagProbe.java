import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Execute config.ap's persistent index-flag initializer and startup sender. */
public class RunConfigBootFlagProbe extends GhidraScript {
    private static final long INITIALIZER = 0x40054cccL;
    private static final long SENDER = 0x40094d84L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc14000L;
    private static final int RAM_SIZE = 0x4000;
    private static final long FLAG_RAM = 0x9fc155d0L;
    private static final long APP_RECORD_RAM = 0x9fc156ecL;
    private static final long FLAG_ID = 0x2f0000L;
    private static final long APP_RECORD_ID = 0x20000L;
    private static final long READ_WRITE_CELL = 0x40054e98L;
    private static final long FILL_COPY_CELL = 0x40054e9cL;
    private static final long SCALAR_GETTER_CELL = 0x40054eb8L;
    private static final long MENU_GETTER_CELL = 0x40054ed8L;
    private static final long AFTER_INIT_CELL = 0x40054ea4L;
    private static final long INIT_SETTINGS_CELL = 0x40054ea0L;
    private static final long INIT_DAE_CELL = 0x40054eb0L;
    private static final long COMMON_CALL_CELL = 0x40094f54L;
    private static final long SEND_CELL = 0x40094f5cL;
    private static final long SENDER_READ_WRITE_CELL = 0x40094f6cL;
    private static final long SENDER_RECORD_RAM_CELL = 0x40094f68L;
    private static final long FLAG_MASK_CELL = 0x40094f64L;
    private static final long FLAG_PTR_CELL = 0x40094f60L;
    private static final long BRANCH_ADDRESS = 0x40094e0cL;
    private static final String CONFIG_BIN_SHA256 =
        "900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19";
    private static final String COMMUNITY_CONFIG_SHA256 =
        "5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580";
    private static final String STOCK_CONFIG_SHA256 =
        "118c7f7af839901b803e331da19359ddf842fd956b4186290c727eb31bbc5470";

    private byte[] configBin;
    private String variant;
    private long recordService;
    private long fillService;
    private long scalarService;
    private long menuService;
    private long afterInitService;
    private long settingsInit;
    private long daeInit;
    private long commonService;
    private long sendService;
    private long senderRecordService;
    private long senderRecordRam;
    private long flagMask;
    private long flagPointer;
    private final Map<Integer, byte[]> persistentRecords = new HashMap<>();
    private int flagRecordWrites;
    private int settingsRecordWrites;
    private int helperReads;
    private int fillCalls;
    private int copyCalls;
    private int scalarCalls;
    private int menuCalls;
    private int afterInitCalls;
    private int commonCalls;
    private int senderRecordReads;
    private int sendCalls;
    private int sentApp;
    private int sentParam;
    private int sentMessageId;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private long programWord(long location) throws Exception {
        byte[] bytes = new byte[4];
        currentProgram.getMemory().getBytes(addr(location), bytes);
        return le32(bytes, 0);
    }

    private int programHalf(long location) throws Exception {
        byte[] bytes = new byte[2];
        currentProgram.getMemory().getBytes(addr(location), bytes);
        return u16(bytes, 0);
    }

    private int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private long le32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
            | ((data[offset + 1] & 0xffL) << 8)
            | ((data[offset + 2] & 0xffL) << 16)
            | ((data[offset + 3] & 0xffL) << 24);
    }

    private byte[] configItem(int itemId) throws Exception {
        int index = 0x10 + itemId * 8;
        require(index >= 0 && index + 8 <= configBin.length,
            String.format("config item 0x%x directory entry is outside config.bin", itemId));
        ByteBuffer table = ByteBuffer.wrap(configBin).order(ByteOrder.LITTLE_ENDIAN);
        long offset = table.getInt(index) & 0xffffffffL;
        long length = table.getInt(index + 4) & 0xffffffffL;
        require(offset + length <= configBin.length,
            String.format("config item 0x%x payload is outside config.bin", itemId));
        return Arrays.copyOfRange(configBin, (int)offset, (int)(offset + length));
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder output = new StringBuilder();
        for (byte value : digest) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }

    private byte[] read(EmulatorHelper emu, long location, int length) throws Exception {
        return emu.readMemory(addr(location), length);
    }

    private void write(EmulatorHelper emu, long location, byte[] bytes) throws Exception {
        emu.writeMemory(addr(location), bytes);
    }

    private byte[] le32(long value) {
        return new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        };
    }

    private byte[] record(int length, int magic) {
        byte[] value = new byte[length];
        if (length >= 4) {
            value[0] = (byte)magic;
            value[1] = (byte)(magic >>> 8);
            value[2] = (byte)(magic >>> 16);
            value[3] = (byte)(magic >>> 24);
        }
        return value;
    }

    private void seedRecords(boolean invalidAppRecord) {
        persistentRecords.clear();
        persistentRecords.put(0, record(0x38, 0x55aa));
        persistentRecords.put(0xf0000, record(0xf0, 0x55b9));
        persistentRecords.put((int)FLAG_ID, record(4, 0));
        persistentRecords.put((int)APP_RECORD_ID,
            record(0x14, invalidAppRecord ? 0 : 0x55ac));
        persistentRecords.put(0xd0000, new byte[0x78]);
        byte[] commonState = persistentRecords.get(0);
        commonState[0x23] = 1;
    }

    private void resetCallCounts() {
        flagRecordWrites = 0;
        settingsRecordWrites = 0;
        helperReads = 0;
        fillCalls = 0;
        copyCalls = 0;
        scalarCalls = 0;
        menuCalls = 0;
        afterInitCalls = 0;
        commonCalls = 0;
        senderRecordReads = 0;
        sendCalls = 0;
        sentApp = -1;
        sentParam = -1;
        sentMessageId = -1;
    }

    private void returnFromService(EmulatorHelper emu, long ra) throws Exception {
        require((ra & 1L) == 1L,
            String.format("service return address is not MIPS16 tagged: 0x%08x", ra));
        emu.writeRegister("pc", ra & ~1L);
    }

    private void handlePersistentService(EmulatorHelper emu, long pc) throws Exception {
        long a0 = reg(emu, "a0");
        long a1 = reg(emu, "a1");
        long a2 = reg(emu, "a2");
        long a3 = reg(emu, "a3");
        long ra = reg(emu, "ra");
        int id = (int)a1;
        int length = (int)a2;
        helperReads++;
        if (pc == (recordService & ~1L) || pc == (senderRecordService & ~1L)) {
            if (a3 == 0x12) {
                byte[] data = persistentRecords.get(id);
                if (data == null || data.length < length) {
                    write(emu, a0, new byte[length]);
                    emu.writeRegister("v0", 1);
                }
                else {
                    write(emu, a0, Arrays.copyOf(data, length));
                    emu.writeRegister("v0", 0);
                }
                if (pc == (senderRecordService & ~1L)) {
                    senderRecordReads++;
                }
            }
            else if (a3 == 0x13) {
                byte[] data = read(emu, a0, length);
                persistentRecords.put(id, data);
                emu.writeRegister("v0", 0);
                if (id == (int)FLAG_ID && length == 4) {
                    flagRecordWrites++;
                }
                if (id == (int)APP_RECORD_ID && length == 0x14) {
                    settingsRecordWrites++;
                }
            }
            else {
                throw new Exception(String.format("unknown persistence op 0x%x id=0x%x", a3,id));
            }
            returnFromService(emu, ra);
            return;
        }
        throw new Exception("unreachable persistent-service dispatch");
    }

    private void handleFillCopy(EmulatorHelper emu) throws Exception {
        long destination = reg(emu, "a0");
        long source = reg(emu, "a1");
        int length = (int)reg(emu, "a2");
        long operation = reg(emu, "a3");
        long ra = reg(emu, "ra");
        require(length >= 0 && length <= 0x1000, "fill/copy length outside probe bounds");
        if (operation == 1) {
            byte[] fill = new byte[length];
            Arrays.fill(fill, (byte)source);
            write(emu, destination, fill);
            fillCalls++;
        }
        else if (operation == 0) {
            write(emu, destination, read(emu, source, length));
            copyCalls++;
        }
        else {
            throw new Exception(String.format("unknown fill/copy selector %d", operation));
        }
        emu.writeRegister("v0", 0);
        returnFromService(emu, ra);
    }

    private void handleScalar(EmulatorHelper emu) throws Exception {
        int id = (int)reg(emu, "a0");
        byte[] item = configItem(id);
        require(item.length >= 2, "short scalar config item");
        scalarCalls++;
        emu.writeRegister("v0", u16(item, 0));
        returnFromService(emu, reg(emu, "ra"));
    }

    private void handleMenuConfig(EmulatorHelper emu) throws Exception {
        long a0 = reg(emu, "a0");
        long destination = reg(emu, "a1");
        int length = (int)reg(emu, "a2");
        byte[] item = configItem(0xe2);
        require(a0 == 0 && length == 0x24 && item.length <= length,
            String.format("unexpected menu config ABI: %08x,%08x,%08x", a0,destination,length));
        byte[] result = new byte[length];
        System.arraycopy(item, 0, result, 0, item.length);
        write(emu, destination, result);
        menuCalls++;
        emu.writeRegister("v0", 0);
        returnFromService(emu, reg(emu, "ra"));
    }

    private void initializeCallRegisters(EmulatorHelper emu, long entry, long arg0,
            long stack) throws Exception {
        emu.writeRegister("sp", stack);
        emu.writeRegister("ra", STOP | 1L);
        emu.writeRegister("a0", arg0);
        emu.writeRegister("a1", 0);
        emu.writeRegister("a2", 0);
        emu.writeRegister("a3", 0);
        emu.writeRegister("s0", 0);
        emu.writeRegister("s1", 0);
        emu.writeRegister("pc", entry);
    }

    private void seedInitializerMemory(EmulatorHelper emu) throws Exception {
        write(emu, RAM, new byte[RAM_SIZE]);
        write(emu, 0x9fc15654L, record(0x38, 0x55aa));
        write(emu, 0x9fc15700L, record(0xf0, 0x55b9));
        write(emu, FLAG_RAM, persistentRecords.get((int)FLAG_ID));
        write(emu, APP_RECORD_RAM, persistentRecords.get((int)APP_RECORD_ID));
        write(emu, 0x9fc155d8L, new byte[0x78]);
    }

    private long runInitializer(boolean invalidRecord) throws Exception {
        seedRecords(invalidRecord);
        resetCallCounts();
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            seedInitializerMemory(emu);
            initializeCallRegisters(emu, INITIALIZER, 0, RAM + RAM_SIZE - 0x100);
            boolean returned = false;
            int steps = 0;
            for (; steps < 200000; steps++) {
                long pc = reg(emu, "pc") & ~1L;
                if (pc == STOP) {
                    returned = true;
                    break;
                }
                if (pc == (recordService & ~1L)) {
                    handlePersistentService(emu, pc);
                    continue;
                }
                if (pc == (fillService & ~1L)) {
                    handleFillCopy(emu);
                    continue;
                }
                if (pc == (scalarService & ~1L)) {
                    handleScalar(emu);
                    continue;
                }
                if (pc == (menuService & ~1L)) {
                    handleMenuConfig(emu);
                    continue;
                }
                if (pc == (afterInitService & ~1L)) {
                    afterInitCalls++;
                    emu.writeRegister("v0", 0);
                    returnFromService(emu, reg(emu, "ra"));
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("config initializer stopped at %08x: %s",
                        pc, emu.getLastError()));
                }
            }
            require(returned, "config initializer exceeded its step limit");
            long flag = le32(read(emu, FLAG_RAM, 4), 0);
            byte[] storedFlag = persistentRecords.get((int)FLAG_ID);
            require(storedFlag != null && storedFlag.length == 4,
                "synthetic flag record was lost");
            long persistedFlag = le32(storedFlag, 0);
            if (invalidRecord) {
                require(flag == 0x10000 && persistedFlag == 0x10000,
                    String.format("invalid record did not set/persist flag: ram=%08x store=%08x",
                        flag, persistedFlag));
                require(flagRecordWrites == 1,
                    "invalid settings record should write flag record exactly once");
                require((persistentRecords.get((int)APP_RECORD_ID)[0] & 0xff) == 0xac
                        && (persistentRecords.get((int)APP_RECORD_ID)[1] & 0xff) == 0x55,
                    "invalid settings record was not reinitialized with magic 0x55ac");
            }
            else {
                require(flag == 0 && persistedFlag == 0,
                    "valid settings record unexpectedly set the index flag");
                require(flagRecordWrites == 0,
                    "valid settings record unexpectedly wrote the flag record");
            }
            println(String.format(
                "CONFIG INIT CASE variant=%s record=%s steps=%d flag-ram=0x%08x flag-record=0x%08x flag-writes=%d helper-reads=%d scalar=%d menu-config=%d fill=%d copy=%d",
                variant, invalidRecord ? "invalid-0x20000" : "valid-0x20000",
                steps, flag, persistedFlag, flagRecordWrites, helperReads,
                scalarCalls, menuCalls, fillCalls, copyCalls));
            return flag;
        }
        finally {
            emu.dispose();
        }
    }

    private void seedSenderRecords(EmulatorHelper emu, long flag, int storedApp) throws Exception {
        write(emu, RAM, new byte[RAM_SIZE]);
        byte[] flagData = le32(flag);
        write(emu, FLAG_RAM, flagData);
        byte[] appRecord = new byte[0x14];
        appRecord[0] = (byte)0xac;
        appRecord[1] = 0x55;
        appRecord[2] = (byte)storedApp;
        appRecord[3] = 0;
        write(emu, senderRecordRam + 2, appRecord);
        byte[] common = record(0x38, 0x55aa);
        common[0x23] = 1;
        persistentRecords.put(0, common);
    }

    private void runSender(boolean flagSet) throws Exception {
        resetCallCounts();
        long flag = flagSet ? 0x10000 : 0;
        int storedApp = 9;
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            seedSenderRecords(emu, flag, storedApp);
            initializeCallRegisters(emu, SENDER, 2, RAM + RAM_SIZE - 0x100);
            boolean returned = false;
            int steps = 0;
            for (; steps < 100000; steps++) {
                long pc = reg(emu, "pc") & ~1L;
                if (pc == STOP) {
                    returned = true;
                    break;
                }
                if (pc == (commonService & ~1L)) {
                    commonCalls++;
                    emu.writeRegister("v0", 0);
                    returnFromService(emu, reg(emu, "ra"));
                    continue;
                }
                if (pc == (senderRecordService & ~1L)) {
                    handlePersistentService(emu, pc);
                    continue;
                }
                if (pc == (sendService & ~1L)) {
                    long a0 = reg(emu, "a0");
                    long message = reg(emu, "a1");
                    require(a0 == 8,
                        String.format("unexpected send operation: a0=0x%08x", a0));
                    byte[] bytes = read(emu, message, 8);
                    sentMessageId = (int)le32(bytes, 0);
                    sentApp = bytes[4] & 0xff;
                    sentParam = bytes[5] & 0xff;
                    require(sentMessageId == 0x201,
                        String.format("unexpected startup message id 0x%x", sentMessageId));
                    sendCalls++;
                    emu.writeRegister("v0", 0);
                    returnFromService(emu, reg(emu, "ra"));
                    continue;
                }
                if (pc == (fillService & ~1L)) {
                    handleFillCopy(emu);
                    continue;
                }
                if (!emu.step(monitor)) {
                    throw new Exception(String.format("config sender stopped at %08x: %s",
                        pc, emu.getLastError()));
                }
            }
            require(returned, "config sender exceeded its step limit");
            require(sendCalls == 1,
                String.format("sender produced %d send calls", sendCalls));
            if (flagSet && "stock".equals(variant)) {
                require(sentApp == 10 && sentParam == 0x4e,
                    String.format("stock set-flag message differs: app=%d param=0x%x",
                        sentApp, sentParam));
            }
            if (!flagSet || "community".equals(variant)) {
                require(sentApp == storedApp,
                    String.format("fallback sender did not use stored app: got %d expected %d",
                        sentApp, storedApp));
            }
            println(String.format(
                "CONFIG SENDER CASE variant=%s flag=0x%05x steps=%d send=(op=8,msg=0x%08x,app=%d,param=0x%02x) helper-reads=%d common-calls=%d",
                variant, flag, steps, sentMessageId, sentApp, sentParam,
                senderRecordReads, commonCalls));
        }
        finally {
            emu.dispose();
        }
    }

    private void loadTargets() throws Exception {
        recordService = programWord(READ_WRITE_CELL) & ~1L;
        fillService = programWord(FILL_COPY_CELL) & ~1L;
        scalarService = programWord(SCALAR_GETTER_CELL) & ~1L;
        menuService = programWord(MENU_GETTER_CELL) & ~1L;
        afterInitService = programWord(AFTER_INIT_CELL) & ~1L;
        settingsInit = programWord(INIT_SETTINGS_CELL) & ~1L;
        daeInit = programWord(INIT_DAE_CELL) & ~1L;
        commonService = programWord(COMMON_CALL_CELL) & ~1L;
        sendService = programWord(SEND_CELL) & ~1L;
        senderRecordService = programWord(SENDER_READ_WRITE_CELL) & ~1L;
        senderRecordRam = programWord(SENDER_RECORD_RAM_CELL);
        flagMask = programWord(FLAG_MASK_CELL);
        flagPointer = programWord(FLAG_PTR_CELL);
        require(recordService != 0 && fillService != 0 && scalarService != 0
                && menuService != 0 && commonService != 0 && sendService != 0
                && senderRecordService != 0,
            "one or more config service pointers are empty");
        require(senderRecordRam == APP_RECORD_RAM && flagPointer == FLAG_RAM
                && flagMask == 0x10000,
            String.format("sender pool values differ: record=%08x flag=%08x mask=%08x",
                senderRecordRam, flagPointer, flagMask));
        println(String.format(
            "CONFIG SERVICE TARGETS variant=%s record=0x%08x fill=0x%08x scalar=0x%08x menu=0x%08x after-init=0x%08x init-settings=0x%08x init-dae=0x%08x common=0x%08x sender-record=0x%08x send=0x%08x flag=(0x%08x,0x%08x)",
            variant, recordService, fillService, scalarService, menuService,
            afterInitService, settingsInit, daeInit, commonService,
            senderRecordService, sendService, flagPointer, flagMask));
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 3,
            "usage: RunConfigBootFlagProbe.java CONFIG.BIN stock|community CONFIG.AP");
        configBin = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        variant = getScriptArgs()[1];
        require("stock".equals(variant) || "community".equals(variant),
            "variant must be stock or community");
        require(CONFIG_BIN_SHA256.equals(sha256(configBin)), "config.bin hash mismatch");
        String expected = "stock".equals(variant)
            ? STOCK_CONFIG_SHA256 : COMMUNITY_CONFIG_SHA256;
        String actual = sha256(Files.readAllBytes(Paths.get(getScriptArgs()[2])));
        require(expected.equals(actual),
            String.format("config.ap hash mismatch: expected %s got %s", expected, actual));
        int branch = programHalf(BRANCH_ADDRESS);
        int expectedBranch = "stock".equals(variant) ? 0x220d : 0x100d;
        require(branch == expectedBranch,
            String.format("sender branch bytes differ: expected %04x got %04x",
                expectedBranch, branch));

        Memory memory = currentProgram.getMemory();
        MemoryBlock ram = memory.createInitializedBlock("config_boot_probe_ram", addr(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);
        loadTargets();

        runInitializer(false);
        runSender(false);
        runInitializer(true);
        runSender(true);
        println(String.format(
            "CONFIG BOOT FLAG P-CODE PASS variant=%s config-ap-sha256=%s config-bin-sha256=%s branch=0x%04x record-store=synthetic message-send=synthetic hardware=not-modeled",
            variant, actual, CONFIG_BIN_SHA256, branch));
    }
}

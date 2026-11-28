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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Exhaustively measure key.drv export-table slot 3 for all MMIO byte values. */
public class RunKeyExport3RangeProbe extends GhidraScript {
    private static final long CODE_START = 0x29009400L;
    private static final int CODE_SIZE = 0x600;
    private static final long ENTRY = 0x29009584L;
    private static final long STOP = 0x700300L;
    private static final long MMIO = 0xc0020000L;
    private static final int MMIO_SIZE = 0x100;
    private static final long INPUT_BYTE = MMIO + 0x28;
    private static final long STACK = 0x10003f00L;
    private static final int STACK_SIZE = 0x200;
    private static final String EXPECTED_SHA256 =
        "858d56ff19106fccb75e35552c9cecca99dce0579c7daac5dce376d742f67ba1";

    private Address address(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (condition) {
            return;
        }

        throw new Exception(message);
    }

    private long register(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private long fileWord(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
            | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16)
            | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size
    ) throws Exception {
        MemoryBlock block = memory.getBlock(address(start));
        if (block == null) {
            block = memory.createInitializedBlock(
                name,
                address(start),
                new ByteArrayInputStream(new byte[size]),
                size,
                monitor,
                false
            );
        }

        block.setRead(true);
        block.setWrite(true);
    }

    private boolean runCase(int inputValue) throws Exception {
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });

            Register isaMode = currentProgram.getRegister("ISA_MODE");
            emulator.setContextRegister(isaMode, BigInteger.ONE);

            byte[] initialMmio = new byte[MMIO_SIZE];
            Arrays.fill(initialMmio, (byte)0xa5);
            initialMmio[(int)(INPUT_BYTE - MMIO)] = (byte)inputValue;
            emulator.writeMemory(address(MMIO), initialMmio);
            emulator.writeMemory(address(STACK - STACK_SIZE), new byte[STACK_SIZE]);

            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", inputValue);
            emulator.writeRegister("a1", ~inputValue);
            emulator.writeRegister("a2", inputValue * 0x01010101L);
            emulator.writeRegister("a3", inputValue ^ 0xa5a5a5a5L);
            emulator.writeRegister("v0", 0);
            emulator.writeRegister("pc", ENTRY);

            int steps;
            for (steps = 0; steps < 100; steps++) {
                long pc = register(emulator, "pc") & 0xfffffffeL;
                if (pc == STOP) {
                    break;
                }

                require(
                    pc >= CODE_START && pc < CODE_START + CODE_SIZE,
                    String.format("slot 3 left mapped code at pc=%08x", pc)
                );
                emulator.setContextRegister(isaMode, BigInteger.ONE);

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "slot 3 stopped at %08x: %s",
                        pc,
                        emulator.getLastError()
                    ));
                }
            }

            require(steps < 100, "slot 3 exceeded instruction limit");

            int maskedValue = inputValue & 0x3f;
            boolean expected = maskedValue >= 0x33 && maskedValue <= 0x39;
            long actual = register(emulator, "v0");
            require(actual == (expected ? 1 : 0), String.format(
                "slot 3 result mismatch for MMIO=%02x: expected=%d actual=%08x",
                inputValue,
                expected ? 1 : 0,
                actual
            ));

            byte[] finalMmio = emulator.readMemory(address(MMIO), MMIO_SIZE);
            require(Arrays.equals(initialMmio, finalMmio), String.format(
                "slot 3 modified MMIO for input %02x",
                inputValue
            ));

            return expected;
        }
        finally {
            emulator.dispose();
        }
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunKeyExport3RangeProbe.java KEY.DRV");

        byte[] driver = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "key.drv SHA-256 mismatch: " + sha256);
        require(driver.length == 0x1600, "unexpected key.drv size");
        require(fileWord(driver, 0x0c0c) == 0x29009585L,
            "export-table slot 3 does not point to 0x29009584");

        Memory memory = currentProgram.getMemory();
        require(memory.getBlock(address(ENTRY)) != null,
            "key.drv bank containing slot 3 is not mapped");
        mapWritableRegion(memory, "key_slot3_mmio", MMIO, MMIO_SIZE);
        mapWritableRegion(memory, "key_slot3_stack", STACK - STACK_SIZE, STACK_SIZE);

        int matches = 0;
        for (int inputValue = 0; inputValue <= 0xff; inputValue++) {
            if (runCase(inputValue)) {
                matches++;
            }
        }

        require(matches == 28, "unexpected number of accepted MMIO byte values: " + matches);
        println("KEY EXPORT-3 RANGE PROBE COMPLETE entry=0x29009584 tested=256 "
            + "matches=28 rule=(value&0x3f in 0x33..0x39) mmio=plain-ram "
            + "sha256=" + sha256);
    }
}

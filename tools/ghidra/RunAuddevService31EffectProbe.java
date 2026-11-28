import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Measure the aud_dev caller's response to a synthetic resident call with a3=0x31. */
public class RunAuddevService31EffectProbe extends GhidraScript {
    private static final long ENTRY = 0x9400L;
    private static final long STOP = 0x700300L;
    private static final long COPY_STUB = 0x10003000L;
    private static final long SERVICE_STUB = 0x10003100L;
    private static final long RAM = 0x10000000L;
    private static final int RAM_SIZE = 0x5000;
    private static final long SOURCE = RAM + 0x1000L;
    private static final long STACK = RAM + 0x4000L;
    private static final long STATE = 0x9fc34000L;
    private static final int STATE_SIZE = 0x200;
    private static final long CALL_GATE_FIELD = STATE + 0x28L;
    private static final long COPY_POINTER = 0x98fcL;
    private static final long SERVICE_POINTER = 0x9900L;
    private static final String EXPECTED_SHA256 =
        "3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c";

    private final List<String> serviceCalls = new ArrayList<>();
    private int copyCallCount;
    private boolean clearCallGateAtService;
    private long serviceReturnValue;

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

    private byte[] read(EmulatorHelper emulator, long start, int length) throws Exception {
        return emulator.readMemory(address(start), length);
    }

    private long readWord(EmulatorHelper emulator, long start) throws Exception {
        byte[] bytes = read(emulator, start, 4);
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    private long fileWord(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
            | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16)
            | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private String stateHashExcludingCallGate(byte[] state) throws Exception {
        int gateOffset = (int)(CALL_GATE_FIELD - STATE);
        byte[] comparedState = new byte[state.length - 4];
        System.arraycopy(state, 0, comparedState, 0, gateOffset);
        System.arraycopy(state, gateOffset + 4, comparedState, gateOffset,
            state.length - gateOffset - 4);

        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(comparedState));
    }

    private void writeWord(EmulatorHelper emulator, long start, long value) throws Exception {
        emulator.writeMemory(address(start), new byte[] {
            (byte)value,
            (byte)(value >>> 8),
            (byte)(value >>> 16),
            (byte)(value >>> 24)
        });
    }

    private void mapWritableRegion(
        Memory memory,
        String name,
        long start,
        int size,
        boolean executable
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
        if (executable) {
            block.setExecute(true);
        }
    }

    private void requireDriverImage(byte[] driver) throws Exception {
        require(driver.length >= 0x2500, "aud_dev.drv is truncated before bank 5");
        require(fileWord(driver, 0x23f8) == STATE
                && fileWord(driver, 0x23fc) == 0xbfc09f59L
                && fileWord(driver, 0x2400) == 0xbfc09f5dL,
            "bank-5 state or resident-service pool words differ from the expected image");
        byte[] mappedCode = new byte[0x600];
        currentProgram.getMemory().getBytes(address(ENTRY), mappedCode);
        byte[] expectedCode = Arrays.copyOfRange(driver, 0x1f00, 0x2500);
        require(Arrays.equals(mappedCode, expectedCode),
            "loaded bank-5 code differs from the hash-checked driver file");
    }

    private void prepareState(EmulatorHelper emulator, long gateValue) throws Exception {
        emulator.writeMemory(address(RAM), new byte[RAM_SIZE]);

        byte[] source = new byte[0xf0];
        source[7] = 1;
        for (int band = 0; band < 16; band++) {
            for (int offset = 0; offset < 8; offset++) {
                source[0x10 + band * 8 + offset] = (byte)(band * 8 + offset + 1);
            }
        }
        emulator.writeMemory(address(SOURCE), source);

        byte[] state = new byte[STATE_SIZE];
        emulator.writeMemory(address(STATE), state);
        writeWord(emulator, CALL_GATE_FIELD, gateValue);
        emulator.writeMemory(address(STACK), new byte[0x1000]);
    }

    private String executeCase(long initialGate, long syntheticReturn, boolean clearGate)
            throws Exception {
        serviceCalls.clear();
        copyCallCount = 0;
        serviceReturnValue = syntheticReturn;
        clearCallGateAtService = clearGate;

        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override
                public boolean pcodeCallback(PcodeOpRaw op) {
                    return true;
                }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            prepareState(emulator, initialGate);

            emulator.writeRegister("sp", STACK + 0x1000L);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", 1);
            emulator.writeRegister("a1", SOURCE);
            emulator.writeRegister("a2", 0);
            emulator.writeRegister("a3", 0);
            emulator.writeRegister("s0", 0);
            emulator.writeRegister("s1", 0);
            emulator.writeRegister("v0", 0);
            emulator.writeRegister("pc", ENTRY);

            List<String> externalPcs = new ArrayList<>();
            int steps;
            for (steps = 0; steps < 100000; steps++) {
                long pc = register(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    break;
                }

                if (pc == SERVICE_STUB) {
                    handleService(emulator);
                    continue;
                }

                if (pc == COPY_STUB) {
                    handleCopy(emulator);
                    continue;
                }

                if (pc >= 0x9400L && pc < 0x9a00L) {
                    emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                        BigInteger.ONE);
                }
                else {
                    externalPcs.add(String.format("%08x", pc));
                }

                if (!emulator.step(monitor)) {
                    throw new Exception(String.format(
                        "aud_dev bank-5 execution stopped at %08x: %s",
                        pc,
                        emulator.getLastError()
                    ));
                }
            }

            require(steps < 100000, "aud_dev bank-5 handler exceeded instruction limit");
            require(externalPcs.isEmpty(),
                "aud_dev execution left the mapped bank: " + externalPcs);

            byte[] finalState = read(emulator, STATE, STATE_SIZE);
            long finalGate = readWord(emulator, CALL_GATE_FIELD);
            long finalResult = register(emulator, "v0");
            String calls = serviceCalls.isEmpty() ? "none" : String.join("|", serviceCalls);

            if (initialGate == 0) {
                require(serviceCalls.isEmpty() && copyCallCount == 16
                        && finalGate == 1 && finalResult == 0,
                    "clear call gate did not skip the resident call and complete 16 band copies");
            }
            else if (clearGate) {
                require(serviceCalls.size() == 1 && copyCallCount == 16
                        && finalGate == 1 && finalResult == 0,
                    "synthetic call-gate clear did not resume the EQ path");
            }
            else {
                require(serviceCalls.size() == 1 && copyCallCount == 0
                        && finalGate == initialGate && finalResult == 0xffffffffL,
                    String.format("unchanged call gate did not produce the early failure path: "
                        + "initial=%08x final=%08x result=%08x",
                        initialGate, finalGate, finalResult));
            }

            return String.format(
                "gate=%08x>%08x service-return=%08x service-clears-gate=%s steps=%d "
                    + "result=%08x service-calls=%d copy-calls=%d other-state-sha256=%s calls=%s",
                initialGate,
                finalGate,
                syntheticReturn,
                clearGate ? "yes-synthetic" : "no",
                steps,
                finalResult,
                serviceCalls.size(),
                copyCallCount,
                stateHashExcludingCallGate(finalState),
                calls
            );
        }
        finally {
            emulator.dispose();
        }
    }

    private void handleService(EmulatorHelper emulator) throws Exception {
        long a0 = register(emulator, "a0");
        long a1 = register(emulator, "a1");
        long a2 = register(emulator, "a2");
        long a3 = register(emulator, "a3");
        long ra = register(emulator, "ra");
        require(a0 == 2 && a1 == 0 && a2 == 0 && a3 == 0x31,
            String.format("unexpected resident-service call ABI: %08x/%08x/%08x/%08x",
                a0, a1, a2, a3));
        require((ra & 1L) == 1L,
            String.format("resident-service return address is not MIPS16 tagged: %08x", ra));
        serviceCalls.add(String.format(
            "target=0xbfc09f5c args=%08x/%08x/%08x/%08x ra=%08x",
            a0, a1, a2, a3, ra
        ));

        if (clearCallGateAtService) {
            writeWord(emulator, CALL_GATE_FIELD, 0);
        }

        emulator.writeRegister("v0", serviceReturnValue);
        emulator.writeRegister("pc", ra & ~1L);
    }

    private void handleCopy(EmulatorHelper emulator) throws Exception {
        long destination = register(emulator, "a0");
        long source = register(emulator, "a1");
        long length = register(emulator, "a2");
        long flag = register(emulator, "a3");
        long ra = register(emulator, "ra");
        require(length == 8 && flag == 0,
            String.format("unexpected candidate copy-stub ABI len=%d flag=%d", length, flag));

        boolean isBandCopy = destination >= 0x9fc34094L
            && destination + length <= 0x9fc34114L
            && source >= SOURCE + 0x10L
            && source + length <= SOURCE + 0x90L;
        require(isBandCopy,
            String.format("copy-stub call outside expected EQ bands: dst=%08x src=%08x len=%d",
                destination, source, length));

        byte[] bytes = read(emulator, source, (int)length);
        emulator.writeMemory(address(destination), bytes);
        copyCallCount++;
        emulator.writeRegister("v0", 0);
        emulator.writeRegister("pc", ra & ~1L);
    }

    @Override
    public void run() throws Exception {
        require(getScriptArgs().length == 1,
            "usage: RunAuddevService31EffectProbe.java AUD_DEV.DRV");
        byte[] driver = java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(getScriptArgs()[0]));
        String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(driver));
        require(EXPECTED_SHA256.equals(sha256), "aud_dev.drv SHA-256 mismatch: " + sha256);
        requireDriverImage(driver);

        Memory memory = currentProgram.getMemory();
        MemoryBlock code = memory.getBlock(address(ENTRY));
        require(code != null, "mapped aud_dev bank-5 code is missing");
        code.setWrite(true);
        memory.setBytes(address(COPY_POINTER), new byte[] {
            (byte)(COPY_STUB | 1L), (byte)((COPY_STUB | 1L) >>> 8),
            (byte)((COPY_STUB | 1L) >>> 16), (byte)((COPY_STUB | 1L) >>> 24)
        });
        memory.setBytes(address(SERVICE_POINTER), new byte[] {
            (byte)(SERVICE_STUB | 1L), (byte)((SERVICE_STUB | 1L) >>> 8),
            (byte)((SERVICE_STUB | 1L) >>> 16), (byte)((SERVICE_STUB | 1L) >>> 24)
        });

        mapWritableRegion(memory, "auddev_service31_probe_ram", RAM, RAM_SIZE, true);
        mapWritableRegion(memory, "auddev_service31_probe_state", STATE, STATE_SIZE, false);

        String clearGate = executeCase(0, 0, false);
        String serviceReturnZero = executeCase(1, 0, false);
        String serviceReturnOne = executeCase(1, 1, false);
        String serviceReturnNegative = executeCase(1, 0xffffffffL, false);
        String gateTwo = executeCase(2, 0, false);
        String gateHighByte = executeCase(0x100L, 0, false);
        String gateAllBits = executeCase(0xffffffffL, 0, false);
        String syntheticClearReturnZero = executeCase(1, 0, true);
        String syntheticClearReturnOne = executeCase(1, 1, true);
        String syntheticClearReturnNegative = executeCase(1, 0xffffffffL, true);
        String normalizedReturnZero = serviceReturnZero.replaceAll(
            "service-return=[0-9a-f]{8}", "service-return=<synthetic>");
        String normalizedReturnOne = serviceReturnOne.replaceAll(
            "service-return=[0-9a-f]{8}", "service-return=<synthetic>");
        String normalizedReturnNegative = serviceReturnNegative.replaceAll(
            "service-return=[0-9a-f]{8}", "service-return=<synthetic>");
        String normalizedGateOne = normalizeNonzeroGate(serviceReturnZero);
        String normalizedGateTwo = normalizeNonzeroGate(gateTwo);
        String normalizedGateHighByte = normalizeNonzeroGate(gateHighByte);
        String normalizedGateAllBits = normalizeNonzeroGate(gateAllBits);
        String normalizedClearReturnZero = syntheticClearReturnZero.replaceAll(
            "service-return=[0-9a-f]{8}", "service-return=<synthetic>");
        String normalizedClearReturnOne = syntheticClearReturnOne.replaceAll(
            "service-return=[0-9a-f]{8}", "service-return=<synthetic>");
        String normalizedClearReturnNegative = syntheticClearReturnNegative.replaceAll(
            "service-return=[0-9a-f]{8}", "service-return=<synthetic>");
        require(normalizedReturnZero.equals(normalizedReturnOne)
                && normalizedReturnZero.equals(normalizedReturnNegative),
            "synthetic resident-service return changed caller-visible state or control flow");
        require(normalizedGateOne.equals(normalizedGateTwo)
                && normalizedGateOne.equals(normalizedGateHighByte)
                && normalizedGateOne.equals(normalizedGateAllBits),
            "nonzero 32-bit call-gate values changed caller-visible state or control flow");
        require(normalizedClearReturnZero.equals(normalizedClearReturnOne)
                && normalizedClearReturnZero.equals(normalizedClearReturnNegative),
            "resident-service return changed caller-visible state after the gate was cleared");

        println("AUDDEV SERVICE-31 EFFECT " + clearGate);
        println("AUDDEV SERVICE-31 EFFECT " + serviceReturnZero);
        println("AUDDEV SERVICE-31 EFFECT " + serviceReturnOne);
        println("AUDDEV SERVICE-31 EFFECT " + serviceReturnNegative);
        println("AUDDEV SERVICE-31 EFFECT " + gateTwo);
        println("AUDDEV SERVICE-31 EFFECT " + gateHighByte);
        println("AUDDEV SERVICE-31 EFFECT " + gateAllBits);
        println("AUDDEV SERVICE-31 EFFECT " + syntheticClearReturnZero);
        println("AUDDEV SERVICE-31 EFFECT " + syntheticClearReturnOne);
        println("AUDDEV SERVICE-31 EFFECT " + syntheticClearReturnNegative);
        println("AUDDEV SERVICE-31 EFFECT PROBE COMPLETE "
            + "target=0xbfc09f5c service-return-sweep=3 call-gate-clear=synthetic "
            + "copy-stub=synthetic-memcpy mmio=not-modeled");
    }

    private String normalizeNonzeroGate(String result) {
        return result.replaceFirst(
            "gate=[0-9a-f]{8}>[0-9a-f]{8}", "gate=<nonzero>");
    }
}

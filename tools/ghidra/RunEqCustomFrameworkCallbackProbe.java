// Execute Custom-row registration, then drive the real event wrapper and
// deferred-callback dispatcher with a synthetic decoded event.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class RunEqCustomFrameworkCallbackProbe extends GhidraScript {
    private static final long ENTRY = 0x403d4c1cL;
    private static final long EVENT_WRAPPER = 0x40c14f30L;
    private static final long FRAMEWORK_DISPATCH = 0x487d44b0L;
    private static final long EVENT_READY_HELPER = 0xbfc16ae8L;
    private static final long EVENT_DECODE_HELPER = 0x485547ecL;
    private static final long REGISTERED_CALLBACK = 0x403d4c01L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L;
    private static final long RAM_SIZE = 0x7000L;
    private static final long STACK = 0x9fc1aff0L;
    private static final long POINTER_STATE = 0x403d4cb8L;
    private static final long POINTER_FLAG = 0x403d4cc0L;
    private static final long POINTER_ARG0 = 0x403d4cc4L;
    private static final long POINTER_OUT0 = 0x403d4ce4L;
    private static final long POINTER_OUT1 = 0x403d4ce8L;
    private static final long COPY_DEST_SLOT = 0x403d4ef4L;
    private static final long COPY_SOURCE_SLOT = 0x403d4ef8L;
    private static final long COPY_ENTRY_SLOT = 0x403d4efcL;
    private static final long MESSAGE_ENTRY_SLOT = 0x40614f2cL;
    private static final long ENGINE_CUSTOM = 0x9fc1a19cL;
    private static final long PARAM = RAM + 0x2000;
    private static final int INDEX = 7;
    private static final int[] GAINS = {0x31, 0x42, 0x53, 0x64, 0x75, 0x26, 0x17};

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private byte[] bytes(EmulatorHelper emu, long at, int length) throws Exception {
        return emu.readMemory(addr(at), length);
    }

    private long word(EmulatorHelper emu, long at) throws Exception {
        byte[] value = bytes(emu, at, 4);
        return (value[0] & 0xffL) | ((value[1] & 0xffL) << 8)
            | ((value[2] & 0xffL) << 16) | ((value[3] & 0xffL) << 24);
    }

    private long u16(EmulatorHelper emu, long at) throws Exception {
        byte[] value = bytes(emu, at, 2);
        return (value[0] & 0xffL) | ((value[1] & 0xffL) << 8);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private void requireRamPointer(long pointer, String label) throws Exception {
        require(pointer >= RAM && pointer < RAM + RAM_SIZE,
            label + " does not resolve into synthetic RAM: 0x" + Long.toHexString(pointer));
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length > 1)
            throw new Exception("usage: RunEqCustomFrameworkCallbackProbe.java [CAPTURE.bin]");
        Path capturePath = getScriptArgs().length == 1 ? Paths.get(getScriptArgs()[0]) : null;

        MemoryBlock ram = currentProgram.getMemory().createInitializedBlock(
            "eq_custom_row_callback_ram", addr(RAM),
            new ByteArrayInputStream(new byte[(int)RAM_SIZE]), RAM_SIZE, monitor, false);
        ram.setRead(true);
        ram.setWrite(true);
        ram.setExecute(true);
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) block.setWrite(true);

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        int steps = 0;
        int nestedCalls = 0;
        int copyCalls = 0;
        int messageCalls = 0;
        int frameworkCallbackCalls = 0;
        int eventReadyCalls = 0;
        int eventDecodeCalls = 0;
        int frameworkDispatchCalls = 0;
        int phase = 0;
        long result = 0;
        long frameworkCallbackReturn = 0;
        byte[] messagePayloadCaptured = null;
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);

            long state = word(emu, POINTER_STATE);
            long flag = word(emu, POINTER_FLAG);
            long arg0 = word(emu, POINTER_ARG0);
            long out0 = word(emu, POINTER_OUT0);
            long out1 = word(emu, POINTER_OUT1);
            long copyDestination = word(emu, COPY_DEST_SLOT);
            long copySource = word(emu, COPY_SOURCE_SLOT);
            long copyEntry = word(emu, COPY_ENTRY_SLOT);
            long messageEntry = word(emu, MESSAGE_ENTRY_SLOT);
            requireRamPointer(state, "selector state pointer");
            requireRamPointer(flag, "Custom-row flag pointer");
            requireRamPointer(out0, "callback registration output 0 pointer");
            requireRamPointer(out1, "callback registration output 1 pointer");
            requireRamPointer(copyDestination, "engine Custom buffer pointer");
            requireRamPointer(copySource, "persistent Custom source pointer");
            require(copyDestination == ENGINE_CUSTOM, "unexpected engine Custom destination");
            require(copySource == state + 0x2c, "Custom source does not point at persistent +0x2c");
            require((copyEntry & 1L) == 1L, "Custom copy service pointer is not MIPS16 tagged");
            require((messageEntry & 1L) == 1L, "EQ message service pointer is not MIPS16 tagged");
            require(arg0 == 0x403d4c01L,
                String.format("unexpected registered callback pointer: 0x%08x", arg0));

            emu.writeMemory(addr(state + 0x19), new byte[] {(byte)INDEX});
            byte[] gainSentinel = new byte[GAINS.length];
            for (int i = 0; i < GAINS.length; i++) gainSentinel[i] = (byte)GAINS[i];
            emu.writeMemory(addr(state + 0x2c), gainSentinel);
            emu.writeMemory(addr(flag), new byte[] {(byte)0x55});
            emu.writeMemory(addr(out0), new byte[] {0x55, 0x55, 0x55, 0x55});
            emu.writeMemory(addr(out1), new byte[] {0x66, 0x66, 0x66, 0x66});
            emu.writeMemory(addr(PARAM), new byte[0x20]);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", PARAM);
            emu.writeRegister("pc", ENTRY);

            for (steps = 0; steps < 10000; steps++) {
                long pc = reg(emu, "pc");
                long normalizedPc = pc & ~1L;
                if (normalizedPc == STOP) {
                    result = reg(emu, "v0");
                    if (phase == 0) {
                        require(result == 0x8202, "Custom-row selection callback result mismatch");
                        require((bytes(emu, flag, 1)[0] & 0xff) == 1,
                            "Custom-row selection callback did not set its registered flag to 1");
                        require(word(emu, out0) == arg0 && word(emu, out1) == flag,
                            "Custom-row selection callback did not register the expected callback/flag pointers");
                        phase = 1;
                        // Enter the real event wrapper. Its event-source and
                        // decode helpers are intercepted below; all wrapper,
                        // dispatch, and callback instructions remain real.
                        emu.writeRegister("a0", PARAM + 0x100);
                        emu.writeRegister("a1", PARAM + 0x300);
                        emu.writeRegister("a2", PARAM + 0x500);
                        emu.writeRegister("ra", STOP | 1L);
                        emu.writeRegister("pc", EVENT_WRAPPER);
                        continue;
                    }
                    break;
                }
                if (normalizedPc == EVENT_READY_HELPER) {
                    eventReadyCalls++;
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", 1);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (normalizedPc == EVENT_DECODE_HELPER) {
                    eventDecodeCalls++;
                    long output = reg(emu, "a1");
                    long ra = reg(emu, "ra");
                    emu.writeMemory(addr(output), new byte[] {0x04, 0x26, 0, 0, 0, 0, 0, 0});
                    emu.writeRegister("v0", 1);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (normalizedPc == FRAMEWORK_DISPATCH) frameworkDispatchCalls++;
                if (normalizedPc == (REGISTERED_CALLBACK & ~1L)) {
                    frameworkCallbackCalls++;
                    frameworkCallbackReturn = reg(emu, "ra") & ~1L;
                    println("FRAMEWORK ENTERS REGISTERED CUSTOM CALLBACK");
                }
                if (frameworkCallbackCalls == 1 && messageCalls == 1
                    && normalizedPc == frameworkCallbackReturn) {
                    println(String.format("FRAMEWORK CALLBACK RETURN observed at 0x%08x", normalizedPc));
                    emu.writeRegister("pc", STOP);
                    continue;
                }
                if (normalizedPc == 0x403d4c00L || normalizedPc == 0x40094d4cL || normalizedPc == 0x403d4ed0L)
                    println(String.format("EQ CALLBACK PATH pc=0x%08x selector=%d", normalizedPc,
                        bytes(emu, state + 0x19, 1)[0] & 0xff));
                if (normalizedPc == 0x403d4cc8L) nestedCalls++;
                if (normalizedPc == (copyEntry & ~1L)) {
                    long a0 = reg(emu, "a0");
                    long a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2");
                    long a3 = reg(emu, "a3");
                    long ra = reg(emu, "ra");
                    if (a0 == copyDestination && a1 == copySource && a2 == GAINS.length && a3 == 0) {
                        copyCalls++;
                        byte[] copied = bytes(emu, a1, (int)a2);
                        emu.writeMemory(addr(a0), copied);
                    } else {
                        println(String.format("OTHER COPY-SERVICE CALL a0=0x%08x a1=0x%08x a2=%d a3=0x%08x",
                            a0, a1, a2, a3));
                    }
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (normalizedPc == (messageEntry & ~1L)) {
                    messageCalls++;
                    long payload = reg(emu, "a0");
                    require(reg(emu, "a2") == 0x303, "EQ message selector is not 0x303");
                    byte[] message = bytes(emu, payload, 2);
                    require((message[0] & 0xff) == 7 && (message[1] & 0xff) == 7,
                        "Custom producer message is not {7,7}");
                    messagePayloadCaptured = bytes(emu, payload, 4);
                    long ra = reg(emu, "ra");
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", ra & ~1L);
                    continue;
                }
                if (!emu.step(monitor))
                    throw new Exception(String.format("stopped at 0x%08x: %s",
                        pc, emu.getLastError()));
            }

            byte[] observedGains = bytes(emu, state + 0x2c, GAINS.length);
            println(String.format("FRAMEWORK COUNTS callback=%d setter=%d copy=%d message=%d flag=%d selector=%d return=0x%04x",
                frameworkCallbackCalls, nestedCalls, copyCalls, messageCalls,
                bytes(emu, flag, 1)[0] & 0xff, bytes(emu, state + 0x19, 1)[0] & 0xff,
                result & 0xffff));
            require((reg(emu, "pc") & ~1L) == STOP, "Custom-row callback did not return");
            require(u16(emu, PARAM + 6) == 0xffff, "callback result descriptor +6 was not set to 0xffff");
            require(Arrays.equals(observedGains, gainSentinel), "callback changed persistent Custom gain bytes");
            require(nestedCalls == 1, "expected exactly one real FUN_403d4cc8 call");
            require(copyCalls == 1 && messageCalls == 1, "registered Custom callback did not reach copy/message boundaries");
            require(frameworkCallbackCalls == 1, "actual deferred-callback dispatcher did not invoke the registered Custom callback exactly once");
            require(eventReadyCalls == 1 && eventDecodeCalls == 1 && frameworkDispatchCalls == 1,
                "real event wrapper did not reach the callback dispatcher exactly once");
            require(word(emu, out0) == arg0 && word(emu, out1) == flag,
                "callback registration setter did not copy the real arguments");
            require(Arrays.equals(bytes(emu, ENGINE_CUSTOM, GAINS.length), gainSentinel),
                "Custom gain bytes did not reach the engine buffer under the copy shim");
            require(messagePayloadCaptured != null, "Custom producer message payload was not captured");

            if (capturePath != null) {
                byte[] engineSeed = new byte[4 + GAINS.length];
                System.arraycopy(messagePayloadCaptured, 0, engineSeed, 0, 4);
                System.arraycopy(bytes(emu, ENGINE_CUSTOM, GAINS.length), 0,
                    engineSeed, 4, GAINS.length);
                Path parent = capturePath.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(capturePath, engineSeed);
            }

            println(String.format(
                "EQ CUSTOM FRAMEWORK CALLBACK PASS steps=%d row_result=0x8202 row_flag_set=1 selector=%d callback=0x%08x event_wrapper=0x%08x event=0x2604:synthetic-helper dispatcher=0x%08x dispatches=%d callback_invokes=%d callback_return=observed message=0x303:{7,7} module10_copy=synthetic setter_calls=%d copy_calls=%d message_calls=%d flag_cell=0x%08x",
                steps, INDEX, arg0, EVENT_WRAPPER, FRAMEWORK_DISPATCH, frameworkDispatchCalls,
                frameworkCallbackCalls, nestedCalls, copyCalls, messageCalls, flag));
        } finally {
            emu.dispose();
        }
    }
}

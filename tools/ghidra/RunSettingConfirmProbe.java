// Trace setting.ap confirmation and synthetic event paths; host only, no device state changes.
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

public class RunSettingConfirmProbe extends GhidraScript {
    private static final long ENTRY = 0x489146e8L;
    private static final long MODE_HELPER = 0x48914a10L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x10000000L;
    private static final long STACK = RAM + 0x3000L;
    private static final long SYSRAM = 0x9fc00000L;
    private static final long[] API_TARGETS = {
        0x48b54679L, 0x48b5463dL, 0x48454841L, 0x484547adL,
        0xbfc16589L, 0x48a14601L, 0x48454601L, 0xbfc16585L,
        0x4845473dL
    };

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private String hex(long value) {
        return String.format("0x%08x", value & 0xffffffffL);
    }

    private void addRam(Memory memory, long start, long size, String name) throws Exception {
        MemoryBlock block = memory.createInitializedBlock(
            name, addr(start), new ByteArrayInputStream(new byte[(int)size]), size, monitor, false
        );
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    private String dumpStack(EmulatorHelper emu, long address, int length) throws Exception {
        if (address < STACK - 0x1000 || address + length > STACK + 0x1000) {
            return "<outside-stack:" + hex(address) + ">";
        }
        byte[] bytes = emu.readMemory(addr(address), length);
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private int byteAt(EmulatorHelper emu, long address) throws Exception {
        return emu.readMemory(addr(address), 1)[0] & 0xff;
    }

    private boolean isZeroBuffer(EmulatorHelper emu, long address, int length) throws Exception {
        for (int i = 0; i < length; i++) {
            if (byteAt(emu, address + i) != 0) return false;
        }
        return true;
    }

    private long wordAt(EmulatorHelper emu, long address) throws Exception {
        byte[] bytes = emu.readMemory(addr(address), 4);
        return (bytes[0] & 0xffL) | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16) | ((bytes[3] & 0xffL) << 24);
    }

    // Inputs 2-7 select the six fallback {key,mask,result} records in setting.ap.
    private int[] syntheticEvent(int input) throws Exception {
        switch (input) {
            case 2: return new int[] {0x05, 0x0400, 0x0021};
            case 3: return new int[] {0x06, 0x0400, 0x0021};
            case 4: return new int[] {0x09, 0x1800, 0x2202};
            case 5: return new int[] {0x08, 0x1800, 0x2201};
            case 6: return new int[] {0x80, 0x1000, 0x0108};
            case 7: return new int[] {0x7f, 0x3f00, 0x0024};
            default: throw new Exception("synthetic event input must be 2 through 7");
        }
    }

    @Override
    public void run() throws Exception {
        int input = getScriptArgs().length == 0 ? 1 : Integer.decode(getScriptArgs()[0]);
        Memory memory = currentProgram.getMemory();
        addRam(memory, RAM, 0x4000, "setting_confirm_probe_ram");
        addRam(memory, SYSRAM, 0x20000, "setting_confirm_probe_sysram");
        memory.setBytes(addr(SYSRAM + 0x15601), new byte[] {0});
        memory.setBytes(addr(SYSRAM + 0x1578e), new byte[] {1});
        if (input == 9) {
            // Seed the getter's backing byte so the real caller must preserve it.
            memory.setBytes(addr(SYSRAM + 0x1561b), new byte[] {1});
            // Model the callback slot written by the separately probed setting initializer.
            memory.setBytes(addr(SYSRAM + 0x15608), new byte[] {0x79, 0x4d, 0x05, 0x40});
        }

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        Register isa = currentProgram.getRegister("ISA_MODE");
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emu.setContextRegister(isa, BigInteger.ONE);
            emu.writeRegister("sp", STACK);
            emu.writeRegister("ra", STOP | 1L);
            emu.writeRegister("a0", input == 9 ? 1 : input);
            emu.writeRegister("a1", 0);
            emu.writeRegister("a2", 0);
            emu.writeRegister("a3", 0);
            emu.writeRegister("s1", 0);
            emu.writeRegister("pc", ENTRY);

            boolean sawTextId = false;
            boolean sawEntryControl = false;
            boolean injectedEvent = false;
            boolean enteredEventDispatch = false;
            boolean forcedPrimaryEventMiss = false;
            boolean sawConfirmRequest = false;
            boolean pendingGetterReturn = false;
            boolean sawGetterReturn = false;
            long getterReturnAddress = 0;
            int getterCalls = 0;
            int import6Calls = 0;
            int confirmEventServiceCalls = 0;
            for (int steps = 0; steps < 100000; steps++) {
                long pc = emu.readRegister("pc").longValue() & 0xffffffffL;
                // MIPS16 return pointers may have bit 0 set. The context register
                // carries the mode, so normalize the emulator PC before decoding.
                if ((pc & 1L) != 0) {
                    pc &= ~1L;
                    emu.writeRegister("pc", pc);
                }
                if (input == 9 && pendingGetterReturn && pc == getterReturnAddress) {
                    long getterValue = emu.readRegister("v0").longValue() & 0xffL;
                    if (getterValue != 1) {
                        throw new Exception("real state getter returned " + hex(getterValue)
                            + ", expected seeded value 1");
                    }
                    pendingGetterReturn = false;
                    sawGetterReturn = true;
                    println("SETTING CONFIRM GETTER RETURN value=" + hex(getterValue));
                }
                if (pc == STOP) {
                    if (input == 9) {
                        if (!sawGetterReturn || getterCalls != 1 || !sawConfirmRequest || import6Calls != 2
                            || confirmEventServiceCalls != 2
                            || byteAt(emu, SYSRAM + 0x1561b) != 1) {
                            throw new Exception("cleanup state restore mismatch: request="
                                + sawConfirmRequest + " import6Calls=" + import6Calls
                                + " eventCalls=" + confirmEventServiceCalls
                                + " getterCalls=" + getterCalls + " getterReturn=" + sawGetterReturn
                                + " flag=" + byteAt(emu, SYSRAM + 0x1561b));
                        }
                        println("SETTING CONFIRM RESTORE PASS steps=" + steps
                            + " import6-a0-sequence=0,1 flag=1");
                        return;
                    }
                    if (input >= 2 && input <= 7 && injectedEvent && enteredEventDispatch) {
                        println("SETTING SYNTHETIC EVENT RETURN PASS code="
                            + hex(syntheticEvent(input)[2]));
                        return;
                    }
                    throw new Exception("confirmation API was not reached");
                }
                if (pc == MODE_HELPER) {
                    emu.writeRegister("v0", 1);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    continue;
                }
                if (input >= 2 && injectedEvent && !enteredEventDispatch
                    && pc == 0x48bd4600L) {
                    long eventCode = emu.readRegister("a0").longValue() & 0xffffffffL;
                    int expectedCode = syntheticEvent(input)[2];
                    if (eventCode != expectedCode) {
                        throw new Exception("event map result mismatch: expected="
                            + hex(expectedCode) + " actual=" + hex(eventCode));
                    }
                    enteredEventDispatch = true;
                    println("SETTING EVENT DISPATCH ENTER code=" + hex(eventCode));
                }
                if (input >= 2 && injectedEvent && enteredEventDispatch
                    && pc == 0xbfc16582L) {
                    long selector = emu.readRegister("v1").longValue() & 0xffffffffL;
                    long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                    long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                    long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;
                    long ra = emu.readRegister("ra").longValue() & 0xffffffffL;
                    if ((input != 2 && input != 3) || selector != 0x10
                        || a0 != 0 || a1 != RAM + 0x2ea8 || a2 != 3
                        || a3 != 0x0a || ra != 0x48a549c3L) {
                        throw new Exception("unexpected event dispatcher trap: selector="
                            + hex(selector) + " args=" + hex(a0) + "," + hex(a1) + ","
                            + hex(a2) + "," + hex(a3) + " ra=" + hex(ra));
                    }
                    println("SETTING SYNTHETIC EVENT ABI TRAP BOUNDARY selector="
                        + hex(selector) + " args=" + hex(a0) + "," + hex(a1) + ","
                        + hex(a2) + "," + hex(a3) + " ra=" + hex(ra));
                    return;
                }

                boolean handled = false;
                for (long target : API_TARGETS) {
                    if (pc != (target & ~1L)) continue;
                    if (input == 9 && target == 0x48b54679L) {
                        if (pendingGetterReturn || getterCalls != 0) {
                            throw new Exception("unexpected repeated/nested saved-state getter");
                        }
                        getterCalls++;
                        getterReturnAddress = emu.readRegister("ra").longValue() & ~1L;
                        pendingGetterReturn = true;
                        continue;
                    }
                    if (input == 9 && target == 0x48b5463dL) {
                        // Execute the actual getter/setter bodies to observe the saved byte
                        // returning through cleanup; only resident services stay synthetic.
                        continue;
                    }
                    long a0 = emu.readRegister("a0").longValue() & 0xffffffffL;
                    long a1 = emu.readRegister("a1").longValue() & 0xffffffffL;
                    long a2 = emu.readRegister("a2").longValue() & 0xffffffffL;
                    long a3 = emu.readRegister("a3").longValue() & 0xffffffffL;
                    String detail = "";
                    if (target == 0x48b54679L) {
                        if (a0 != 0x85) throw new Exception("unexpected prompt text ID " + hex(a0));
                        sawTextId = true;
                    }
                    if (target == 0x48a14601L) {
                        if (input == 9 ? !sawGetterReturn : !sawTextId) {
                            throw new Exception("confirmation request preceded expected text/getter call");
                        }
                        if (byteAt(emu, a0) != 0x0a || byteAt(emu, a0 + 1) != 0x10
                            || byteAt(emu, a0 + 2) != 1 || wordAt(emu, a1) != 0x010100faL
                            || wordAt(emu, a1 + 4) != 0x48554680L) {
                            throw new Exception("confirmation payload mismatch: control="
                                + dumpStack(emu, a0, 8) + " data=" + dumpStack(emu, a1, 16));
                        }
                        detail = " control=" + dumpStack(emu, a0, 8)
                            + " data=" + dumpStack(emu, a1, 16);
                        if (input == 9) {
                            int flag = byteAt(emu, SYSRAM + 0x1561b);
                            if (flag != 0) {
                                throw new Exception("confirmation request saw uncleared state " + flag);
                            }
                            sawConfirmRequest = true;
                        }
                    }
                    if (target == 0xbfc16589L && input == 9 && a3 == 0x1e) {
                        long expectedA0 = import6Calls == 0 ? 0 : 1;
                        if (import6Calls > 1 || a0 != expectedA0 || a1 != 0 || a2 != 0) {
                            throw new Exception("unexpected import-6 cleanup args: "
                                + hex(a0) + "," + hex(a1) + "," + hex(a2) + "," + hex(a3));
                        }
                        import6Calls++;
                    }
                    if (target == 0xbfc16585L && input == 9
                        && (a3 == 0x19 || a3 == 0x18)) {
                        long expectedA0;
                        long syntheticReturn;
                        if (confirmEventServiceCalls == 0) {
                            expectedA0 = 6;
                            syntheticReturn = 1;
                        } else if (confirmEventServiceCalls == 1) {
                            expectedA0 = 0;
                            syntheticReturn = 0;
                        } else {
                            throw new Exception("unexpected additional confirmation event service call");
                        }
                        long expectedA3 = 0x19;
                        long expectedA1 = confirmEventServiceCalls == 0 ? RAM + 0x2f68 : RAM + 0x2f98;
                        if (a0 != expectedA0 || a1 != expectedA1 || a3 != expectedA3 || a2 != 0
                            || !isZeroBuffer(emu, a1, 8)) {
                            throw new Exception("unexpected confirmation event service args call="
                                + confirmEventServiceCalls + " got=" + hex(a0) + "," + hex(a1)
                                + "," + hex(a2) + "," + hex(a3) + " buffer="
                                + dumpStack(emu, a1, 8));
                        }
                        if (confirmEventServiceCalls == 1) {
                            emu.writeMemory(addr(a1), new byte[] {2, 1, 0, 0, 0, 0, 0, 0});
                        }
                        println("SETTING CONFIRM EVENT SERVICE call=" + confirmEventServiceCalls
                            + " args=" + hex(a0) + "," + hex(a1) + "," + hex(a2) + "," + hex(a3)
                            + " synthetic-return=" + hex(syntheticReturn));
                        confirmEventServiceCalls++;
                        emu.writeRegister("v0", syntheticReturn);
                        emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                        handled = true;
                        break;
                    }
                    if (target == 0xbfc16589L && input == 0
                        && a3 == 2 && byteAt(emu, a0) == 0x09
                        && byteAt(emu, a0 + 1) == 0x10
                        && byteAt(emu, a0 + 2) == 1) {
                        sawEntryControl = true;
                    }
                    println("UI API " + hex(target) + " args=" + hex(a0) + ","
                        + hex(a1) + "," + hex(a2) + "," + hex(a3) + detail);
                    if (target == 0xbfc16585L && input == 8 && !forcedPrimaryEventMiss) {
                        if (a0 != 6 || a1 != RAM + 0x2f68 || a2 != 0 || a3 != 0x19) {
                            throw new Exception("unexpected primary event request: args="
                                + hex(a0) + "," + hex(a1) + "," + hex(a2) + "," + hex(a3));
                        }
                        emu.writeRegister("v0", 1);
                        emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                        forcedPrimaryEventMiss = true;
                        println("SETTING PRIMARY EVENT MISS INJECTED service-return=1");
                        handled = true;
                        break;
                    }
                    if (target == 0xbfc16585L && input == 8 && forcedPrimaryEventMiss) {
                        if (a0 != 0 || a1 != RAM + 0x2f98 || a2 != 0 || a3 != 0x19
                            || !isZeroBuffer(emu, a1, 8)) {
                            throw new Exception("unexpected alternate event-source boundary: args="
                                + hex(a0) + "," + hex(a1) + "," + hex(a2) + "," + hex(a3)
                                + " buffer=" + dumpStack(emu, a1, 8));
                        }
                        println("SETTING ALTERNATE EVENT SOURCE BOUNDARY args="
                            + hex(a0) + "," + hex(a1) + "," + hex(a2) + "," + hex(a3)
                            + " buffer=" + dumpStack(emu, a1, 8));
                        return;
                    }
                    if (target == 0xbfc16585L && input >= 2 && input <= 7 && !injectedEvent) {
                        if (a0 != 6 || a1 != RAM + 0x2f68 || a2 != 0 || a3 != 0x19) {
                            throw new Exception("unexpected synthetic-event poll: args="
                                + hex(a0) + "," + hex(a1) + "," + hex(a2) + "," + hex(a3));
                        }
                        int[] spec = syntheticEvent(input);
                        byte[] event = new byte[] {0, (byte)spec[0],
                            (byte)spec[1], (byte)(spec[1] >>> 8), 0, 0, 0, 0};
                        emu.writeMemory(addr(a1), event);
                        emu.writeRegister("v0", 0);
                        emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                        injectedEvent = true;
                        println("SETTING SYNTHETIC EVENT INJECTED key=" + hex(spec[0])
                            + " mask=" + hex(spec[1]) + " expected-code=" + hex(spec[2])
                            + " raw=" + dumpStack(emu, a1, 8) + " service-return=0");
                        handled = true;
                        break;
                    }
                    if (target == 0xbfc16585L && input >= 2 && input <= 7 && injectedEvent) {
                        if (input != 4 && input != 5 && input != 7) {
                            throw new Exception("unexpected follow-up service boundary for input " + input);
                        }
                        if (a0 != 1 || a1 != 0 || a2 != 0 || a3 != 0x31) {
                            throw new Exception("unexpected follow-up service args: "
                                + hex(a0) + "," + hex(a1) + "," + hex(a2) + "," + hex(a3));
                        }
                        println("SETTING SYNTHETIC EVENT FOLLOW-UP SERVICE BOUNDARY args="
                            + hex(a0) + "," + hex(a1) + "," + hex(a2) + "," + hex(a3));
                        return;
                    }
                    if (target == 0xbfc16585L && input == 0 && sawEntryControl
                        && a0 == 6 && a3 == 0x19) {
                        if (steps != 178 || a1 != RAM + 0x2f68 || a2 != 0
                            || !isZeroBuffer(emu, a1, 8)) {
                            throw new Exception("unexpected event-poll boundary: steps=" + steps
                                + " args=" + hex(a0) + "," + hex(a1) + "," + hex(a2)
                                + "," + hex(a3) + " buffer=" + dumpStack(emu, a1, 8));
                        }
                        println("SETTING EVENT POLL BOUNDARY PASS steps=" + steps
                            + " control=0x1009 event-buffer-before-call="
                            + dumpStack(emu, a1, 8));
                        return;
                    }
                    if (target == 0x48a14601L) {
                        println("SETTING CONFIRM REQUEST PASS steps=" + steps
                            + " control=0x100a status=1 field-capacity=250 payload=0x48554680");
                        if (input != 9) return;
                    }
                    emu.writeRegister("v0", 0);
                    emu.writeRegister("pc", emu.readRegister("ra").longValue() & ~1L);
                    handled = true;
                    break;
                }
                if (handled) continue;
                if (!emu.step(monitor)) {
                    throw new Exception("emulation stopped at " + hex(pc) + ": " + emu.getLastError());
                }
            }
            throw new Exception("step limit reached at " + hex(emu.readRegister("pc").longValue()));
        } finally {
            emu.dispose();
        }
    }
}

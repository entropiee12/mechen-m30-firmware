import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Run MEngine _play and its real EQ builder, recording the external boundary order. */
public class RunMenginePlayOrderProbe extends GhidraScript {
    private static final long PLAY = 0x60190eecL;
    private static final long PREPARE = 0x60190d58L;
    private static final long MODE_CALLBACK = 0x60b50f18L;
    private static final long COPY = 0xbfc11cc0L;
    private static final long COMMAND = 0xbfc11cc4L;
    private static final long KERNEL = 0xbfc11cc8L;
    private static final long STOP = 0x700300L;
    private static final long RAM = 0x9fc15000L;
    private static final int RAM_SIZE = 0x6000;
    private static final long STACK = 0x9fc19000L;
    private static final long SETTINGS = 0x9fc15d9cL;
    private static final long LOCAL = 0x9fc15dd4L;
    private static final long SELECTOR = 0x9fc15cc9L;
    private static final long SHM_CELL = 0x9fc15ee4L;
    private static final long SHARED = 0x9fc1a400L;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) {
            throw new Exception(message);
        }
    }

    private long reg(EmulatorHelper emulator, String name) throws Exception {
        return emulator.readRegister(name).longValue() & 0xffffffffL;
    }

    private void putWord(EmulatorHelper emulator, long at, long value) throws Exception {
        emulator.writeMemory(addr(at), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private void returnFromStub(EmulatorHelper emulator, long value) throws Exception {
        long ra = reg(emulator, "ra");
        require((ra & 1L) == 1L, "return address lost MIPS16 tag");
        emulator.writeRegister("v0", value);
        emulator.writeRegister("pc", ra & ~1L);
    }

    @Override public void run() throws Exception {
        Memory memory = currentProgram.getMemory();
        memory.createInitializedBlock("play_order_ram", addr(RAM),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false)
            .setWrite(true);
        EmulatorHelper emulator = new EmulatorHelper(currentProgram);
        List<String> events = new ArrayList<>();
        try {
            emulator.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            emulator.setContextRegister(currentProgram.getRegister("ISA_MODE"), BigInteger.ONE);
            putWord(emulator, SHM_CELL, SHARED);
            putWord(emulator, 0x9fc15c0cL, 0x12345678L);
            emulator.writeMemory(addr(SETTINGS + 0x18), new byte[] {1});
            emulator.writeMemory(addr(LOCAL + 2), new byte[] {1});
            emulator.writeMemory(addr(SELECTOR), new byte[] {1});
            emulator.writeRegister("sp", STACK);
            emulator.writeRegister("ra", STOP | 1L);
            emulator.writeRegister("a0", 0);
            emulator.writeRegister("pc", PLAY);

            boolean returned = false;
            for (int step = 0; step < 500000; step++) {
                long pc = reg(emulator, "pc") & ~1L;
                if (pc == STOP) {
                    returned = true;
                    break;
                }
                if (pc == PREPARE || pc == MODE_CALLBACK) {
                    events.add(pc == PREPARE ? "prepare" : "mode-callback");
                    returnFromStub(emulator, 1);
                    continue;
                }
                if (pc == COPY) {
                    long dst = reg(emulator, "a0");
                    long src = reg(emulator, "a1");
                    int length = (int)reg(emulator, "a2");
                    long operation = reg(emulator, "a3");
                    require(length <= 0x400, "unexpected copy/fill size");
                    if (operation == 1) {
                        byte[] fill = new byte[length];
                        java.util.Arrays.fill(fill, (byte)src);
                        emulator.writeMemory(addr(dst), fill);
                    } else {
                        require(operation == 0, "unexpected copy operation");
                        emulator.writeMemory(addr(dst), emulator.readMemory(addr(src), length));
                    }
                    if (dst == SHARED && length == 0xf0) {
                        events.add("shared-copy");
                    }
                    returnFromStub(emulator, 0);
                    continue;
                }
                if (pc == KERNEL) {
                    long id = reg(emulator, "a0");
                    long operation = reg(emulator, "a3");
                    require(operation == 0x49 && id == 3,
                        String.format("unexpected kernel op 0x%x id=%d", operation, id));
                    events.add("attach-id3");
                    returnFromStub(emulator, SHARED);
                    continue;
                }
                if (pc == COMMAND) {
                    long handle = reg(emulator, "a0");
                    long command = reg(emulator, "a1");
                    if (command == 0x1f) {
                        require((emulator.readMemory(addr(SHARED + 2), 1)[0] & 255) == 1,
                            "EQ command preceded shared-memory update");
                    }
                    events.add(String.format("command-%02x", command));
                    println(String.format("MENGINE PLAY CALL command=0x%02x handle=0x%08x arg=0x%08x",
                        command, handle, reg(emulator, "a2")));
                    returnFromStub(emulator, 0);
                    continue;
                }
                if (!memory.contains(addr(pc))) {
                    throw new Exception(String.format("unmodeled external call at 0x%08x", pc));
                }
                if (!emulator.step(monitor)) {
                    throw new Exception(String.format("stopped at 0x%08x: %s", pc, emulator.getLastError()));
                }
            }
            require(returned, "_play did not return");
            require(events.equals(List.of("prepare", "mode-callback", "shared-copy",
                    "attach-id3", "command-1f", "command-02", "command-19")),
                "unexpected _play event order: " + events);
            println("MENGINE PLAY ORDER P-CODE PASS events=" + events
                + " synthetic=prepare,mode-callback,memcpy,SHM-attach,mmm-command");
        } finally {
            emulator.dispose();
        }
    }
}

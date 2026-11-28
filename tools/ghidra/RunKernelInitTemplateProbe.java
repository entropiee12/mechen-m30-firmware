// Trace the extracted FUN_bfc322f8 through ROM call boundaries to its callback edge.
// ROM helpers are no-effect stubs; the callback pointer is explicitly synthetic.
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunKernelInitTemplateProbe extends GhidraScript {
    private static final long ENTRY = 0xbfc322f8L;
    private static final long STOP_CALLBACK = 0x9fc27f00L;
    private static final long RAM_BASE = 0x9fc10000L;
    private static final int RAM_SIZE = 0x20000;
    private static final long ROM_CLEAR = 0xbfc045f4L;
    private static final long ROM_COPY = 0xbfc04634L;
    private static final int MAX_STEPS = 12000;

    private Address addr(long value) {
        return currentProgram.getAddressFactory().getDefaultAddressSpace()
            .getAddress(value & 0xffffffffL);
    }

    private void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }

    private long reg(EmulatorHelper emu, String name) throws Exception {
        return emu.readRegister(name).longValue() & 0xffffffffL;
    }

    private long readWord(EmulatorHelper emu, long at) throws Exception {
        byte[] bytes = emu.readMemory(addr(at), 4);
        return (bytes[0] & 255L) | ((bytes[1] & 255L) << 8)
            | ((bytes[2] & 255L) << 16) | ((bytes[3] & 255L) << 24);
    }

    private void writeWord(EmulatorHelper emu, long at, long value) throws Exception {
        emu.writeMemory(addr(at), new byte[] {
            (byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)
        });
    }

    private void mapRam(Memory memory) throws Exception {
        memory.createInitializedBlock("kernel_template_probe_ram", addr(RAM_BASE),
            new ByteArrayInputStream(new byte[RAM_SIZE]), RAM_SIZE, monitor, false);
        var block = memory.getBlock(addr(RAM_BASE));
        block.setRead(true);
        block.setWrite(true);
        block.setExecute(true);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: RunKernelInitTemplateProbe.java KER_INIT.BIN");
        byte[] image = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        require(image.length > 0x800, "ker_init.bin truncated before FUN_bfc322f8");
        mapRam(currentProgram.getMemory());

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        List<String> calls = new ArrayList<>();
        try {
            emu.registerCallOtherCallback("setISAMode", new BreakCallBack() {
                @Override public boolean pcodeCallback(PcodeOpRaw op) { return true; }
            });
            // The fixed-ROM clear/copy bodies are absent. Their effects are
            // intentionally omitted; only the actual call-site ABI is recorded.
            emu.writeRegister("sp", 0x9fc2ff00L);
            emu.writeRegister("s8", 0x9fc2fe00L);
            emu.writeRegister("a0", 0x01010101L);
            emu.writeRegister("a1", 0x02020202L);
            emu.writeRegister("a2", 0x03030303L);
            emu.writeRegister("a3", 0x04040404L);
            emu.writeRegister("ra", 0x00700100L);
            emu.writeRegister("pc", ENTRY);

            // Synthetic stand-in for the callback word that the unknown copy
            // helper would have placed at 0x9fc1aad8. Stop before executing it.
            byte[] source = new byte[0x208];
            Arrays.fill(source, (byte)0x5a);
            source[0] = 0x00;
            source[1] = 0x7f;
            source[2] = (byte)0xc2;
            source[3] = (byte)0x9f;
            emu.writeMemory(addr(0x9fc12000L), source);
            // Keep the callback's memory slot distinct from the synthetic ROM
            // helper effects and verify the p-code callsite consumes this word.
            writeWord(emu, 0x9fc1aad8L, STOP_CALLBACK);

            boolean reachedCallback = false;
            int steps = 0;
            long callbackA0 = 0, callbackA1 = 0, callbackA2 = 0, callbackA3 = 0;
            for (; steps < MAX_STEPS; steps++) {
                long pc = reg(emu, "pc");
                long target = pc & ~1L;
                if (target == STOP_CALLBACK) {
                    reachedCallback = true;
                    callbackA0 = reg(emu, "a0");
                    callbackA1 = reg(emu, "a1");
                    callbackA2 = reg(emu, "a2");
                    callbackA3 = reg(emu, "a3");
                    break;
                }
                if (target == ROM_CLEAR || target == ROM_COPY) {
                    long a0 = reg(emu, "a0");
                    long a1 = reg(emu, "a1");
                    long a2 = reg(emu, "a2");
                    long a3 = reg(emu, "a3");
                    long ra = reg(emu, "ra");
                    long rawCallPointer = reg(emu, "v0");
                    require(rawCallPointer == (target | 1L),
                        String.format("expected tagged MIPS16 ROM pointer 0x%08x for pc 0x%08x",
                            target | 1L, target));
                    require((ra & 3L) == 0,
                        String.format("expected aligned MIPS32 return address, got 0x%08x", ra));
                    String call = String.format("%08x:ptr=%08x:%08x,%08x,%08x,%08x@%08x",
                        target, rawCallPointer, a0, a1, a2, a3, ra);
                    calls.add(call);
                    println("KERNEL TEMPLATE ROM CALL " + call);
                    emu.writeRegister("v0", 0);
                    // The extracted caller is MIPS32, while these fixed-ROM
                    // entry pointers are tagged MIPS16. Restore caller mode
                    // at the intercepted return boundary.
                    emu.setContextRegister(currentProgram.getRegister("ISA_MODE"),
                        BigInteger.ZERO);
                    emu.writeRegister("pc", ra);
                    continue;
                }
                if (target < ENTRY || target >= 0xbfc33000L)
                    throw new Exception(String.format("unexpected control flow pc=0x%08x", pc));
                if (!emu.step(monitor))
                    throw new Exception(String.format("p-code stopped at 0x%08x: %s calls=%s",
                        pc, emu.getLastError(), calls));
            }

            require(reachedCallback, "did not reach the synthetic callback within the step limit");
            require(readWord(emu, 0x9fc1aad8L) == STOP_CALLBACK,
                "synthetic callback pointer changed before indirect call");
            String[] expected = {
                "9fc1a48c,00000000,000007f4,",
                "9fc1aad8,9fc12000,00000014,",
                "9fc1a980,9fc12014,00000020,",
                "bfc080f8,9fc12200,00000004,"
            };
            require(calls.size() == expected.length,
                "expected exactly the four clear/copy boundaries before callback: " + calls);
            for (int i = 0; i < expected.length; i++) {
                long target = i == 0 ? ROM_CLEAR : ROM_COPY;
                String prefix = String.format("%08x:ptr=%08x:%s", target, target | 1L, expected[i]);
                require(calls.get(i).startsWith(prefix),
                    "unexpected ROM call " + i + ": " + calls.get(i));
                String[] callParts = calls.get(i).substring(calls.get(i).indexOf('@') + 1)
                    .split(":");
                long callRa = Long.parseUnsignedLong(callParts[0], 16);
                require(callRa >= ENTRY && callRa < 0xbfc32400L,
                    "ROM call returned outside FUN_bfc322f8: " + calls.get(i));
            }
            require(callbackA0 == 0xfffffff0L && callbackA1 == 0xbfc0855dL
                    && callbackA2 == 0,
                String.format("unexpected callback arguments %08x,%08x,%08x,%08x",
                    callbackA0, callbackA1, callbackA2, callbackA3));

            for (String call : calls) println("KERNEL TEMPLATE ROM CALL " + call);
            println(String.format("KERNEL TEMPLATE CALLBACK EDGE PASS entry=%08x "
                + "synthetic-target=%08x args=%08x,%08x,%08x,%08x steps=%d "
                + "rom-effects=omitted source-bytes=synthetic isa-interworking=not-modeled",
                ENTRY, STOP_CALLBACK, callbackA0, callbackA1, callbackA2, callbackA3, steps));
        }
        finally {
            emu.dispose();
        }
    }
}

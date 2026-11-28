// Map a raw MIPS32 segment at a known runtime address.
// Arguments: file, base address, file offset, optional byte count.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.lang.Register;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class FlatLoad32 extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 3 || getScriptArgs().length > 4)
            throw new Exception("usage: FlatLoad32.java FILE BASE FILE_OFFSET [SIZE]");
        byte[] data = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        long base = Long.decode(getScriptArgs()[1]);
        int from = Integer.decode(getScriptArgs()[2]);
        int size = getScriptArgs().length == 4
            ? Integer.decode(getScriptArgs()[3]) : data.length - from;
        if (from < 0 || size <= 0 || from + size > data.length)
            throw new Exception("file range outside input");

        Memory memory = currentProgram.getMemory();
        for (var block : memory.getBlocks()) memory.removeBlock(block, monitor);
        AddressSpace space = currentProgram.getAddressFactory().getDefaultAddressSpace();
        Address start = space.getAddress(base + from);
        byte[] chunk = Arrays.copyOfRange(data, from, from + size);
        var block = memory.createInitializedBlock("flat_mips32", start,
            new ByteArrayInputStream(chunk), chunk.length, monitor, false);
        block.setRead(true);
        block.setExecute(true);
        Register isa = currentProgram.getProgramContext().getRegister("ISA_MODE");
        if (isa != null) currentProgram.getProgramContext().setValue(isa, start,
            start.add(chunk.length - 1), BigInteger.ZERO);

        int functions = 0;
        for (int offset = 0; offset + 7 < chunk.length; offset += 4) {
            int instruction = (chunk[offset] & 0xff) |
                ((chunk[offset + 1] & 0xff) << 8) |
                ((chunk[offset + 2] & 0xff) << 16) |
                ((chunk[offset + 3] & 0xff) << 24);
            int next = (chunk[offset + 4] & 0xff) |
                ((chunk[offset + 5] & 0xff) << 8) |
                ((chunk[offset + 6] & 0xff) << 16) |
                ((chunk[offset + 7] & 0xff) << 24);
            // addiu sp,sp,-frame followed by sw ra,... is a reliable enough
            // discovery hint for these small raw kernel init segments.
            if ((instruction & 0xffff0000) != 0x27bd0000 ||
                (instruction & 0x00008000) == 0 || (next & 0xffff0000) != 0xafbf0000)
                continue;
            Address function = start.add(offset);
            if (disassemble(function) && getFunctionAt(function) == null &&
                createFunction(function, null) != null) functions++;
        }
        println("FlatLoad32: bytes=" + chunk.length + " functions=" + functions +
            " base=" + String.format("0x%08x", base));
    }
}

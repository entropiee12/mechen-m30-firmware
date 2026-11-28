// Map a non-standard Actions .drv file at a stable flat address.
// Arguments: file, base address, file offset to map, optional byte count.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class FlatLoad extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 3 || getScriptArgs().length > 4) {
            throw new Exception("usage: FlatLoad.java FILE BASE FILE_OFFSET [SIZE]");
        }
        byte[] data = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        long base = Long.decode(getScriptArgs()[1]);
        int from = Integer.decode(getScriptArgs()[2]);
        int size = getScriptArgs().length == 4 ? Integer.decode(getScriptArgs()[3]) : data.length - from;
        if (from < 0 || from >= data.length || size <= 0 || from + size > data.length) {
            throw new Exception("file range outside input");
        }

        Memory memory = currentProgram.getMemory();
        for (MemoryBlock block : memory.getBlocks()) memory.removeBlock(block, monitor);
        AddressSpace space = currentProgram.getAddressFactory().getDefaultAddressSpace();
        Address start = space.getAddress(base + from);
        byte[] chunk = Arrays.copyOfRange(data, from, from + size);
        MemoryBlock block = memory.createInitializedBlock("flat_drv", start,
            new ByteArrayInputStream(chunk), chunk.length, monitor, false);
        block.setRead(true);
        block.setExecute(true);

        Register isa = currentProgram.getRegister("ISA_MODE");
        currentProgram.getProgramContext().setValue(isa, start,
            start.add(chunk.length - 1), BigInteger.ONE);

        int functions = 0;
        for (int offset = 0; offset + 1 < chunk.length; offset += 2) {
            int halfword = (chunk[offset] & 0xff) | ((chunk[offset + 1] & 0xff) << 8);
            if ((halfword & 0xff80) != 0x6480) continue; // MIPS16 SAVE
            int functionOffset = offset;
            if (offset >= 2) {
                int previous = (chunk[offset - 2] & 0xff) | ((chunk[offset - 1] & 0xff) << 8);
                if ((previous & 0xf800) == 0xf000) functionOffset = offset - 2;
            }
            Address function = start.add(functionOffset);
            if (getFunctionAt(function) == null && disassemble(function)
                && createFunction(function, null) != null) functions++;
        }
        println("FlatLoad: bytes=" + chunk.length + " functions=" + functions
            + " base=" + String.format("0x%08x", base) + " offset=" + String.format("0x%x", from));
    }
}

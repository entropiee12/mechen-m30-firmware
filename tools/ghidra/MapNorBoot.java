// Map the NORBREC member at its candidate virtual base and mark its mixed ISA
// regions. Arguments: file, file-base.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class MapNorBoot extends GhidraScript {
    private void createMips16Functions(byte[] data, Address start)
        throws Exception {
        int functions = 0;
        for (int offset = 0x158; offset + 1 < data.length; offset += 2) {
            int halfword = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
            if ((halfword & 0xff80) != 0x6480) continue; // MIPS16 SAVE
            int functionOffset = offset;
            if (offset >= 2) {
                int previous = (data[offset - 2] & 0xff)
                    | ((data[offset - 1] & 0xff) << 8);
                if ((previous & 0xf800) == 0xf000) functionOffset = offset - 2;
            }
            Address function = start.add(functionOffset);
            if (getFunctionAt(function) == null && disassemble(function)
                && createFunction(function, null) != null) functions++;
        }
        println("MapNorBoot: mips16-functions=" + functions);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 2)
            throw new Exception("usage: MapNorBoot.java FILE FILE_BASE");

        byte[] data = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        if (data.length != 0x28a00)
            throw new Exception(String.format("unexpected NORBREC size: 0x%x", data.length));
        long base = Long.decode(getScriptArgs()[1]);
        AddressSpace space = currentProgram.getAddressFactory().getDefaultAddressSpace();
        Address start = space.getAddress(base);
        Memory memory = currentProgram.getMemory();
        for (var block : memory.getBlocks()) memory.removeBlock(block, monitor);
        var mapped = memory.createInitializedBlock("norboot", start,
            new ByteArrayInputStream(data), data.length, monitor, false);
        mapped.setRead(true);
        mapped.setExecute(true);

        Register isa = currentProgram.getRegister("ISA_MODE");
        if (isa == null) throw new Exception("processor has no ISA_MODE register");
        Address entry = start.add(0x100);
        currentProgram.getProgramContext().setValue(isa, entry, entry.add(0x17),
            BigInteger.ZERO);
        currentProgram.getProgramContext().setValue(isa, start.add(0x158),
            start.add(data.length - 1), BigInteger.ONE);

        if (!disassemble(entry)) throw new Exception("could not disassemble MIPS32 entry");
        if (getFunctionAt(entry) == null && createFunction(entry, null) == null)
            throw new Exception("could not create MIPS32 entry function");
        createMips16Functions(data, start);
        println(String.format("MapNorBoot: bytes=0x%x base=0x%08x entry=0x%08x",
            data.length, base, base + 0x100));
    }
}

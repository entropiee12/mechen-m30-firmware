// Map raw segments without removing blocks already loaded by another script.
// Arguments: COUNT FILE BASE FILE_OFFSET SIZE ISA_MODE ...
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

public class MapSegmentsKeep extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 1) {
            throw new Exception("usage: MapSegmentsKeep.java COUNT FILE BASE OFFSET SIZE ISA_MODE ...");
        }
        int count = Integer.parseInt(getScriptArgs()[0]);
        if (getScriptArgs().length != 1 + count * 5) {
            throw new Exception("expected " + count + " segment tuples");
        }

        Memory memory = currentProgram.getMemory();
        AddressSpace space = currentProgram.getAddressFactory().getDefaultAddressSpace();
        Register isa = currentProgram.getProgramContext().getRegister("ISA_MODE");

        for (int i = 0; i < count; i++) {
            int arg = 1 + i * 5;
            byte[] data = Files.readAllBytes(Paths.get(getScriptArgs()[arg]));
            long base = Long.decode(getScriptArgs()[arg + 1]);
            int from = Integer.decode(getScriptArgs()[arg + 2]);
            int size = Integer.decode(getScriptArgs()[arg + 3]);
            int mode = Integer.decode(getScriptArgs()[arg + 4]);
            if (from < 0 || size <= 0 || from + size > data.length) {
                throw new Exception("segment range outside input: " + getScriptArgs()[arg]);
            }
            byte[] chunk = Arrays.copyOfRange(data, from, from + size);
            Address start = space.getAddress(base + from);
            if (memory.getBlock(start) != null) {
                throw new Exception("segment overlaps an existing block at " + start);
            }
            var block = memory.createInitializedBlock(
                "kept_segment_" + i, start, new ByteArrayInputStream(chunk),
                chunk.length, monitor, false);
            block.setRead(true);
            block.setExecute(true);
            if (isa != null) {
                currentProgram.getProgramContext().setValue(
                    isa, start, start.add(chunk.length - 1), BigInteger.valueOf(mode));
            }
            println("MapSegmentsKeep: " + getScriptArgs()[arg] + " -> "
                + String.format("0x%08x", base + from) + " size="
                + String.format("0x%x", size) + " isa=" + mode);
        }
    }
}

// Find little-endian 32-bit pointer literals and report their containing
// Ghidra function.  The program is expected to have a flat MIPS16 mapping.
// Arguments: comma-separated pointer values.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.util.LinkedHashMap;
import java.util.Map;

public class FindPointerRefs extends GhidraScript {
    private long readLe32(byte[] bytes) {
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: FindPointerRefs.java 0x164c9001,0x26409401");
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (String text : getScriptArgs()[0].split(",")) {
            counts.put(Long.decode(text) & 0xffffffffL, 0);
        }
        Memory memory = currentProgram.getMemory();
        byte[] bytes = new byte[4];
        for (MemoryBlock block : memory.getBlocks()) {
            if (!block.isInitialized()) continue;
            Address start = block.getStart();
            long size = block.getSize();
            for (long offset = 0; offset + 4 <= size; offset++) {
                Address address = start.add(offset);
                memory.getBytes(address, bytes);
                long value = readLe32(bytes);
                if (!counts.containsKey(value)) continue;
                Function function = currentProgram.getFunctionManager().getFunctionContaining(address);
                String owner = function == null ? "<no-function>" : function.getName();
                int next = counts.get(value) + 1;
                counts.put(value, next);
                println(String.format(
                    "POINTER_REF value=0x%08x address=%s owner=%s offset=0x%x",
                    value, address, owner, offset));
            }
        }
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            println(String.format("POINTER_TOTAL value=0x%08x count=%d",
                entry.getKey(), entry.getValue()));
        }
    }
}

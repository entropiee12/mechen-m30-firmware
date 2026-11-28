// Group observed little-endian pointer literals that sit near each other.
// Arguments: comma-separated pointer values, optional cluster gap in bytes.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FindPointerClusters extends GhidraScript {
    private static class Hit {
        long value;
        Address address;
        long offset;

        Hit(long value, Address address, long offset) {
            this.value = value;
            this.address = address;
            this.offset = offset;
        }
    }

    private long readLe32(byte[] bytes) {
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 1 || getScriptArgs().length > 2) {
            throw new Exception("usage: FindPointerClusters.java 0x164c9001,0x26409401 [0x40]");
        }
        long gap = getScriptArgs().length == 2 ? Long.decode(getScriptArgs()[1]) : 0x40;
        Map<Long, Boolean> wanted = new LinkedHashMap<>();
        for (String text : getScriptArgs()[0].split(",")) {
            wanted.put(Long.decode(text) & 0xffffffffL, true);
        }

        List<Hit> hits = new ArrayList<>();
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
                if (wanted.containsKey(value)) hits.add(new Hit(value, address, offset));
            }
        }
        hits.sort(Comparator.comparingLong(hit -> hit.offset));

        int cluster = 0;
        long start = -1;
        long end = -1;
        int count = 0;
        for (Hit hit : hits) {
            if (start < 0 || hit.offset - end > gap) {
                if (start >= 0) {
                    println(String.format("POINTER_CLUSTER id=%d start=0x%x end=0x%x hits=%d",
                        cluster, start, end, count));
                }
                cluster++;
                start = hit.offset;
                count = 0;
            }
            end = hit.offset;
            count++;
            println(String.format("POINTER_HIT cluster=%d value=0x%08x address=%s offset=0x%x",
                cluster, hit.value, hit.address, hit.offset));
        }
        if (start >= 0) {
            println(String.format("POINTER_CLUSTER id=%d start=0x%x end=0x%x hits=%d",
                cluster, start, end, count));
        }
        println(String.format("POINTER_CLUSTER_TOTAL hits=%d clusters=%d gap=0x%x",
            hits.size(), cluster, gap));
    }
}

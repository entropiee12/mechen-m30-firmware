// Dump little-endian 32-bit words from a mapped Ghidra memory range.
// Arguments: START END (END is exclusive).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;

public class DumpWords extends GhidraScript {
    private long readLe32(byte[] bytes) {
        return (bytes[0] & 0xffL)
            | ((bytes[1] & 0xffL) << 8)
            | ((bytes[2] & 0xffL) << 16)
            | ((bytes[3] & 0xffL) << 24);
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 2) {
            throw new Exception("usage: DumpWords.java START END");
        }
        long startValue = Long.decode(getScriptArgs()[0]);
        long endValue = Long.decode(getScriptArgs()[1]);
        if (endValue <= startValue || ((endValue - startValue) & 3) != 0) {
            throw new Exception("range must be positive and 32-bit aligned");
        }

        Memory memory = currentProgram.getMemory();
        byte[] bytes = new byte[4];
        Address start = currentProgram.getAddressFactory().getDefaultAddressSpace()
            .getAddress(startValue);
        for (long offset = 0; startValue + offset < endValue; offset += 4) {
            Address address = start.add(offset);
            memory.getBytes(address, bytes);
            println(String.format("WORD address=%s value=0x%08x",
                address, readLe32(bytes)));
        }
    }
}

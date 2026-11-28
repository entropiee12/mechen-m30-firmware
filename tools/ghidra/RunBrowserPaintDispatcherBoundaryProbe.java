// Pin the recovered browser callback and file-operation dispatcher boundary.
// This intentionally does not claim to emulate the missing listbox painter.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import java.util.HashMap;
import java.util.Map;

public class RunBrowserPaintDispatcherBoundaryProbe extends GhidraScript {
    private long word(long at) throws Exception {
        byte[] b = new byte[4];
        Memory m = currentProgram.getMemory();
        m.getBytes(currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(at), b);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8)
            | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    private void requireReference(long targetValue, long sourceValue) throws Exception {
        Address target = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(targetValue);
        Address source = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(sourceValue);
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target);
        while (refs.hasNext()) {
            Reference ref = refs.next();
            if (ref.getFromAddress().equals(source) && ref.getReferenceType().isCall()) return;
        }
        throw new Exception(String.format("missing Ghidra reference 0x%08x -> 0x%08x",
            sourceValue, targetValue));
    }

    @Override public void run() throws Exception {
        analyzeAll(currentProgram);
        Map<Long, Long> expected = new HashMap<>();
        expected.put(0x40cd5028L, 0x48754575L); // list initialization callback
        expected.put(0x40cd5044L, 0x487546d9L); // list-state/row descriptor callback
        expected.put(0x40cd52d8L, 0x48714a21L); // refresh row callback
        expected.put(0x40cd52dcL, 0x780db22dL); // fsel operation dispatcher
        expected.put(0x487546c0L, 0x780db201L); // common file-operation dispatch
        expected.put(0x780db224L, 0x780db5d4L); // operation handler table
        expected.put(0x780db5d4L, 0x713125ddL);
        expected.put(0x780db5d8L, 0x714525adL);
        expected.put(0x780db5dcL, 0x713925f5L);

        for (Map.Entry<Long, Long> e : expected.entrySet()) {
            long actual = word(e.getKey());
            if (actual != e.getValue()) {
                throw new Exception(String.format("literal mismatch @0x%08x expected=0x%08x actual=0x%08x",
                    e.getKey(), e.getValue(), actual));
            }
        }
        requireReference(0x487546d8L, 0x40cd4f70L);
        requireReference(0x48714a20L, 0x40cd5282L);
        requireReference(0x780db200L, 0x487545f2L);
        requireReference(0x780db22cL, 0x40cd52c2L);

        long[] absentTargets = {0x260e4ff5L, 0x26409401L, 0x164490a1L};
        long[] absentCounts = new long[absentTargets.length];
        Memory memory = currentProgram.getMemory();
        for (MemoryBlock block : memory.getBlocks()) {
            if (!block.isInitialized()) continue;
            byte[] bytes = new byte[4];
            for (long off = 0; off + 4 <= block.getSize(); off++) {
                Address address = block.getStart().add(off);
                memory.getBytes(address, bytes);
                long value = (bytes[0] & 0xffL) | ((bytes[1] & 0xffL) << 8)
                    | ((bytes[2] & 0xffL) << 16) | ((bytes[3] & 0xffL) << 24);
                for (int i = 0; i < absentTargets.length; i++) {
                    if (value == absentTargets[i]) absentCounts[i]++;
                }
            }
        }
        for (int i = 0; i < absentCounts.length; i++) {
            if (absentCounts[i] != 0) {
                throw new Exception(String.format("unexpected direct browser.ap pointer 0x%08x count=%d",
                    absentTargets[i], absentCounts[i]));
            }
        }

        println("BROWSER PAINT DISPATCHER BOUNDARY PASS "+
            "list-init=0x48754574 row-state=0x487546d8 refresh-row=0x48714a20 "+
            "fsel-dispatch=0x780db22c operation-table=0x780db5d4 entries=0x713125dc,0x714525ac,0x713925f4 "+
            "direct-B3-B16-A17-pointers=0");
    }
}

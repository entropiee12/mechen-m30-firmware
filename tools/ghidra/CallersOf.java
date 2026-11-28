import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.util.*;

/** Callers (call references) of the given function entry addresses, transitive to DEPTH levels. */
public class CallersOf extends GhidraScript {
    @Override public void run() throws Exception {
        int depth = Integer.parseInt(getScriptArgs()[1]);
        List<Long> level = new ArrayList<>();
        for (String s : getScriptArgs()[0].split(",")) level.add(Long.decode(s));
        Set<Long> seen = new HashSet<>(level);
        for (int d = 1; d <= depth; d++) {
            List<Long> next = new ArrayList<>();
            for (long t : level) {
                Address a = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(t);
                Function fn = getFunctionAt(a);
                println(String.format("TARGET %08x (%s)", t, fn == null ? "no function" : fn.getName()));
                int n = 0;
                for (Reference r : getReferencesTo(a)) {
                    Function from = getFunctionContaining(r.getFromAddress());
                    println(String.format("  d%d %s from %s in %s", d, r.getReferenceType(), r.getFromAddress(), from == null ? "<none>" : from.getName()));
                    n++;
                    if (from != null && seen.add(from.getEntryPoint().getOffset())) next.add(from.getEntryPoint().getOffset());
                }
                if (n == 0) println("  (no references)");
            }
            level = next;
        }
    }
}

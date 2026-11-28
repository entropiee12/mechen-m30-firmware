import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import java.util.*;

/** List `li a3,IMM` sites for chosen ops with the preceding instructions (argument setup). */
public class FindA3Ops extends GhidraScript {
    @Override public void run() throws Exception {
        Set<Long> want = new HashSet<>();
        for (String s : getScriptArgs()[0].split(",")) want.add(Long.decode(s));
        Listing l = currentProgram.getListing();
        List<Instruction> all = new ArrayList<>();
        for (Instruction i : l.getInstructions(true)) all.add(i);
        for (int k = 0; k < all.size(); k++) {
            Instruction i = all.get(k);
            if (!i.getMnemonicString().toLowerCase().contains("li")) continue;
            if (i.getNumOperands() < 2) continue;
            String r = i.getDefaultOperandRepresentation(0);
            if (!r.equals("a3")) continue;
            Object[] o = i.getOpObjects(1);
            if (o.length == 0 || !(o[0] instanceof Scalar)) continue;
            long v = ((Scalar) o[0]).getUnsignedValue();
            if (!want.contains(v)) continue;
            println("SITE op=0x" + Long.toHexString(v) + " @" + i.getAddress());
            for (int j = Math.max(0, k - 9); j <= Math.min(all.size() - 1, k + 3); j++)
                println("    " + all.get(j).getAddress() + ": " + all.get(j));
        }
    }
}

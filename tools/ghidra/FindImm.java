import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import java.util.*;

/** All instructions with a scalar operand equal to IMM, with owning function and 3 preceding instructions. */
public class FindImm extends GhidraScript {
    @Override public void run() throws Exception {
        long want = Long.decode(getScriptArgs()[0]);
        Listing l = currentProgram.getListing();
        List<Instruction> all = new ArrayList<>();
        for (Instruction i : l.getInstructions(true)) all.add(i);
        for (int k = 0; k < all.size(); k++) {
            Instruction i = all.get(k);
            boolean hit = false;
            for (int op = 0; op < i.getNumOperands() && !hit; op++)
                for (Object o : i.getOpObjects(op))
                    if (o instanceof Scalar && ((Scalar) o).getUnsignedValue() == want) hit = true;
            if (!hit) continue;
            Function f = getFunctionContaining(i.getAddress());
            println("IMM " + i.getAddress() + " " + i + "  in " + (f == null ? "<none>" : f.getName()));
            for (int j = Math.max(0, k - 3); j <= Math.min(all.size() - 1, k + 3); j++) if (j != k) println("      " + all.get(j).getAddress() + ": " + all.get(j));
        }
    }
}

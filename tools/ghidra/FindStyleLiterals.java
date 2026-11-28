import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;

/** List every decoded 0x2001..0x2005 style literal and its containing function. */
public class FindStyleLiterals extends GhidraScript {
    @Override
    public void run() throws Exception {
        for (Instruction instruction : currentProgram.getListing().getInstructions(true)) {
            String text = instruction.toString();
            if (text.contains("0x2001") || text.contains("0x2002") ||
                text.contains("0x2003") || text.contains("0x2004") ||
                text.contains("0x2005")) {
                Function function = currentProgram.getListing().getFunctionContaining(
                    instruction.getAddress());
                println(instruction.getAddress() + " " + text + "  [" +
                    (function == null ? "?" : function.getName()) + "]");
            }
        }
    }
}

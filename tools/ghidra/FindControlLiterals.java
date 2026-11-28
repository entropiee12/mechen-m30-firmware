import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;

/** Print decoded instructions that carry a 0x1000..0x1008 control literal. */
public class FindControlLiterals extends GhidraScript {
    @Override
    public void run() throws Exception {
        for (Instruction instruction : currentProgram.getListing().getInstructions(true)) {
            String text = instruction.toString();
            boolean match = false;
            for (int value = 0x1000; value <= 0x1008; value++) {
                if (text.contains(String.format("0x%x", value))) {
                    match = true;
                    break;
                }
            }
            if (match) {
                Function function = currentProgram.getListing().getFunctionContaining(instruction.getAddress());
                println(instruction.getAddress() + " " + text + " [" +
                    (function == null ? "?" : function.getName()) + "]");
            }
        }
    }
}

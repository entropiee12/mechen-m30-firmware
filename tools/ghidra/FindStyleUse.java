import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Function;

/** Print instructions in the now-playing refresh path that reference 0x2005. */
public class FindStyleUse extends GhidraScript {
    @Override
    public void run() throws Exception {
        println("FindStyleUse: start");
        Listing listing = currentProgram.getListing();
        Address start = toAddr("0x40154ddc");
        println("address=" + start + " functionContaining=" + listing.getFunctionContaining(start));
        Instruction instruction = listing.getInstructionAt(start);
        if (instruction == null) {
            Function function = listing.getFunctionAt(start);
            println("no instruction at " + start + "; function=" + function);
            if (function == null) return;
            instruction = listing.getInstructions(function.getBody(), true).hasNext()
                ? listing.getInstructions(function.getBody(), true).next()
                : null;
        }
        int seen = 0;
        while (instruction != null && seen++ < 500) {
            String text = instruction.toString();
            if (seen < 500) {
                println(instruction.getAddress() + "  " + text);
            }
            if (text.contains("0x2005") || text.contains("0x2001")) {
                println(instruction.getAddress() + "  " + text);
            }
            CodeUnit next = listing.getCodeUnitAfter(instruction.getMaxAddress());
            instruction = next instanceof Instruction ? (Instruction) next : null;
        }
    }
}

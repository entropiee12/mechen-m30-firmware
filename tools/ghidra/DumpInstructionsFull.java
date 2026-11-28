// Dump full decoded MIPS/MIPS16 instruction text from a mapped range.
// Arguments: START COUNT
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;

public class DumpInstructionsFull extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 2) {
            throw new Exception("usage: DumpInstructionsFull.java START COUNT");
        }
        long startValue = Long.decode(getScriptArgs()[0]);
        int count = Integer.decode(getScriptArgs()[1]);
        Address address = currentProgram.getAddressFactory().getDefaultAddressSpace()
            .getAddress(startValue & ~1L);
        for (int i = 0; i < count; i++) {
            Instruction instruction = getInstructionAt(address);
            if (instruction == null) {
                disassemble(address);
                instruction = getInstructionAt(address);
            }
            if (instruction == null) {
                println("INSN missing " + address);
                address = address.add(2);
                continue;
            }
            println("INSN " + instruction.getAddress() + " " + instruction);
            address = instruction.getMaxAddress().add(1);
        }
    }
}

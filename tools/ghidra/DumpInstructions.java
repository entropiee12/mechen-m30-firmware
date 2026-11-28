// Dump decoded instructions from a mapped address range.
// Arguments: START COUNT
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;

public class DumpInstructions extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 2) {
            throw new Exception("usage: DumpInstructions.java START COUNT");
        }
        long startValue = Long.decode(getScriptArgs()[0]);
        int count = Integer.decode(getScriptArgs()[1]);
        if (count <= 0) throw new Exception("COUNT must be positive");

        Address address = currentProgram.getAddressFactory().getDefaultAddressSpace()
            .getAddress(startValue & ~1L);
        for (int i = 0; i < count; i++) {
            Instruction instruction = getInstructionAt(address);
            if (instruction == null) {
                disassemble(address);
                instruction = getInstructionAt(address);
            }
            if (instruction == null) {
                println(String.format("INSN address=%s missing", address));
                address = address.add(2);
                continue;
            }
            String operands = instruction.getNumOperands() == 0
                ? "" : instruction.getDefaultOperandRepresentation(0);
            StringBuilder bytes = new StringBuilder();
            for (byte value : instruction.getBytes()) {
                if (bytes.length() != 0) bytes.append(' ');
                bytes.append(String.format("%02x", value & 0xff));
            }
            println(String.format("INSN address=%s bytes=%s %s %s",
                instruction.getAddress(),
                bytes,
                instruction.getMnemonicString(),
                operands));
            address = instruction.getMaxAddress().add(1);
        }
    }
}

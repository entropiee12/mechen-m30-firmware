import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;

/** Dump MIPS16 instructions with their resolved references and pointed-to words. */
public class DumpInstructionsAndRefs extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 2) {
            throw new Exception("usage: DumpInstructionsAndRefs.java START COUNT");
        }

        Address address = currentProgram.getAddressFactory().getDefaultAddressSpace()
            .getAddress(Long.decode(getScriptArgs()[0]) & ~1L);
        int count = Integer.decode(getScriptArgs()[1]);
        for (int index = 0; index < count; index++) {
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
            for (Reference reference : instruction.getReferencesFrom()) {
                Address target = reference.getToAddress();
                String word = "unmapped";
                if (currentProgram.getMemory().contains(target)
                    && currentProgram.getMemory().contains(target.add(3))) {
                    word = String.format("%08x", getInt(target));
                }
                println(String.format("REF from=%s to=%s type=%s word=%s",
                    reference.getFromAddress(), target, reference.getReferenceType(), word));
            }

            address = instruction.getMaxAddress().add(1);
        }
    }
}

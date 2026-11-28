// Report store instructions that use a selected immediate offset.
// Arguments: comma-separated offsets, e.g. 0x41,0x42
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.OperandType;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.scalar.Scalar;
import java.util.LinkedHashMap;
import java.util.Map;

public class FindOffsetStores extends GhidraScript {
    private boolean isStore(String mnemonic) {
        String m = mnemonic.toLowerCase();
        return m.equals("sb") || m.equals("sh") || m.equals("sw")
            || m.equals("sbu") || m.equals("shu") || m.equals("sd")
            || m.equals("swr") || m.equals("swl");
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: FindOffsetStores.java 0x41,0x42");
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (String text : getScriptArgs()[0].split(",")) {
            counts.put(Long.decode(text) & 0xffffffffL, 0);
        }
        InstructionIterator instructions = currentProgram.getListing().getInstructions(true);
        while (instructions.hasNext()) {
            Instruction instruction = instructions.next();
            if (!isStore(instruction.getMnemonicString())) continue;
            for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
                if ((instruction.getOperandType(operand) & OperandType.SCALAR) == 0) continue;
                for (Object object : instruction.getOpObjects(operand)) {
                    if (!(object instanceof Scalar)) continue;
                    long value = ((Scalar)object).getUnsignedValue();
                    if (!counts.containsKey(value)) continue;
                    Function function = currentProgram.getFunctionManager()
                        .getFunctionContaining(instruction.getAddress());
                    String owner = function == null ? "<no-function>" : function.getName();
                    counts.put(value, counts.get(value) + 1);
                    println(String.format(
                        "OFFSET_STORE offset=0x%x address=%s owner=%s instruction=%s",
                        value, instruction.getAddress(), owner, instruction));
                }
            }
        }
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            println(String.format("OFFSET_STORE_TOTAL offset=0x%x count=%d",
                entry.getKey(), entry.getValue()));
        }
    }
}

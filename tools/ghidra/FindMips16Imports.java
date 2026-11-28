// Report MIPS16 indirect calls whose delay slot loads a3 with an import id.
// Arguments: start, end, comma-separated required selector values.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.scalar.Scalar;
import java.util.LinkedHashMap;
import java.util.Map;

public class FindMips16Imports extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 3) {
            throw new Exception("usage: FindMips16Imports.java START END REQUIRED");
        }
        Address start = currentProgram.getAddressFactory().getDefaultAddressSpace()
            .getAddress(Long.decode(getScriptArgs()[0]));
        Address end = currentProgram.getAddressFactory().getDefaultAddressSpace()
            .getAddress(Long.decode(getScriptArgs()[1]));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (String value : getScriptArgs()[2].split(",")) {
            counts.put(Long.decode(value) & 0xffffffffL, 0);
        }

        InstructionIterator iterator = currentProgram.getListing().getInstructions(start, true);
        int total = 0;
        while (iterator.hasNext()) {
            Instruction call = iterator.next();
            if (call.getAddress().compareTo(end) >= 0) break;
            String mnemonic = call.getMnemonicString().toLowerCase();
            if (mnemonic.startsWith("_")) mnemonic = mnemonic.substring(1);
            if (!mnemonic.equals("jalr") && !mnemonic.equals("jalrc")) continue;

            Instruction delay = currentProgram.getListing().getInstructionAt(
                call.getAddress().add(call.getLength()));
            if (delay == null || delay.getAddress().compareTo(end) >= 0) continue;
            String delayMnemonic = delay.getMnemonicString().toLowerCase();
            if (delayMnemonic.startsWith("_")) delayMnemonic = delayMnemonic.substring(1);
            if (!delayMnemonic.equals("li")) continue;

            Object[] registerObjects = delay.getOpObjects(0);
            if (registerObjects.length != 1 || !(registerObjects[0] instanceof Register)
                || !((Register) registerObjects[0]).getName().equalsIgnoreCase("a3")) continue;
            Object[] valueObjects = delay.getOpObjects(1);
            long selector = -1;
            for (Object value : valueObjects) {
                if (value instanceof Scalar) {
                    selector = ((Scalar) value).getUnsignedValue();
                    break;
                }
            }
            if (selector < 0) continue;
            total++;
            if (counts.containsKey(selector)) counts.put(selector, counts.get(selector) + 1);
            println(String.format(
                "MIPS16_IMPORT call=0x%08x selector=0x%x delay=0x%08x",
                call.getAddress().getOffset(), selector, delay.getAddress().getOffset()));
        }

        println(String.format("MIPS16_IMPORT_SUMMARY calls=%d", total));
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            println(String.format("MIPS16_IMPORT_TOTAL selector=0x%x count=%d",
                entry.getKey(), entry.getValue()));
            if (entry.getValue() == 0) {
                throw new Exception(String.format("required selector missing: 0x%x", entry.getKey()));
            }
        }
    }
}

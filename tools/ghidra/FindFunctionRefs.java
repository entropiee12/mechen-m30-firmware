import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;

/** Print code/data references to mapped function addresses and their owners. */
public class FindFunctionRefs extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: FindFunctionRefs.java 0x40154cec,0x40154f44");
        }

        for (String text : getScriptArgs()[0].split(",")) {
            Address target = currentProgram.getAddressFactory().getDefaultAddressSpace()
                .getAddress(Long.decode(text));
            Function function = currentProgram.getFunctionManager().getFunctionAt(target);
            if (function != null) {
                target = function.getEntryPoint();
            }

            int count = 0;
            for (Reference reference : currentProgram.getReferenceManager().getReferencesTo(target)) {
                Address source = reference.getFromAddress();
                Function owner = currentProgram.getFunctionManager().getFunctionContaining(source);
                String ownerName = owner == null ? "<no-function>" : owner.getName();
                println(String.format(
                    "FUNCTION_REF target=%s from=%s type=%s owner=%s",
                    target, source, reference.getReferenceType(), ownerName));
                count++;
            }
            println(String.format("FUNCTION_REF_TOTAL target=%s count=%d", target, count));
        }
    }
}

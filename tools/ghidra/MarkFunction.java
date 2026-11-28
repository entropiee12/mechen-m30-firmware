// Create/refresh functions at explicit addresses before DumpC.
// Arguments: comma-separated addresses.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;

public class MarkFunction extends GhidraScript {
    @Override
    public void run() throws Exception {
        for (String text : getScriptArgs()[0].split(",")) {
            Address address = currentProgram.getAddressFactory().getDefaultAddressSpace()
                .getAddress(Long.decode(text));
            Function containing = currentProgram.getFunctionManager().getFunctionContaining(address);
            if (containing != null && !containing.getEntryPoint().equals(address)) {
                currentProgram.getFunctionManager().removeFunction(containing.getEntryPoint());
            }
            disassemble(address);
            Function function = getFunctionAt(address);
            if (function == null) function = createFunction(address, null);
            println("MarkFunction: " + address + " -> " + (function == null ? "failed" : function.getName()));
        }
    }
}

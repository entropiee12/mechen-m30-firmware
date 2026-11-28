// Run Ghidra analysis over a mapped AP and print references to target addresses.
// Arguments: comma-separated target addresses.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

public class DumpRefsTo extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            throw new Exception("usage: DumpRefsTo.java address[,address...]");
        }

        analyzeAll(currentProgram);
        Listing listing = currentProgram.getListing();
        String[] targets = getScriptArgs()[0].split(",");
        for (String text : targets) {
            Address target = currentProgram.getAddressFactory().getDefaultAddressSpace()
                .getAddress(Long.decode(text));
            println("REF TARGET " + target);
            ReferenceIterator references = currentProgram.getReferenceManager().getReferencesTo(target);
            boolean found = false;
            while (references.hasNext()) {
                Reference reference = references.next();
                Address from = reference.getFromAddress();
                println("REF target=" + target + " from=" + from + " type=" + reference.getReferenceType()
                    + " function=" + listing.getFunctionContaining(from));
                found = true;
            }
            if (!found) println("REF none");
        }
    }
}

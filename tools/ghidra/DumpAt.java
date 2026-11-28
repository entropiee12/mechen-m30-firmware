// Decompile explicitly named addresses after a raw segment has been mapped.
// Arguments: comma-separated addresses, optional output file.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import java.io.PrintWriter;

public class DumpAt extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] addresses = getScriptArgs()[0].split(",");
        String output = getScriptArgs().length > 1 ? getScriptArgs()[1] : null;
        boolean containing = getScriptArgs().length > 2 && "containing".equals(getScriptArgs()[2]);
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        PrintWriter writer = output == null ? null : new PrintWriter(output);
        try {
            for (String text : addresses) {
                Address address = currentProgram.getAddressFactory().getDefaultAddressSpace()
                    .getAddress(Long.decode(text));
                Function function = getFunctionAt(address);
                boolean contained = false;
                if (function == null && containing) {
                    function = getFunctionContaining(address);
                    contained = function != null;
                }
                if (function == null) {
                    disassemble(address);
                    function = createFunction(address, null);
                }
                if (function == null) {
                    println("DumpAt: no function at " + address);
                    continue;
                }
                DecompileResults result = decompiler.decompileFunction(function, 90, monitor);
                String body = result.decompileCompleted()
                    ? result.getDecompiledFunction().getC()
                    : "// failed: " + result.getErrorMessage();
                if (writer != null) {
                    writer.println("// ===== " + function.getName() + " @ " + address);
                    writer.println(body);
                } else {
                    println("// ===== " + function.getName() + " @ " + address);
                    println(body);
                }
                String relation = contained ? " (containing)" : "";
                println("DumpAt: " + address + " -> " + function.getName() + relation
                    + " @ " + function.getEntryPoint());
            }
        } finally {
            if (writer != null) writer.close();
        }
    }
}

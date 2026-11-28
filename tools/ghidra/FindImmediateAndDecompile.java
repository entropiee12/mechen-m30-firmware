import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.scalar.Scalar;

/** Find immediate loads and decompile named functions in a mapped overlay. */
public class FindImmediateAndDecompile extends GhidraScript {
    @Override
    public void run() throws Exception {
        if (getScriptArgs().length < 1) {
            throw new Exception("usage: FindImmediateAndDecompile.java IMMEDIATE [FUNCTION ...]");
        }

        long immediate = Long.decode(getScriptArgs()[0]);
        if (immediate != 0) {
            Listing listing = currentProgram.getListing();
            for (Instruction instruction : listing.getInstructions(true)) {
                for (int operandIndex = 0; operandIndex < instruction.getNumOperands(); operandIndex++) {
                    Object[] objects = instruction.getOpObjects(operandIndex);
                    for (Object object : objects) {
                        if (!(object instanceof Scalar)) {
                            continue;
                        }

                        Scalar scalar = (Scalar)object;
                        if (scalar.getUnsignedValue() != immediate) {
                            continue;
                        }

                        Function owner = currentProgram.getFunctionManager()
                            .getFunctionContaining(instruction.getAddress());
                        String functionName = owner == null ? "<no-function>" : owner.getName();
                        println(String.format("IMMEDIATE %s function=%s instruction=%s",
                            Long.toHexString(immediate), functionName, instruction));
                    }
                }
            }
        }

        if (getScriptArgs().length < 2) {
            return;
        }

        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        for (int index = 1; index < getScriptArgs().length; index++) {
            Address entry = currentProgram.getAddressFactory().getDefaultAddressSpace()
                .getAddress(Long.decode(getScriptArgs()[index]) & ~1L);
            Function function = currentProgram.getFunctionManager().getFunctionAt(entry);
            if (function == null) {
                println("DECOMPILE missing function at " + entry);
                continue;
            }

            DecompileResults result = decompiler.decompileFunction(function, 30, monitor);
            println("DECOMPILE " + function.getName() + " @ " + entry);
            println(result.getDecompiledFunction() == null
                ? result.getErrorMessage()
                : result.getDecompiledFunction().getC());
        }
        decompiler.dispose();
    }
}

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scan mapped AP prologue functions for settings-offset stores and 0xf0-byte saves. */
public class ScanPersistentDaeWriters extends GhidraScript {
    private static final int MAX_FUNCTIONS = 1200;
    private static final Pattern STORE = Pattern.compile("\\+ 0x1[89]\\)\\s*(?:=(?!=)|\\+\\+|--)");
    private static final Pattern DAE_SAVE = Pattern.compile("0xf0,0x13\\)");
    private static final Pattern POOL = Pattern.compile("_UNK_([0-9a-f]{8})");

    private String poolWords(String line) {
        Matcher matcher = POOL.matcher(line);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            long location = Long.parseLong(matcher.group(1), 16);
            Address address = currentProgram.getAddressFactory()
                .getDefaultAddressSpace().getAddress(location);
            byte[] bytes = new byte[4];

            try {
                currentProgram.getMemory().getBytes(address, bytes);
            }

            catch (Exception exception) {
                result.append(String.format(" pool=0x%08x:unmapped", location));
                continue;
            }

            long value = (bytes[0] & 255L) | ((bytes[1] & 255L) << 8)
                | ((bytes[2] & 255L) << 16) | ((bytes[3] & 255L) << 24);
            result.append(String.format(" pool=0x%08x:0x%08x", location, value));
        }

        return result.toString();
    }

    @Override
    public void run() throws Exception {
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        int seen = 0;
        int failed = 0;
        int hits = 0;

        try {
            FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);

            while (functions.hasNext()) {
                Function function = functions.next();
                seen++;

                if (seen > MAX_FUNCTIONS) {
                    throw new Exception("function cap exceeded: " + MAX_FUNCTIONS);
                }

                DecompileResults result = decompiler.decompileFunction(function, 5, monitor);

                if (!result.decompileCompleted()) {
                    failed++;
                    continue;
                }

                String[] lines = result.getDecompiledFunction().getC().split("\\R");

                for (String line : lines) {
                    if (STORE.matcher(line).find() || DAE_SAVE.matcher(line).find()) {
                        println("WRITER_CANDIDATE function=" + function.getEntryPoint()
                            + " line=" + line.trim() + poolWords(line));
                        hits++;
                    }
                }
            }

            println("WRITER_SCAN_TOTAL functions=" + seen + " failures=" + failed
                + " candidates=" + hits + " cap=" + MAX_FUNCTIONS);
        }

        finally {
            decompiler.dispose();
        }
    }
}

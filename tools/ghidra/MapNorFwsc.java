// Map the hash-pinned NORFWSC member at its AFI Addr candidate.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

public class MapNorFwsc extends GhidraScript {
    private static final String EXPECTED_SHA256 =
        "5b72dee96b608139baea2a8d187e8c8bc4644600c0c085382093f9f2a95fb920";
    private static final int IMAGE_SIZE = 0x2a00;
    private static final long BASE = 0xbfc1e000L;
    private static final long ENTRY = 0xbfc1e200L;

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1)
            throw new Exception("usage: MapNorFwsc.java NORFWSC.BIN");
        byte[] data = Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        if (data.length != IMAGE_SIZE)
            throw new Exception(String.format("unexpected NORFWSC size: 0x%x", data.length));
        String actualSha = sha256(data);
        if (!EXPECTED_SHA256.equals(actualSha))
            throw new Exception("NORFWSC SHA-256 mismatch: " + actualSha);
        for (int i = 0; i < 0x200; i++)
            if (data[i] != 0) throw new Exception("NORFWSC prefix is not zero at " + i);

        AddressSpace space = currentProgram.getAddressFactory().getDefaultAddressSpace();
        Address start = space.getAddress(BASE);
        Memory memory = currentProgram.getMemory();
        for (var block : memory.getBlocks()) memory.removeBlock(block, monitor);
        var block = memory.createInitializedBlock("norfwsc", start,
            new ByteArrayInputStream(data), data.length, monitor, false);
        block.setRead(true);
        block.setExecute(true);

        Register isa = currentProgram.getRegister("ISA_MODE");
        if (isa == null) throw new Exception("processor has no ISA_MODE register");
        currentProgram.getProgramContext().setValue(isa, space.getAddress(ENTRY),
            space.getAddress(ENTRY + 0x1df), java.math.BigInteger.ZERO);
        if (!disassemble(space.getAddress(ENTRY)))
            throw new Exception("could not disassemble candidate MIPS32 entry");
        println(String.format("MapNorFwsc: bytes=0x%x sha256=%s base=0x%08x entry=0x%08x zero-prefix=0x200",
            data.length, actualSha, BASE, ENTRY));
    }
}

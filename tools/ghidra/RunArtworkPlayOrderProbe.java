import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Trace the firmware path where artwork refresh can precede the 0x311 wrapper. */
public class RunArtworkPlayOrderProbe extends GhidraScript {
    private static final long ENTRY=0x40154ddcL, STOP=0x700300L, STUB=0x700000L;
    private static final long RAM=0x10000000L, UI_FLAGS=RAM+0x100, PLAYER_FLAGS=RAM+0x400;
    private static final long UI_STATE=RAM+0x200, PLAY_STATE=RAM+0x300, ART=RAM+0x500, STACK=RAM+0x3000;

    private Address addr(long x) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(x); }
    private void putWord(Memory m,long a,long v)throws Exception { m.setBytes(addr(a),new byte[]{(byte)v,(byte)(v>>>8),(byte)(v>>>16),(byte)(v>>>24)}); }
    private long half(EmulatorHelper e,long a)throws Exception { byte[] b=e.readMemory(addr(a),2); return (b[0]&255L)|((b[1]&255L)<<8); }
    private String hex(long x){return String.format("0x%08x",x&0xffffffffL);}
    private void returnFromCall(EmulatorHelper e)throws Exception { long ra=e.readRegister("ra").longValue()&0xffffffffL; e.writeRegister("v0",1); e.writeRegister("pc",ra&~1L); }

    @Override public void run() throws Exception {
        if(getScriptArgs().length!=1) throw new Exception("usage: RunArtworkPlayOrderProbe.java STUBS.BIN");
        Memory m=currentProgram.getMemory();
        byte[] stubs=Files.readAllBytes(Paths.get(getScriptArgs()[0]));
        MemoryBlock sb=m.createInitializedBlock("artwork_play_stubs",addr(STUB),new ByteArrayInputStream(stubs),stubs.length,monitor,false);
        sb.setRead(true); sb.setWrite(false); sb.setExecute(true);
        MemoryBlock rb=m.createInitializedBlock("artwork_play_ram",addr(RAM),new ByteArrayInputStream(new byte[0x4000]),0x4000,monitor,false);
        rb.setRead(true); rb.setWrite(true); rb.setExecute(true);
        putWord(m,0x40155324L,STUB+1); putWord(m,0x40155334L,PLAYER_FLAGS);
        putWord(m,0x4015533cL,UI_STATE); putWord(m,0x4015534cL,PLAY_STATE);
        putWord(m,0x40155340L,STUB+0x21); putWord(m,0x40155344L,ART);
        putWord(m,UI_FLAGS,8); putWord(m,PLAYER_FLAGS,0x14);
        putWord(m,PLAY_STATE,2); putWord(m,PLAY_STATE+2,1);

        EmulatorHelper e=new EmulatorHelper(currentProgram);
        List<String> events=new ArrayList<>(); List<Integer> ui=new ArrayList<>();
        try {
            e.registerCallOtherCallback("setISAMode",new BreakCallBack(){public boolean pcodeCallback(PcodeOpRaw op){return true;}});
            Register isa=currentProgram.getRegister("ISA_MODE"); e.setContextRegister(isa,BigInteger.ONE);
            e.writeRegister("sp",STACK); e.writeRegister("ra",STOP|1L);
            e.writeRegister("a0",UI_FLAGS); e.writeRegister("a1",0); e.writeRegister("a2",0); e.writeRegister("a3",0);
            e.writeRegister("s1",0); e.writeRegister("pc",ENTRY);
            for(int n=0;n<100000;n++){
                long pc=e.readRegister("pc").longValue()&0xffffffffL, base=pc&~1L;
                if(base==STOP){
                    int[] expected={0x1002,0x1007,0x1006,0x1008,0x1005,0x1004,0x1002};
                    int[] actual=ui.stream().mapToInt(Integer::intValue).toArray();
                    if(!Arrays.equals(actual,expected)) throw new Exception("UI IDs "+Arrays.toString(actual));
                    int artAt=events.indexOf("artwork-wrapper"), playAt=events.indexOf("play-0x311-wrapper");
                    if(artAt<0||playAt<0||artAt>=playAt) throw new Exception("wrong call order "+events);
                    println("ARTWORK PLAY ORDER DYNAMIC PASS branch=paint-0x8 player-flags=0x14 ui="+Arrays.toString(actual)+" events="+events);
                    return;
                }
                if(base==STUB){ long a0=e.readRegister("a0").longValue()&0xffffffffL; ui.add((int)half(e,a0)); returnFromCall(e); continue; }
                if(base==0x40154c00L){events.add("artwork-wrapper");returnFromCall(e);continue;}
                if(base==0xbfc16588L){events.add("play-0x311-wrapper");returnFromCall(e);continue;}
                if(base==0x4805447cL){events.add("artwork-blit");returnFromCall(e);continue;}
                if(base==0xbfc164b0L){events.add("post-play-0x32d-wrapper");returnFromCall(e);continue;}
                if(!e.step(monitor)) throw new Exception("stopped at "+hex(pc)+": "+e.getLastError());
            }
            throw new Exception("step limit at "+hex(e.readRegister("pc").longValue()));
        } finally { e.dispose(); }
    }
}

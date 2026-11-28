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
import java.util.ArrayList;
import java.util.List;

/** Execute the real music.ap mode-4 advance branch without rewriting AP literals. */
public class RunAsrcMode4DynamicProbe extends GhidraScript {
    private static final long ENTRY=0x40494e94L, STOP=0x700300L;
    private static final long RAM=0x9fc14000L, RAM_SIZE=0x4000L;
    private static final long EXCEPTION_GET=0xbfc164dcL, ADVANCE=0x405151a0L;
    private static final long END_NOTICE=0x40394eb4L, END_UI=0xbfc16408L;
    private Address addr(long x){return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(x);}
    private void bytes(EmulatorHelper e,long a,byte[] b)throws Exception{e.writeMemory(addr(a),b);}
    private void word(EmulatorHelper e,long a,long v)throws Exception{bytes(e,a,new byte[]{(byte)v,(byte)(v>>>8),(byte)(v>>>16),(byte)(v>>>24)});}
    private void one(EmulatorHelper e,long a,int v)throws Exception{bytes(e,a,new byte[]{(byte)v});}
    private long reg(EmulatorHelper e,String n)throws Exception{return e.readRegister(n).longValue()&0xffffffffL;}
    private long readWord(EmulatorHelper e,long a)throws Exception{byte[] b=e.readMemory(addr(a),4);return (b[0]&255L)|((b[1]&255L)<<8)|((b[2]&255L)<<16)|((b[3]&255L)<<24);}
    private void require(boolean ok,String message)throws Exception{if(!ok)throw new Exception(message);}
    private void returnFromMips16(EmulatorHelper e)throws Exception{long ra=reg(e,"ra");require((ra&1L)==1L,String.format("caller return address is not MIPS16-tagged: %08x",ra));e.writeRegister("pc",ra&~1L);}

    private List<Long> runCase(String label,int mode,int progress,int currentIndex,List<Long> expectedAdvances,int expectedIndex,boolean expectEnd)throws Exception{
        EmulatorHelper e=new EmulatorHelper(currentProgram); List<Long> advances=new ArrayList<>();
        List<Long> endNotices=new ArrayList<>(); int[] endUiCalls={0};
        try{
            // Clear only the synthetic runtime state. AP literals remain untouched.
            bytes(e,RAM,new byte[(int)RAM_SIZE]);
            one(e,0x9fc1a1d2L,0);                  // explicit UI event state read by FUN_40495160
            one(e,0x9fc15719L,mode);               // playback mode byte
            word(e,0x9fc15730L,0);                 // current item sentinel
            one(e,0x9fc15738L,currentIndex);       // active item index (word-slot + 2)
            one(e,0x9fc15739L,3);                  // item count
            word(e,0x9fc15420L,progress);          // elapsed/advance value
            word(e,0x9fc1541cL,10);                 // mode threshold
            one(e,0x9fc15428L,0);                   // no pending switch
            one(e,0x9fc156ceL,0);                   // no reverse event
            one(e,0x9fc1571aL,0);                   // no special repeat state

            // The handler and captured callback pointers are MIPS16. Their odd
            // pointer tags and AP contexts are asserted before this run.
            e.registerCallOtherCallback("setISAMode",new BreakCallBack(){@Override public boolean pcodeCallback(PcodeOpRaw op){return true;}});
            e.setContextRegister(currentProgram.getRegister("ISA_MODE"),BigInteger.ONE);
            e.writeRegister("sp",RAM+0x3f00); e.writeRegister("ra",STOP|1L); e.writeRegister("s1",0);
            e.writeRegister("a0",0);e.writeRegister("a1",0);e.writeRegister("a2",0);e.writeRegister("a3",0);
            e.writeRegister("pc",ENTRY);
            boolean returned=false;
            for(int step=0;step<30000;step++){
                long pc=reg(e,"pc"),base=pc&~1L;
                if(base==STOP){returned=true;break;}
                if(base==EXCEPTION_GET){
                    long out=reg(e,"a0"); word(e,out,100); // stable current-time sample
                    e.writeRegister("v0",0);returnFromMips16(e);continue;
                }
                if(base==ADVANCE){
                    advances.add(reg(e,"a0"));e.writeRegister("v0",0);returnFromMips16(e);continue;
                }
                if(base==END_NOTICE){endNotices.add(reg(e,"a0"));e.writeRegister("v0",0);returnFromMips16(e);continue;}
                if(base==END_UI){endUiCalls[0]++;e.writeRegister("v0",0);returnFromMips16(e);continue;}
                if(!e.step(monitor))throw new Exception(String.format("mode=%d progress=%d stopped at %08x: %s",mode,progress,pc,e.getLastError()));
            }
            require(returned,"mode="+mode+" did not return");
            int actualIndex=oneValue(e,0x9fc15738L)&255;
            println(String.format("ASRC MODE CASE mode=%d progress=%d start-index=%d end-index=%d advance-args=%s end-notice-args=%s end-ui-calls=%d",mode,progress,currentIndex,actualIndex,advances,endNotices,endUiCalls[0]));
            require(advances.equals(expectedAdvances),label+" advance args="+advances+" expected="+expectedAdvances);
            require(actualIndex==expectedIndex,label+" final index="+actualIndex+" expected="+expectedIndex);
            require(expectEnd ? endNotices.size()==1 && endUiCalls[0]==1 : endNotices.isEmpty() && endUiCalls[0]==0,label+" unexpected end callbacks: notice="+endNotices+" ui="+endUiCalls[0]);
            verifyEmulatedLiteralPool(e);
            return advances;
        }finally{e.dispose();}
    }
    private int oneValue(EmulatorHelper e,long a)throws Exception{return e.readMemory(addr(a),1)[0]&255;}
    private void verifyLiteralPool()throws Exception{
        long[] addresses={0x40495120L,0x40495124L,0x40495128L,0x4049512cL,0x40495130L,0x40495134L,0x40495138L,0x4049513cL,0x40495140L,0x40495144L,0x40495148L,0x4049514cL,0x40495150L,0x40495154L,0x40495158L,0x4049515cL};
        long[] expected={0x9fc15718L,0x9fc156ccL,0xbfc164ddL,0x9fc15428L,0x9fc15730L,0x9fc15420L,0x9fc1541cL,0x9fc1542cL,0x40194c01L,0x40194d31L,0x40494c8dL,0x405151a1L,0x9fc15418L,0x40394eb5L,0xbfc16409L,0x9fc15580L};
        Memory m=currentProgram.getMemory();byte[] b=new byte[4];
        for(int i=0;i<addresses.length;i++){m.getBytes(addr(addresses[i]),b);long value=(b[0]&255L)|((b[1]&255L)<<8)|((b[2]&255L)<<16)|((b[3]&255L)<<24);require(value==expected[i],String.format("literal changed at %08x: %08x",addresses[i],value));}
    }
    private void verifyEmulatedLiteralPool(EmulatorHelper e)throws Exception{
        long[] addresses={0x40495120L,0x40495124L,0x40495128L,0x4049512cL,0x40495130L,0x40495134L,0x40495138L,0x4049513cL,0x40495140L,0x40495144L,0x40495148L,0x4049514cL,0x40495150L,0x40495154L,0x40495158L,0x4049515cL};
        long[] expected={0x9fc15718L,0x9fc156ccL,0xbfc164ddL,0x9fc15428L,0x9fc15730L,0x9fc15420L,0x9fc1541cL,0x9fc1542cL,0x40194c01L,0x40194d31L,0x40494c8dL,0x405151a1L,0x9fc15418L,0x40394eb5L,0xbfc16409L,0x9fc15580L};
        for(int i=0;i<addresses.length;i++) require(readWord(e,addresses[i])==expected[i],String.format("emulated literal changed at %08x",addresses[i]));
    }
    private void verifyMips16Callbacks()throws Exception{
        long[] pointers={0xbfc164ddL,0x40194c01L,0x40194d31L,0x40494c8dL,0x405151a1L,0x40394eb5L,0xbfc16409L};
        for(long pointer:pointers) require((pointer&1L)==1L,String.format("callback is not MIPS16-tagged: %08x",pointer));
        Register isa=currentProgram.getRegister("ISA_MODE");
        for(long entry:new long[]{ENTRY,0x40494db0L,0x40495160L}){
            BigInteger mode=currentProgram.getProgramContext().getValue(isa,addr(entry),false);
            require(mode!=null && BigInteger.ONE.equals(mode),String.format("expected MIPS16 context at %08x, got %s",entry,mode));
        }
    }
    @Override public void run()throws Exception{
        Memory m=currentProgram.getMemory();
        MemoryBlock ram=m.getBlock(addr(RAM));
        if(ram==null) ram=m.createInitializedBlock("asrc_mode4_runtime_ram",addr(RAM),new ByteArrayInputStream(new byte[(int)RAM_SIZE]),RAM_SIZE,monitor,false);
        else require(ram.getStart().equals(addr(RAM)) && ram.getSize()==RAM_SIZE && "asrc_mode4_runtime_ram".equals(ram.getName()),"refusing unexpected block at synthetic RAM base: "+ram.getName()+" "+ram.getStart()+".."+ram.getEnd());
        ram.setRead(true);ram.setWrite(true);ram.setExecute(true);
        MemoryBlock eventRam=m.getBlock(addr(0x9fc1a000L));
        if(eventRam==null) eventRam=m.createInitializedBlock("asrc_mode4_runtime_state",addr(0x9fc1a000L),new ByteArrayInputStream(new byte[0x2000]),0x2000,monitor,false);
        else require(eventRam.getStart().equals(addr(0x9fc1a000L)) && eventRam.getSize()==0x2000 && "asrc_mode4_runtime_state".equals(eventRam.getName()),"refusing unexpected block at event RAM base: "+eventRam.getName()+" "+eventRam.getStart()+".."+eventRam.getEnd());
        eventRam.setRead(true);eventRam.setWrite(true);eventRam.setExecute(true);
        verifyLiteralPool();
        verifyMips16Callbacks();
        boolean stock=getScriptArgs().length>0 && "stock".equals(getScriptArgs()[0]);
        List<Long> m2=runCase("mode2",2,20,2,List.of(0L),0,false);
        List<Long> m4=stock
            ? runCase("mode4-stock-end",4,20,2,List.of(),3,true)
            : runCase("mode4-community-end",4,20,2,List.of(0L),0,false);
        List<Long> below=runCase("mode4-below-threshold",4,9,2,List.of(),2,false);
        List<Long> at=stock
            ? runCase("mode4-at-threshold-stock",4,10,2,List.of(),3,true)
            : runCase("mode4-at-threshold-community",4,10,2,List.of(0L),0,false);
        List<Long> above=stock
            ? runCase("mode4-above-threshold-stock",4,11,2,List.of(),3,true)
            : runCase("mode4-above-threshold-community",4,11,2,List.of(0L),0,false);
        require(below.isEmpty(),"mode 4 advanced below threshold");
        require(stock ? at.isEmpty() && above.isEmpty() : at.equals(List.of(0L)) && above.equals(List.of(0L)),"threshold boundary mismatch");
        if(stock) require(m4.isEmpty(),"stock mode4 unexpectedly used community wrap path");
        else require(m2.equals(m4),"community mode4 does not match the mode2 end-wrap path");
        verifyLiteralPool();
        println("ASRC MODE4 DYNAMIC PASS actual-music.ap code; callback literals verified before-and-after; resident/UI boundary stubbed; stock="+stock);
    }
}

import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Instruction;
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

/** Execute the music tick wrapper and its real EOF handler through the AP callback table. */
public class RunAsrcMusicTickProbe extends GhidraScript {
    private static final long ENTRY=0x400d4edcL, STOP=0x700300L;
    private static final long RAM=0x9fc14000L, RAM_SIZE=0x4000L;
    private static final long STATE_RAM=0x9fc1a000L, STATE_SIZE=0x2000L;
    private static final long MODE4_HANDLER=0x40494e94L, TICK_HANDLER_PTR=0x400d509cL;
    private static final long EVENT_INIT=0xbfc164b0L, SNAPSHOT=0xbfc164dcL;
    private static final long ADVANCE_UI=0x405151a0L, END_NOTICE=0x40394eb4L, END_UI=0xbfc16408L;
    private static final long WRAPPER_PTR_LOAD=0x400d4fdaL, WRAPPER_CALLSITE=0x400d4fdcL;
    private Address addr(long x){return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(x);}
    private void bytes(EmulatorHelper e,long a,byte[] b)throws Exception{e.writeMemory(addr(a),b);}
    private void word(EmulatorHelper e,long a,long v)throws Exception{bytes(e,a,new byte[]{(byte)v,(byte)(v>>>8),(byte)(v>>>16),(byte)(v>>>24)});}
    private void one(EmulatorHelper e,long a,int v)throws Exception{bytes(e,a,new byte[]{(byte)v});}
    private long reg(EmulatorHelper e,String n)throws Exception{return e.readRegister(n).longValue()&0xffffffffL;}
    private int oneValue(EmulatorHelper e,long a)throws Exception{return e.readMemory(addr(a),1)[0]&255;}
    private long readWord(EmulatorHelper e,long a)throws Exception{byte[] b=e.readMemory(addr(a),4);return (b[0]&255L)|((b[1]&255L)<<8)|((b[2]&255L)<<16)|((b[3]&255L)<<24);}
    private void require(boolean ok,String message)throws Exception{if(!ok)throw new Exception(message);}
    private void returnFromMips16(EmulatorHelper e)throws Exception{long ra=reg(e,"ra");require((ra&1L)==1L,String.format("non-MIPS16 return address: %08x",ra));e.writeRegister("pc",ra&~1L);}
    private void mapRam(Memory m,long base,long size,String name)throws Exception{
        MemoryBlock b=m.getBlock(addr(base));
        if(b==null)b=m.createInitializedBlock(name,addr(base),new ByteArrayInputStream(new byte[(int)size]),size,monitor,false);
        else require(b.getStart().equals(addr(base))&&b.getSize()==size&&name.equals(b.getName()),"unexpected memory block at "+name+": "+b.getName()+" "+b.getStart()+".."+b.getEnd());
        b.setRead(true);b.setWrite(true);b.setExecute(true);
    }
    private void verifyLiteralPool(EmulatorHelper e)throws Exception{
        byte[] b=e.readMemory(addr(TICK_HANDLER_PTR),4);
        long value=(b[0]&255L)|((b[1]&255L)<<8)|((b[2]&255L)<<16)|((b[3]&255L)<<24);
        require(value==MODE4_HANDLER+1,String.format("tick callback pointer changed: %08x",value));
        long[] ptrs={0xbfc164b1L,0xbfc164ddL,0x40494e95L,0x405151a1L,0x40394eb5L,0xbfc16409L};
        for(long p:ptrs)require((p&1L)==1L,String.format("expected MIPS16 pointer: %08x",p));
    }
    private void inspectCallsite()throws Exception{
        Register isa=currentProgram.getRegister("ISA_MODE");
        AddressSet set=new AddressSet(addr(WRAPPER_CALLSITE-0x20),addr(WRAPPER_CALLSITE+0x10));
        DisassembleCommand cmd=new DisassembleCommand(set,null,true);
        cmd.setInitialContext(new ghidra.program.model.lang.RegisterValue(isa,BigInteger.ONE));
        if(!cmd.applyTo(currentProgram,monitor))throw new Exception("wrapper MIPS16 disassembly failed: "+cmd.getStatusMsg());
        Instruction load=currentProgram.getListing().getInstructionAt(addr(WRAPPER_PTR_LOAD));
        Instruction call=currentProgram.getListing().getInstructionAt(addr(WRAPPER_CALLSITE));
        require(load!=null&&"lw v0,0xc4(pc)".equals(load.toString()),"unexpected callback pointer load: "+load);
        require(call!=null&&"jalrc v0".equals(call.toString()),"unexpected callback indirect-call instruction: "+call);
        println(String.format("CALLSITE STATIC PASS %08x %s -> %08x %s literal=%08x",WRAPPER_PTR_LOAD,load,WRAPPER_CALLSITE,call,TICK_HANDLER_PTR));
    }
    @Override public void run()throws Exception{
        Memory m=currentProgram.getMemory();mapRam(m,RAM,RAM_SIZE,"asrc_tick_probe_ram");mapRam(m,STATE_RAM,STATE_SIZE,"asrc_tick_probe_state");
        inspectCallsite();
        EmulatorHelper e=new EmulatorHelper(currentProgram);List<Long> advances=new ArrayList<>();List<Long> endNotices=new ArrayList<>();int[] snapshots={0},eventInits={0},endUi={0},handlerHits={0},pointerCallHits={0};long[] handlerEntryRa={0},handlerEntryFrom={0},callTarget={0};
        try{
            verifyLiteralPool(e);
            bytes(e,RAM,new byte[(int)RAM_SIZE]);bytes(e,STATE_RAM,new byte[(int)STATE_SIZE]);
            one(e,0x9fc15416L,0);                         // initial UI/event latch
            one(e,0x9fc156ccL,0);one(e,0x9fc156cfL,0);  // no directory-refresh request
            word(e,0x9fc15418L,0x4000);                   // active playback tick
            word(e,0x9fc15580L,0);word(e,0x9fc15730L,0);
            one(e,0x9fc15420L,0);one(e,0x9fc15424L,0);one(e,0x9fc15416L,0);
            one(e,0x9fc15719L,4);one(e,0x9fc15738L,2);one(e,0x9fc15739L,3);
            word(e,0x9fc1541cL,10);word(e,0x9fc15428L,0);one(e,0x9fc156ceL,0);one(e,0x9fc1571aL,0);
            e.registerCallOtherCallback("setISAMode",new BreakCallBack(){@Override public boolean pcodeCallback(PcodeOpRaw op){return true;}});
            Register isa=currentProgram.getRegister("ISA_MODE");e.setContextRegister(isa,BigInteger.ONE);
            e.writeRegister("sp",RAM+0x3f00);e.writeRegister("ra",STOP|1L);e.writeRegister("s1",0);
            e.writeRegister("a0",RAM+0x200);e.writeRegister("a1",RAM+0x280);e.writeRegister("a2",0);e.writeRegister("a3",0);e.writeRegister("pc",ENTRY);
            boolean returned=false;long previousPc=-1;
            for(int step=0;step<50000;step++){
                long pc=reg(e,"pc"),base=pc&~1L;
                if(base==STOP){returned=true;break;}
                if(base==WRAPPER_CALLSITE){
                    pointerCallHits[0]++;
                    require(previousPc==WRAPPER_PTR_LOAD,"callback callsite was not immediately preceded by pointer load: "+String.format("%08x",previousPc));
                    callTarget[0]=reg(e,"v0");
                    require(callTarget[0]==readWord(e,TICK_HANDLER_PTR),String.format("jalrc target %08x differs from callback literal %08x",callTarget[0],readWord(e,TICK_HANDLER_PTR)));
                }
                if(base==MODE4_HANDLER){handlerHits[0]++;handlerEntryRa[0]=reg(e,"ra");handlerEntryFrom[0]=previousPc;}
                if(base==EVENT_INIT){eventInits[0]++;e.writeRegister("v0",0);returnFromMips16(e);continue;}
                if(base==SNAPSHOT){snapshots[0]++;word(e,reg(e,"a0"),100);e.writeRegister("v0",0);returnFromMips16(e);continue;}
                if(base==ADVANCE_UI){advances.add(reg(e,"a0"));e.writeRegister("v0",0);returnFromMips16(e);continue;}
                if(base==END_NOTICE){endNotices.add(reg(e,"a0"));e.writeRegister("v0",0);returnFromMips16(e);continue;}
                if(base==END_UI){endUi[0]++;e.writeRegister("v0",0);returnFromMips16(e);continue;}
                if(!e.step(monitor))throw new Exception(String.format("tick stopped at %08x: %s",pc,e.getLastError()));
                previousPc=base;
            }
            require(returned,"tick did not return");
            require(handlerHits[0]==1,"expected one dynamic dispatch into mode-4 handler, got "+handlerHits[0]);
            require(pointerCallHits[0]==1,"expected one execution of the callback jalrc, got "+pointerCallHits[0]);
            require(handlerEntryFrom[0]==WRAPPER_CALLSITE,"mode-4 handler did not enter from callback jalrc: "+String.format("%08x",handlerEntryFrom[0]));
            require((handlerEntryRa[0]&~1L)==WRAPPER_CALLSITE+2,"handler return address does not match jalrc continuation: "+String.format("%08x",handlerEntryRa[0]));
            require(snapshots[0]==2,"expected wrapper and EOF snapshots, got "+snapshots[0]);
            require(eventInits[0]==1,"expected one event initialization, got "+eventInits[0]);
            boolean stock=getScriptArgs().length>0&&"stock".equals(getScriptArgs()[0]);
            require(advances.equals(stock?List.of():List.of(0L)),"unexpected advance-index UI callback args: "+advances);
            int expectedIndex=stock?3:0;
            require((oneValue(e,0x9fc15738L)&255)==expectedIndex,"unexpected final index: "+oneValue(e,0x9fc15738L));
            require(stock?endNotices.equals(List.of(0L))&&endUi[0]==1:endNotices.isEmpty()&&endUi[0]==0,"unexpected end notification/UI path: "+endNotices+"/"+endUi[0]);
            verifyLiteralPool(e);
            println(String.format("ASRC TICK DYNAMIC PASS stock=%s steps=real-wrapper+literal-load+jalrc+real-mode4 snapshot-calls=%d event-init=%d callback-jalrc-hits=%d call-target=%08x handler=0x%08x handler-hits=%d entry-from=%08x return-to=%08x mode=4 final-index=%d advance-ui-args=%s end-notice=%s end-ui=%d emulated-literal=unchanged",stock,snapshots[0],eventInits[0],pointerCallHits[0],callTarget[0],MODE4_HANDLER,handlerHits[0],handlerEntryFrom[0],handlerEntryRa[0],oneValue(e,0x9fc15738L)&255,advances,endNotices,endUi[0]));
        }finally{e.dispose();}
    }
}

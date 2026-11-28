import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.script.GhidraScript;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.MemoryBlock;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Execute real drv_ui B3 text-marquee code; intercept absent providers and services. */
public class RunDrvUiMarqueeDynamicProbe extends GhidraScript {
    private static final long ENTRY=0x260e4ff4L, STOP=0x700300L, RAM=0x9fc0b000L;
    private static final long PARAM=RAM+0x100, ITEMS=RAM+0x200, DESC=RAM+0x300, STACK=RAM+0x1ff0;
    private static final long STUB_BASE=0x10001000L;
    private Address addr(long x){return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(x);}
    private void word(EmulatorHelper e,long a,long v)throws Exception{e.writeMemory(addr(a),new byte[]{(byte)v,(byte)(v>>>8),(byte)(v>>>16),(byte)(v>>>24)});}
    private void half(EmulatorHelper e,long a,int v)throws Exception{e.writeMemory(addr(a),new byte[]{(byte)v,(byte)(v>>>8)});}
    private void bytes(EmulatorHelper e,long a,byte[] b)throws Exception{e.writeMemory(addr(a),b);}
    private long reg(EmulatorHelper e,String n)throws Exception{return e.readRegister(n).longValue()&0xffffffffL;}
    private byte[] get(EmulatorHelper e,long a,int n)throws Exception{return e.readMemory(addr(a),n);}
    private String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
    private void require(boolean b,String m)throws Exception{if(!b)throw new Exception(m);}

    private void runCase(int mode,int measuredWidth)throws Exception{
        var memory=currentProgram.getMemory();
        memory.setBytes(addr(RAM),new byte[0x2000]);
        EmulatorHelper e=new EmulatorHelper(currentProgram);
        List<String> farCalls=new ArrayList<>(); List<Integer> regionX=new ArrayList<>(); int measureCalls=0, residentCalls=0, helperCalls=0, transformCalls=0, stepCount=0;
        try{
            // Layout and per-run mode state are synthetic; B3 instructions are the extracted bytes.
            half(e,PARAM,5);half(e,PARAM+2,20);half(e,PARAM+4,120);half(e,PARAM+6,16);
            word(e,ITEMS, DESC);word(e,ITEMS+4,'A');word(e,ITEMS+8,DESC);word(e,ITEMS+12,'B');
            bytes(e,DESC,new byte[]{'L',0,'o',0,'n',0,'g',0,' ',0,'F',0,'o',0,'l',0,'d',0,'e',0,'r',0,0,0});
            // B3 literal-pool globals referenced by this function.
            word(e,0x260e52acL,STUB_BASE+0x01);word(e,0x260e52b0L,STUB_BASE+0x21);
            word(e,0x260e52bcL,STUB_BASE+0x41);word(e,0x260e52c0L,STUB_BASE+0x61);word(e,0x260e52c4L,STUB_BASE+0x81);
            word(e,0x260e52dcL,STUB_BASE+0xa1);word(e,0x260e52e0L,STUB_BASE+0xc1);word(e,0x260e52e4L,STUB_BASE+0xe1);
            word(e,0x260e52e8L,STUB_BASE+0x101);word(e,0x260e52ecL,STUB_BASE+0x121);
            word(e,0x9fc0bfa8L,STUB_BASE+0x141);word(e,0x9fc0bf98L,STUB_BASE+0x161);word(e,0x9fc0bf84L,STUB_BASE+0x181);
            word(e,0x9fc0bf50L,0x12345678);word(e,0x9fc0be24L+0x12,1);word(e,0x9fc0be24L+0x14,0);
            word(e,0x9fc0be4cL+6,0);word(e,0x9fc0be4cL+8,0);
            bytes(e,0x9fc0be5dL,new byte[]{1});bytes(e,0x9fc0bd25L,new byte[]{(byte)120});
            bytes(e,0x9fc0be5fL,new byte[]{0});bytes(e,0x9fc0be9dL,new byte[]{(byte)mode});
            word(e,0x9fc0bd2cL,0x9fc0b500L);bytes(e,0x9fc0b500L,new byte[]{'L',0,'o',0,'n',0,'g',0,0,0});

            e.registerCallOtherCallback("setISAMode",new BreakCallBack(){@Override public boolean pcodeCallback(PcodeOpRaw op){return true;}});
            e.setContextRegister(currentProgram.getRegister("ISA_MODE"),BigInteger.ONE);
            e.writeRegister("sp",STACK);e.writeRegister("ra",STOP|1L);e.writeRegister("a0",PARAM);e.writeRegister("a1",ITEMS);e.writeRegister("a2",1);e.writeRegister("a3",0);e.writeRegister("s0",0);e.writeRegister("s1",0);e.writeRegister("pc",ENTRY);
            for(stepCount=0;stepCount<50000;stepCount++){
                long pc=reg(e,"pc"),p=pc&~1L,ra;
                if(p==STOP)break;
                if(p==STUB_BASE+0x120){long a0=reg(e,"a0"),a1=reg(e,"a1"),a2=reg(e,"a2");byte[] rb=get(e,a1,8); int rx=(rb[0]&255)|((rb[1]&255)<<8); regionX.add(rx); farCalls.add(String.format("desc=%08x region=%08x mode=%08x descbytes=%s regionbytes=%s",a0,a1,a2,hex(get(e,a0,0x18)),hex(rb)));ra=reg(e,"ra");e.writeRegister("v0",0);e.writeRegister("pc",ra&~1L);continue;}
                if(p==STUB_BASE+0xe0){measureCalls++;ra=reg(e,"ra");e.writeRegister("v0",measuredWidth);e.writeRegister("pc",ra&~1L);continue;}
                if(p==STUB_BASE+0x40){helperCalls++;long a3=reg(e,"a3");ra=reg(e,"ra");e.writeRegister("v0",a3==3||a3==7?32:0);e.writeRegister("pc",ra&~1L);continue;}
                if(p==STUB_BASE+0x100){residentCalls++;ra=reg(e,"ra");e.writeRegister("v0",0);e.writeRegister("pc",ra&~1L);continue;}
                if(p==STUB_BASE+0xa0||p==STUB_BASE+0xc0)transformCalls++;
                if(p>=STUB_BASE&&p<STUB_BASE+0x200){ra=reg(e,"ra");e.writeRegister("v0",0);e.writeRegister("pc",ra&~1L);continue;}
                if(!e.step(monitor))throw new Exception(String.format("mode=%x stopped at %08x: %s",mode,pc,e.getLastError()));
            }
            require((reg(e,"pc")&~1L)==STOP,"B3 did not return");
            require(measureCalls==(mode==0x17?2:0),"unexpected text width calls "+measureCalls);
            require(farCalls.size()==2,"expected two B16-qualified external calls, got "+farCalls.size());
            require(mode==0x17 ? transformCalls==4 : transformCalls==0,"unexpected transform path count "+transformCalls);
            int expectedX=mode==0x17&&measuredWidth<120?45:5;
            require(regionX.get(0)==expectedX&&regionX.get(1)==expectedX,"unexpected region x for measured width: "+regionX);
            println(String.format("DRV_UI MARQUEE CASE mode=0x%02x measured-width=%d steps=%d width-calls=%d text-helper-calls=%d resident-forwarder-calls=%d transforms=%d far-calls=%d",mode,measuredWidth,stepCount,measureCalls,helperCalls,residentCalls,transformCalls,farCalls.size()));
            for(String call:farCalls)println("FAR_CALL "+call);
        }finally{e.dispose();}
    }
    @Override public void run()throws Exception{
        var memory=currentProgram.getMemory();
        MemoryBlock data=memory.createInitializedBlock("drvui_probe_ram",addr(RAM),new ByteArrayInputStream(new byte[0x2000]),0x2000,monitor,false);
        data.setRead(true);data.setWrite(true);data.setExecute(true);
        for(MemoryBlock block:memory.getBlocks())block.setWrite(true);
        runCase(0x16,80);runCase(0x17,80);runCase(0x17,180);println("DRV_UI MARQUEE DYNAMIC PASS actual-B3 code; B16-qualified external target and UI/MMIO providers stubbed");
    }
}

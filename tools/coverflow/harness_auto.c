/* Run the packed automatic loader, cache, quantizer and renderer together.
 * Only stock metadata/JPEG services, storage and LCD hardware are fixtures.
 * Playlist path parsing is exercised separately by m3uplaytest.c. */
#include "m3u_queue.h"
#define SECTION(s) __attribute__((section(s),used))
volatile struct vstate shared SECTION(".shared");
u8 hardware[0x20000] SECTION(".hardware");
#define REG(type,offset) (*(type *)(hardware+(offset)))
extern void run_refresh(u32);
static u16 lcd[4096],frame[240][320];
static unsigned failure,line,installs,mounts,opened,decodes,track,missing,preinstalled;
static unsigned offsets[10],lookup_fail,read_fail,decode_fail,library_bad,neighbor_opens,marker_count;
static u32 directory_state[19],cursor_state[2];
static u32 saved_dir0,saved_cursor0;
static unsigned snapshots,restores,offset_fail;
static void check_line(int ok,unsigned line_number)
{
    if(ok) return;
    failure++;
    static char msg[]="FAIL line 000\n";
    msg[10]='0'+line_number/100;msg[11]='0'+line_number/10%10;msg[12]='0'+line_number%10;
    register u32 v0 asm("$2")=4004;
    register u32 a0 asm("$4")=2;
    register const char *a1 asm("$5")=msg;
    register u32 a2 asm("$6")=sizeof(msg)-1;
    asm volatile("syscall":"+r"(v0):"r"(a0),"r"(a1),"r"(a2):"memory","$7");
}
#define check(ok) check_line((ok),__LINE__)
static void clear(void *p,unsigned size) { u8 *b=p;while(size--) *b++=0; }
u32 SECTION(".sysmock") sys_mock(u32 a,u32 b,u32 c,u32 op)
{
    (void)a;(void)b;(void)c;
    if(op==10) {if(preinstalled) return (u32)-1;installs++;}
    else if(op==11) installs--;
    else if(op==41) { mounts++;return 2; }
    else if(op==42) mounts--;
    else failure++;
    return 0;
}
static u16 pixel(unsigned x,unsigned y,unsigned id)
{
    if(id==0) return (u16)(y*124+x);
    /* Asymmetric quadrants catch palette sorting that scrambles spatial order. */
    return y<16?(x<16?0xf800:0x07e0):(x<16?0x001f:0xffff);
}
long SECTION(".fsmock") fs_mock(u32 ctx,u32 a,u32 b,u32 c,u32 op)
{
    check(ctx==2 || ctx==7);
    if(op==OP_OFFSET) {
        if(offset_fail) return 0;
        if(!c) {
            for(unsigned i=0;i<19;i++) ((u32 *)a)[i]=directory_state[i];
            for(unsigned i=0;i<2;i++) ((u32 *)b)[i]=cursor_state[i];
            saved_dir0=directory_state[0];saved_cursor0=cursor_state[0];
            snapshots++;
        } else {
            check(((u32 *)a)[0]==saved_dir0 && ((u32 *)b)[0]==saved_cursor0);
            for(unsigned i=0;i<19;i++) directory_state[i]=((u32 *)a)[i];
            for(unsigned i=0;i<2;i++) cursor_state[i]=((u32 *)b)[i];
            restores++;
        }
        return 1;
    }
    if(op==OP_CD) { if(a==':') directory_state[0]=0;return a==':'; }
    if(op==OP_OPEN) {
        const u16 *name=(const void *)a;
        unsigned file=name[1]=='A'?8:name[1]=='N'?9:3;
        if(missing && file==9) return 0;
        if(file==9) neighbor_opens++;
        offsets[file]=0;opened++;return file;
    }
    if(op==OP_CLOSE) { check(opened>0);opened--;return 1; }
    if(op==OP_SIZE) { *(u32 *)a=4*512;return 1; }
    if(op==OP_SEEK) { offsets[c]=a;return 1; }
    if(op!=OP_READ || b!=512) { failure++;return 0; }
    u8 *dst=(void *)a;
    unsigned offset=offsets[c];offsets[c]+=512;
    clear(dst,512);
    if(c==3) {
        if(!offset) {
            dst[0]='M';dst[1]='U';dst[2]='S';dst[3]='I';dst[4]='C';
            dst[8]=4;dst[10]=1;dst[510]=0x55;dst[511]=0xaa;
        } else if(offset==3*512) {
            for(unsigned i=0;i<256;i++) ((u16 *)dst)[i]=i<4?i:0xffff;
            if(library_bad) ((u16 *)dst)[1]=99;
        } else {
            for(unsigned i=0;i<2;i++) {
                unsigned id=(offset/512-1)*2+i+1;
                dst[i*256+0xf4]=id;dst[i*256+0xa9]=0x42;
                dst[i*256+0xa4]='M';
            }
        }
        return 1;
    }
    if(!offset) {
        dst[16]='O';dst[17]='K';dst[20]=c==8?124:32;dst[21]=c==8?104:32;
        return 1;
    }
    if(read_fail && c==8) return 0;
    unsigned width=c==8?124:32;
    for(unsigned i=0;i<256;i++) {
        unsigned pos=(offset-512)/2+i;
        ((u16 *)dst)[i]=pixel(pos%width,pos/width,c==8?0:track);
    }
    return 1;
}
u32 SECTION(".stockmock") stock_mock(void) { failure++;return 0; }
int SECTION(".automock") lookup_mock(fs_fn fs,u32 ctx,const struct vstate *snapshot,
                                     unsigned pos,struct flow_track_location *out)
{
    (void)fs;check(ctx==7 && snapshot->pos==shared.pos && snapshot->count==shared.count);
    if(lookup_fail) return 0;
    clear(out,sizeof(*out));out->cursor[0]=pos;out->directory[0]=0x42;return 1;
}
void SECTION(".metamock") metadata_mock(u8 *descriptor)
{
    check(REG(u32,0x15404)==7 && REG(u32,0x15980)==7);
    check(shared.flow_name[0]=='N' && descriptor[0x10]==0x42);
    track=descriptor[8];check(track>=1 && track<=9);
    REG(u32,0x15418)=4;
    REG(u32,0x15570)=track;
    REG(u32,0x15420)=0xbad;
    REG(u32,0x15820)=0xbad;
    REG(u32,0x15990)=0xbad;
}
int SECTION(".decodemock") decode_mock(u32 a,u32 b,u32 c,u32 d)
{
    const u16 *input=(const void *)c;u16 *output=(void *)d;
    check(a==1 && b==track && shared.flow_name[0]=='N');
    check(!input[0] && !input[1] && input[2]==32 && input[3]==32);
    output[2]=output[3]=32;decodes++;return decode_fail?-1:0;
}
static int region(struct flow_region *r,u32 mode)
{
    if (r->x==0 && r->y==FLOW_HEADER && r->height==4 &&
        r->width>=4 && r->width<=32 && !(r->width%4)) {
        check(!mode);
        line=FLOW_HEADER;
        return 1;
    }
    check(!mode && r->y==FLOW_HEADER && r->height==FLOW_FOOTER-FLOW_HEADER);
    line=r->y;return 1;
}
static int send(u32 n,u32 width)
{
    if (width>=4 && width<=32 && !(width%4)) {
        marker_count++;
        check(n==width*4 && line==FLOW_HEADER);
        for (unsigned i=0;i<n;i++)
            check(lcd[i]==((i%width)%4==3?FLOW_BACKGROUND:0xf81f));
        line+=4;return 1;
    }
    check(width==320 && n%320==0 && line+n/320<=FLOW_FOOTER);
    for(unsigned i=0;i<n && line+i/320<240;i++) frame[line+i/320][i%320]=lcd[i];
    line+=n/320;return 1;
}
static void reset(void)
{
    clear(lcd,sizeof(lcd));clear((void *)&shared,sizeof(shared));
    shared.magic=M3UPLAY_MAGIC;shared.pos=4;shared.count=9;shared.disk=0x10;
    shared.pl_off[0]=1;
    REG(u32,0xbf50)=(u32)lcd;REG(u16,0xbf54)=sizeof(lcd);
    REG(u32,0xbf84)=(u32)send;REG(u32,0xbf98)=(u32)region;
    REG(u32,0x15404)=7;REG(u32,0x15418)=4;REG(u32,0x15420)=0x1234;
    REG(u32,0x15980)=7;REG(u32,0x15990)=0x5678;
    REG(u32,0x15820)=0x9876;
    directory_state[0]=0xfeed;cursor_state[0]=0xbeef;
    snapshots=restores=0;
    decodes=neighbor_opens=0;
}
static void restored(void)
{
    check(!opened && !installs && !mounts);
    check(snapshots==restores);
    check(REG(u32,0x15404)==7 && REG(u32,0x15980)==7);
    check(REG(u32,0x15820)==0x9876);
    check(REG(u32,0x15420)==0x1234 && REG(u32,0x15990)==0x5678);
    check(shared.flow_name[0]=='A' && shared.flow_name[1]=='L' && shared.flow_name[2]=='B');
}
static void tile_layout(void)
{
    const struct flow_tile *tiles=(const void *)((u8 *)lcd+sizeof(lcd)-5*1024);
    for(unsigned i=0;i<5;i++) {
        check(tiles[i].magic==FLOW_MAGIC);
        for(unsigned y=0;y<2;y++) for(unsigned x=0;x<2;x++) {
            unsigned at=(y?35:5)*40+(x?35:5);
            unsigned pair=tiles[i].pixels[at/2];
            unsigned index=at&1?pair>>4:pair&15;
            check(tiles[i].palette[y*32+x*16+index]==pixel(x?24:4,y?24:4,1));
        }
    }
}
static void shuffle_history(void)
{
    u32 *state=(u32 *)(hardware+0x15f88);
    for(unsigned i=0;i<4;i++) state[i]=i+1;
    state[4]=8;state[5]=16;
    REG(u8,0x15fa0)=2;REG(u8,0x15fa1)=0xa5;
    u16 tracks[5];
    void (*queue)(u16 *,u32,u32,u32,u32)=(void *)FLOW_NATIVE_TEST_ENTRY;
    /* Independent Feistel fixture for keys 1,2,3,4: 8,7,6,5,1,2,3,4,... */
    queue(tracks,5,16,1,0);
    check(tracks[0]==7 && tracks[1]==6 && tracks[2]==5 && tracks[3]==1 && tracks[4]==2);
    check(state[4]==8); /* Browsing history must not advance the engine. */
    queue(tracks,9,16,1,0);
    check(!tracks[0] && !tracks[1] && tracks[2]==9 && !tracks[3] && !tracks[4]);
}
void _start(void)
{
#if !FLOW_LIVE_DECODE
    for (unsigned playlist=0;playlist<2;playlist++) {
        reset();
        shared.magic=playlist?M3UPLAY_MAGIC:0;
        run_refresh(8);
        restored();
        check(!decodes && !neighbor_opens && shared.native_visible);
        check(frame[90][160]==51*124+62);
    }
#else
    shuffle_history();
    reset();run_refresh(8);restored();check(decodes==3 && shared.native_visible);
    check(frame[90][160]==51*124+62);
    /* Visible neighbors preserve the source's top-left red quadrant. */
    check(frame[90][62]==((0xf800&0xe79c)>>1));
    run_refresh(8);restored();check(decodes==5);tile_layout();
    run_refresh(8);restored();check(decodes==5);
    shared.pos=5;run_refresh(8);restored();check(decodes==6);
    /* Same low bytes, different playlist identity must invalidate old tiles. */
    shared.pl_off[7]=1;run_refresh(8);restored();check(decodes==9);
    reset();preinstalled=1;run_refresh(8);restored();check(decodes==3);preinstalled=0;
    reset();offset_fail=1;run_refresh(8);check(decodes==0 && !snapshots && !restores);offset_fail=0;
    reset();lookup_fail=1;run_refresh(8);restored();check(decodes==0);lookup_fail=0;
    reset();missing=1;run_refresh(8);restored();check(decodes==1);missing=0;run_refresh(8);restored();check(decodes==4 && shared.native_visible);
    reset();decode_fail=1;run_refresh(8);restored();check(decodes==1 && !neighbor_opens);decode_fail=0;run_refresh(8);restored();check(decodes==4 && shared.native_visible);
    reset();read_fail=1;run_refresh(8);restored();read_fail=0;
    reset();shared.magic=0;REG(u16,0x15f24)=2;REG(u16,0x15f26)=4;
    REG(u16,0x15f58)=3;REG(u16,0x15f70)=0;REG(u8,0x15f38)=0x10;
    run_refresh(8);restored();check(decodes==3);check(shared.native_visible);
    reset();shared.magic=0;library_bad=1;
    run_refresh(8);restored();check(decodes==0);library_bad=0;
#ifdef FLOW_DIAG_TEST
    check(marker_count>=3);
#else
    check(marker_count==0);
#endif
#endif
    register u32 v0 asm("$2")=4001;
    register u32 a0 asm("$4")=failure?1:0;
    asm volatile("syscall"::"r"(v0),"r"(a0));__builtin_unreachable();
}

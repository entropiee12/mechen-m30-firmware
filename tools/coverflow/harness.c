/* QEMU user-mode fixture for the packed music.ap code. Only LCD, VFS,
 * system services and their globals are replaced by the test loader. */
#include "flow.h"
extern u8 pack_start[],pack_end[];
extern void run_refresh(u32 artwork_dirty);
#define SECTION(s) __attribute__((section(s),used))
volatile struct vstate shared SECTION(".shared");
u8 hardware[0x20000] SECTION(".hardware");
#define REG(type,offset) (*(type *)(hardware+(offset)))
static u16 lcd[8192 / sizeof(u16)], frame[240][320];
static unsigned line, reads, stock, opened, mounts, installs, selected, failure;
static unsigned detail_reads, opens, regions, diagnostic, cursor=7;
static int capture, metadata_status, missing;
static int write_out(const void *p,u32 size)
{
    register u32 v0 asm("$2")=4004;
    register u32 a0 asm("$4")=1;
    register const void *a1 asm("$5")=p;
    register u32 a2 asm("$6")=size;
    asm volatile("syscall":"+r"(v0):"r"(a0),"r"(a1),"r"(a2):"memory","$7");
    return v0;
}
static void check(int yes) { if (!yes) failure++; }
u32 SECTION(".sysmock") sys_mock(u32 a,u32 b,u32 c,u32 op)
{
    (void)a;(void)b;(void)c;
    if (op==10) installs++;
    else if (op==11) installs--;
    else if (op==41) {mounts++;return 2;}
    else if (op==42) mounts--;
    else failure++;
    return 0;
}
long SECTION(".fsmock") fs_mock(u32 ctx,u32 a,u32 b,u32 c,u32 op)
{
    if (ctx==7) {
        static unsigned offset;
        if (op==OP_CD) return a==':';
        if (op==OP_OPEN) {check(((u16 *)a)[1]=='A');offset=0;return 8;}
        if (op==OP_CLOSE) return a==8;
        if (op==OP_SEEK) {offset=a;return 1;}
        if (op==OP_READ && !offset && b==30) {
            u8 *header=(u8 *)a;
            header[16]='O';header[17]='K';header[20]=124;header[21]=104;return 1;
        }
        if (op==OP_READ && offset>=512 && offset%512==0 && b==512) {
            for (unsigned i=0;i<256;i++) ((u16 *)a)[i]=(offset/512-1)*256+i;
            return 1;
        }
        failure++;return 0;
    }
    check(ctx==0 && !mounts && !installs);
    if (op==OP_OFFSET) {
        u32 *directory=(u32 *)a,*selection=(u32 *)b;
        if (c) {
            check(cursor==0 && *directory==7 && *selection==19);
            cursor=*directory;
        } else {
            check(cursor==7);
            *directory=cursor;*selection=19;
        }
        return 1;
    }
    if (op==OP_CD) {check(cursor==7);cursor=0;return a==':';}
    check(cursor==(op==OP_OPEN?0:7));
    if (op==OP_OPEN) {
        u16 *name=(u16 *)a;
        u32 pack=0;
        for (int i=2;i<9;i++)
            pack=(pack<<4)|(name[i]<='9'?name[i]-'0':name[i]-'A'+10);
        check(name[0]==0xfeff && name[1]=='F' && name[9]=='.' && name[10]=='C' &&
              name[11]=='F' && name[12]=='7' && !name[13] && pack==shared.flow_pack);
        opens++;
        if (missing) return 0;
        opened++;return 3;
    }
    if (op==OP_CLOSE) {check(a==3);opened--;return 1;}
    if (op==OP_SIZE) {check(b==3);*(u32 *)a=pack_end-pack_start;return metadata_status;}
    if (op==OP_SEEK) {check(c==3 && !(a%512));selected=a;return metadata_status;}
    if (op==OP_READ) {
        check(c==3 && opened==1 && b==512);
        if (b!=512) return 0;
        if (selected+512>(u32)(pack_end-pack_start)) return 0;
        u32 *src=(u32 *)(pack_start+selected),*dst=(u32 *)a;
        for (int i=0;i<128;i++) dst[i]=src[i];
        if (selected>=shared.count*1024u) detail_reads++;
        selected+=512;
        if (selected<=shared.count*1024u && !(selected%1024)) reads++;
        return 1;
    }
    failure++;return 0;
}
u32 SECTION(".stockmock") stock_mock(void) { stock++;return 0; }
static int region(struct flow_region *r,u32 mode)
{
    if (!mode && r->x==0 && r->y==178 && r->width==36 && r->height==5) return 1;
    regions++;
    check(!mode && r->x==0 && r->y==FLOW_HEADER && r->width==320 && r->height==FLOW_FOOTER-FLOW_HEADER);
    line=FLOW_HEADER;return 1;
}
static int send(u32 n,u32 width)
{
    if (n==180 && width==36) {
        diagnostic++;
        for (unsigned i=0;i<n;i++)
            check(lcd[i]==((i%4!=3 && i%36<16)?0xffff:0));
        return 1;
    }
    check(n && n%320==0 && width==320 && line+n/320<=FLOW_FOOTER);
    if (line+n/320>FLOW_FOOTER) return 0;
    if (capture) for (unsigned i=0;i<n;i++) frame[line+i/320][i%320]=lcd[i];
    line+=n/320;return 1;
}
static u16 tile_pixel(const struct flow_tile *tile,unsigned x,unsigned y)
{
    if ((tile->magic==FLOW_MAGIC_48 || tile->magic==FLOW_MAGIC_DETAIL)) {
        unsigned bit=(y*48+x)*3;
        const u8 *p=(const u8 *)tile+140;
        unsigned index=0;
        for (unsigned i=0;i<3;i++)
            index|=((p[(bit+i)/8]>>((bit+i)%8))&1)<<i;
        return tile->palette[(y/12*2+x/24)*8+index];
    }
    unsigned pixel=y*40+x;
    unsigned palette=(x>=20?16:0)+(y>=20?32:0);
    unsigned pair=tile->pixels[pixel/2];
    return tile->palette[palette+((pixel&1)?pair>>4:pair&15)];
}

void _start(void)
{
    const struct flow_tile *first=(const void *)pack_start;
    shared.count=first->count; shared.flow_pack=first->pack; shared.pos=4;
    shared.disk=0x10;
    REG(u32,0x15f34)=0;
    REG(u32,0xbf50)=(u32)lcd;
    REG(u16,0xbf54)=sizeof(lcd);
    REG(u32,0xbf84)=(u32)send;
    REG(u32,0xbf98)=(u32)region;
    REG(u32,0x15418)=4;

    for (unsigned x=0;x<FLOW_WIDTH;x++) frame[0][x]=frame[FLOW_FOOTER][x]=0x4321;
    shared.flow_disabled=1;
    run_refresh(8);check(stock==1 && !reads);
    shared.flow_disabled=0;
    shared.magic=M3UPLAY_WAITING;
    run_refresh(0);
    shared.magic=0;shared.handoff=M3U_HANDOFF_MISMATCH;
    run_refresh(0);
    shared.handoff=M3U_HANDOFF_CLICK;
    run_refresh(0);
    shared.handoff=0;REG(u32,0x15b20)=M3UVD_MAGIC;
    REG(u32,0x15bb0)=first->pack;
    run_refresh(0);check(regions==0 && !reads);

    shared.magic=M3UPLAY_MAGIC;shared.flow_pack=0;
    run_refresh(0);check(stock==1 && !reads && regions==0);
    shared.flow_pack=first->pack;capture=1;
    run_refresh(0);check(stock==1 && reads==5 && line==FLOW_FOOTER);
    /* Check the actual visible slots against independent textured samples. */
    static const int xs[]={62,160,260}, left[]={26,98,216}, w[]={78,124,78};
    for (int i=0;i<3;i++) {
        const struct flow_tile *tile=first+i+2; /* centre track 4 */
        int dx=xs[i]-left[i],t=i==0?w[i]-1-dx:dx;
        int inset=i==1?0:11+t*22/w[i];
        int size=(tile->magic==FLOW_MAGIC_48 || tile->magic==FLOW_MAGIC_DETAIL)?48:40;
        int ty=(90-FLOW_TOP-inset)*size/(FLOW_COVER_HEIGHT-2*inset),tx=dx*size/w[i];
        u16 color=tile_pixel(tile,tx,ty);
        if (i==1 && tile->magic==FLOW_MAGIC_DETAIL) {
            unsigned y=90-FLOW_TOP;
            const u8 *center=pack_start+first->count*1024u+3*FLOW_CENTER_STRIDE;
            color=((const u16 *)(center+y/2*512))[(y%2)*124+dx];
            check(detail_reads>0);
        }
        if (i!=1) {
            u16 next=tile_pixel(tile,tx+1<size ? tx+1:tx,ty);
            color=((color&0xe79c)>>2)+((next&0xe79c)>>2);
        }
        check(frame[90][xs[i]]==color);
    }
    capture=0; metadata_status=1; shared.pos=5;
    run_refresh(8);check(reads==6 && line==FLOW_FOOTER);
    shared.pos=4;run_refresh(8);check(reads==7);
    shared.pos=1;run_refresh(8);check(reads==8 && line==FLOW_FOOTER);
    unsigned cached_opens=opens;
    run_refresh(8);check(opens==cached_opens+(first->magic==FLOW_MAGIC_DETAIL) && reads==8);
    regions--;
    REG(u16,0xbf54)=1024;
    run_refresh(8);check(stock==2 && reads==8 && regions==4);
    REG(u32,0x15418)=0;
    run_refresh(8);check(stock==2 && !opened && !installs && !mounts);
    for (unsigned i=512;i<sizeof(lcd)/2;i++) lcd[i]=0;
    missing=1;REG(u16,0xbf54)=sizeof(lcd);
    run_refresh(8);check(regions==4 && diagnostic==0 && !opened && !installs && !mounts);
    check(frame[0][160]==0x4321 && frame[FLOW_FOOTER][160]==0x4321 && frame[FLOW_HEADER][160]==FLOW_BACKGROUND);
    /* The fixture emulates the artwork plane. Fill the reserved stock UI
     * areas with the shared matte in exported emulator frames. */
    for (unsigned y=0;y<FLOW_HEIGHT;y++)
        if (y<FLOW_HEADER || y>=FLOW_FOOTER)
            for (unsigned x=0;x<FLOW_WIDTH;x++) frame[y][x]=FLOW_BACKGROUND;
    if (!failure) write_out(frame,sizeof(frame));
    shared.flow_pack=0;
    capture=1;
    REG(u32,0x15404)=7;
    REG(u32,0x15418)=4;
    run_refresh(8);check(frame[90][160]==51*124+62 && frame[90][161]==51*124+63 && shared.native_visible);
    shared.flow_disabled=1;
    unsigned before=regions;
    run_refresh(8);check(regions==before);
    register u32 v0 asm("$2")=4001;
    register u32 a0 asm("$4")=failure?1:0;
    asm volatile("syscall"::"r"(v0),"r"(a0));
    __builtin_unreachable();
}

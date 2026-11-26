#include "flow.h"
#define ENGINE_CTX (*(volatile u32 *)0x9fc15f34u)
#define FS ((fs_fn)0xbfc16bb5u)
#define STOCK_BLIT ((int (*)(void))0x4805447du)
#define PREPARE ((flow_prepare)FLOW_CACHE_ENTRY)
#define DRAW ((flow_draw)FLOW_DRAW_ENTRY)

struct reader { u32 ctx,file,pack,count,size; int stage; };
/* Open the pack only when the cache requests its first missing tile. */
static int open_pack(struct reader *r)
{
    u32 ctx=r->ctx;
    r->stage=1;
    u32 directory[19], selection[2];
    if (!FS(ctx,(u32)directory,(u32)selection,0,OP_OFFSET)) return 0;
    u32 pack=r->pack;
    u16 name[14];
    name[0]=0xfeff; name[1]='F'; name[9]='.'; name[10]='C';
    name[11]='F'; name[12]='7'; name[13]=0;
    for (unsigned i=0;i<7;i++) {
        unsigned digit=(pack>>(4*i))&15;
        name[8-i]=digit<10?'0'+digit:'A'+digit-10;
    }
    r->stage=FS(ctx,':',0,0,OP_CD)?4:3;
    r->file=FS(ctx,(u32)name,0,0,OP_OPEN);
    if (!FS(ctx,(u32)directory,(u32)selection,1,OP_OFFSET)) {
        r->stage=1;
        return 0;
    }
    if (!r->file) return 0;
    u32 size=0;
    r->stage=5;
    if (FS(ctx,(u32)&size,r->file,0,OP_SIZE)<0 ||
        (size!=r->count*sizeof(struct flow_tile) &&
         size!=r->count*(sizeof(struct flow_tile)+FLOW_CENTER_STRIDE))) return 0;
    r->size=size;
    r->stage=6;
    return 1;
}

static int load_sector(u32 track,struct flow_tile *tile,void *opaque)
{
    struct reader *r=opaque;
    if (!r->stage && !open_pack(r)) return 0;
    if (r->stage<6) return 0;
    r->stage=6;
    if (FS(r->ctx,(track-1)*sizeof(*tile),0,r->file,OP_SEEK)<0) return 0;
    r->stage=7;
    if (FS(r->ctx,(u32)tile,512,r->file,OP_READ)<=0) return 0;
    r->stage=8;
    if (FS(r->ctx,(u32)tile+512,512,r->file,OP_READ)<=0) return 0;
    r->stage=9;
    return 1;
}

static int paint_queue(u32 pack,u32 pos,u32 count)
{
    u16 *buffer=*(u16 *volatile *)0x9fc0bf50u;
    u32 bytes=*(volatile u16 *)0x9fc0bf54u;
    /* First sector holds the LCD scanline. Only the last five tile slots
     * are used for covers; the separate font cache is outside this buffer. */
    if (!pos || pos>count) return 0;
    if (!buffer || ((u32)buffer&3) || bytes<1024+FLOW_SLOTS*sizeof(struct flow_tile)) return 0;
    u16 tracks[FLOW_SLOTS];
    u32 shuffle=*(volatile u8 *)0x9fc15f23u,loop=*(volatile u8 *)0x9fc15f39u;
    u32 key=VS->shuf_key,cursor=VS->shuf_pos;
    ((void (*)(u16 *,u32,u32,u32,u32,u32,u32))FLOW_QUEUE_ENTRY)(
        tracks,pos,count,shuffle,loop,key,cursor);
    u32 ctx=ENGINE_CTX;
    if (ctx==~0u) return -2;
    struct reader r={.ctx=ctx,.pack=pack,.count=count};
    struct flow_window window={0};
    u32 end=bytes & ~511u;
    struct flow_tile *slots=(void *)((u8 *)buffer+end-FLOW_SLOTS*sizeof(*slots));
    int ok=PREPARE(slots,&window,tracks,pack,count,load_sector,&r);
    if (!ok) {
        if (r.file) FS(ctx,r.file,0,0,OP_CLOSE);
        return 0;
    }
    u32 available=(u8 *)slots-(u8 *)buffer;
    struct flow_center center={ctx,0,count*1024+(pos-1)*FLOW_CENTER_STRIDE,~0u,0};
    /* The pack ID covers the HD pixels, and the size identifies the layout. */
    if (available>=FLOW_WIDTH*2+512 && window.visible[1]->magic==FLOW_MAGIC_DETAIL &&
        (r.stage || open_pack(&r)) &&
        r.size==count*(1024+FLOW_CENTER_STRIDE)) {
        available-=512;
        center.file=r.file;
        center.pixels=(void *)((u8 *)buffer+available);
        window.center=&center;
    }
    int drawn=0;
    if (!(ENGINE_CTX!=ctx || VS->magic!=M3UPLAY_MAGIC || VS->flow_pack!=pack || VS->pos!=pos ||
        *(volatile u8 *)0x9fc15f23u!=shuffle || *(volatile u8 *)0x9fc15f39u!=loop ||
        VS->count!=count || VS->shuf_key!=key || VS->shuf_pos!=cursor ||
        buffer!=*(u16 *volatile *)0x9fc0bf50u ||
        *(volatile u16 *)0x9fc0bf54u!=bytes))
        drawn=DRAW(&window,buffer,available);
    if (r.file) FS(ctx,r.file,0,0,OP_CLOSE);
    return drawn;
}

static void refresh(int stock_art_path)
{
    int status=0;
    if (!VS->flow_disabled && VS->magic==M3UPLAY_MAGIC && VS->flow_pack)
        status=paint_queue(VS->flow_pack,VS->pos,VS->count);
    else if (!VS->flow_disabled && stock_art_path) {
        VS->native_visible=((int (*)(void))FLOW_NATIVE_ENTRY)();
        if (VS->native_visible) return;
    }
    if (status>0) return;
    if (stock_art_path && (*(volatile u32 *)0x9fc15418u&4)) STOCK_BLIT();

}

void coverflow_refresh(void) { refresh(1); }
void coverflow_bypass(void) { refresh(0); }

/* These stock music.ap callers submit TextBox descriptors to UI operation 3.
 * Preserve other IDs and ordinary playback at the same call sites. */
u32 coverflow_text(u16 *widget,u32 content,u32 mode,u32 op)
{
    if (!VS->flow_disabled &&
        *widget>=0x2001 && *widget<=0x2004) return 0;
    return ((u32 (*)(u16 *,u32,u32,u32))0xbfc16405u)(widget,content,mode,op);
}

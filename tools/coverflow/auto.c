#include "flow.h"
#include "library_queue.h"
#include "m3u_queue.h"

#define FS ((fs_fn)0xbfc16bb5u)
#define SYS ((u32 (*)(u32,u32,u32,u32))0xbfc16bb1u)
#define PREPARE ((flow_prepare)FLOW_CACHE_ENTRY)
#define DRAW ((flow_draw)FLOW_DRAW_ENTRY)
#define QUEUE ((void (*)(u16 *,u32,u32,u32,u32,u32,u32))FLOW_QUEUE_ENTRY)
#define NATIVE_QUEUE ((void (*)(u16 *,u32,u32,u32,u32))FLOW_NATIVE_QUEUE_ENTRY)
#define LIBRARY ((unsigned (*)(flow_library_read,void *,u32,u16,u16,u16,const u16 *,struct flow_track_location *))FLOW_LIBRARY_ENTRY)
#define M3U_TRACK ((int (*)(fs_fn,u32,const struct vstate *,unsigned,struct flow_track_location *))FLOW_M3U_ENTRY)
#define TILE_ENCODE ((int (*)(flow_tile_read,void *,struct flow_tile *,u16 *,u16,u16,u32))FLOW_TILE_ENTRY)
#define METADATA ((void (*)(u8 *))FLOW_METADATA_ENTRY)
#define DECODE ((int (*)(u32,u32,u32,u32))FLOW_DECODE_ENTRY)
#define NATIVE_PREPARE ((int (*)(struct flow_window *,struct native_center *,u16 *))FLOW_NATIVE_PREPARE_ENTRY)
#define NATIVE_FINISH ((void (*)(struct native_center *))FLOW_NATIVE_FINISH_ENTRY)

struct auto_reader {
    u32 ctx,file,sectors,index_sector,first,count,source_id,budget,kind,valid,installed;
    u16 *work;
    u16 positions[FLOW_SLOTS];
    struct flow_track_location locations[FLOW_SLOTS];
    struct vstate snapshot;
};

static int open_card(u32 *ctx,u32 *installed)
{
    /* The player may already have the card driver installed. A second
     * install fails in that case, but a separate filesystem mount still
     * gives us an independent cursor. Never uninstall another owner's driver. */
    *installed=SYS(1,0,(u32)"card.drv",10)==0;
    int result=(signed char)SYS(1,16,0,41);
    if (result<0) {
        if (*installed) SYS(1,0,0,11);
        return 0;
    }
    *ctx=(u32)result;
    return 1;
}

static void close_card(u32 ctx,u32 installed)
{
    SYS(ctx,0,0,42);
    if (installed) SYS(1,0,0,11);
}

static int read_library(void *opaque,u32 sector,u8 data[512])
{
    struct auto_reader *r=opaque;
    if (FS(r->ctx,sector*512,0,r->file,OP_SEEK)<0) return 0;
    return FS(r->ctx,(u32)data,512,r->file,OP_READ)>0;
}

static int read_neighbor(void *opaque,u32 offset,u8 data[512])
{
    struct auto_reader *r=opaque;
    if (FS(r->ctx,offset,0,r->file,OP_SEEK)<0) return 0;
    return FS(r->ctx,(u32)data,512,r->file,OP_READ)>0;
}

static int open_named(u32 ctx,const char *name)
{
    u16 path[16],*p=path;
    *p++=0xfeff;
    while (*name) *p++=(u8)*name++;
    *p=0;
    if (FS(ctx,':',0,0,OP_CD)<=0) return 0;
    return FS(ctx,(u32)path,0,0,OP_OPEN);
}

static void copy(void *destination,const void *source,unsigned bytes)
{
    u8 *d=destination;
    const u8 *s=source;
    while (bytes--) *d++=*s++;
}

static int load_neighbor(u32 track,struct flow_tile *tile,void *opaque)
{
    struct auto_reader *r=opaque;
    if (!r->budget) return 0;
    r->budget--;
    struct flow_track_location resolved;
    struct flow_track_location *location=0;
    if (r->kind==1) {
        if (!M3U_TRACK(FS,r->ctx,&r->snapshot,track,&resolved)) return 0;
        location=&resolved;
    } else {
        for (unsigned i=0;i<FLOW_SLOTS;i++)
            if ((r->valid&(1u<<i)) && r->positions[i]==track) { location=r->locations+i; break; }
        if (!location) return 0;
    }

    u8 descriptor[0xa4],scene[0x560],selector[0x120];
    for (unsigned i=0;i<sizeof(descriptor);i++) descriptor[i]=0;
    descriptor[0]=r->kind==1?r->snapshot.disk:*(volatile u8 *)0x9fc15f38u;
    descriptor[1]=0;
    copy(descriptor+4,location->extension,4);
    copy(descriptor+8,location->cursor,8);
    copy(descriptor+0x10,location->directory,75);
    *(u16 *)(descriptor+0x5c)=(u16)r->count;
    *(u16 *)(descriptor+0x5e)=(u16)track;
    copy(scene,(void *)0x9fc15400u,sizeof(scene));
    copy(selector,(void *)0x9fc15960u,sizeof(selector));
    *(volatile u32 *)0x9fc15980u=r->ctx;
    *(volatile u32 *)0x9fc15404u=r->ctx;
    flow_name_neighbor();
    METADATA(descriptor);
    int ok=0;
    if (*(volatile u32 *)0x9fc15418u&4) {
        /* The stock API takes input/output rectangles, not callbacks. Its
         * old in-bank scratch overlaps our patched repaint trampoline. */
        u16 input[4],output[4];
        input[0]=input[1]=output[0]=output[1]=output[2]=output[3]=0;
        input[2]=input[3]=32;
        int decoded=DECODE(1,*(volatile u32 *)0x9fc15570u,(u32)input,(u32)output);
        /* A failed decode must not reuse the previous neighbor's cache file. */
        u32 file=decoded==0 && output[2] && output[3]?open_named(r->ctx,"NEIGH.PIC"):0;
        if (file) {
            u32 saved=r->file;
            r->file=file;
            ok=TILE_ENCODE(read_neighbor,r,tile,r->work,track,r->count,r->source_id);
            r->file=saved;
            FS(r->ctx,file,0,0,OP_CLOSE);
        }
    }
    flow_name_album();
    copy((void *)0x9fc15400u,scene,sizeof(scene));
    copy((void *)0x9fc15960u,selector,sizeof(selector));
    return ok;
}

static void empty_tile(struct flow_tile *tile,u32 track,u32 count,u32 source_id)
{
    for (unsigned i=0;i<sizeof(*tile);i++) ((u8 *)tile)[i]=0;
    tile->magic=FLOW_MAGIC;
    tile->pack=source_id;
    tile->track=(u16)track;
    tile->count=(u16)count;
    for (unsigned i=0;i<64;i++) tile->palette[i]=0x2947;
    const u8 *bytes=(const u8 *)tile;
    u32 hash=2166136261u;
    for (unsigned i=0;i<sizeof(*tile)-sizeof(tile->checksum);i++) hash=(hash^bytes[i])*16777619u;
    tile->checksum=hash;
}

static int load_or_empty(u32 track,struct flow_tile *tile,void *opaque)
{
    struct auto_reader *r=opaque;
    if (!r->budget) return 0;
    if (load_neighbor(track,tile,opaque)) return 1;
    /* A failed/missing decode is cached as a neutral tile, not retried forever. */
    empty_tile(tile,track,r->count,r->source_id);
    return 1;
}

int flow_auto_paint(u32 kind,u32 pos,u32 count)
{
    u16 *buffer=*(u16 *volatile *)0x9fc0bf50u;
    u32 bytes=*(volatile u16 *)0x9fc0bf54u;
    if (!pos || pos>count || !buffer || ((u32)buffer&3) ||
        bytes<3072+FLOW_SLOTS*sizeof(struct flow_tile)) return 0;
    u32 index_sector=0,first=0;
    if (kind==2) {
        index_sector=*(volatile u16 *)0x9fc15f58u; /* 0x9fc15f4c+0x0c */
        first=*(volatile u16 *)0x9fc15f70u;
        if (!index_sector || first==0xffff) return 0;
    }
    u16 tracks[FLOW_SLOTS];
    if (kind==1) {
        u32 shuffle=*(volatile u8 *)0x9fc15f23u,loop=*(volatile u8 *)0x9fc15f39u;
        QUEUE(tracks,pos,count,shuffle,loop,VS->shuf_key,VS->shuf_pos);
    } else {
        NATIVE_QUEUE(tracks,pos,count,*(volatile u8 *)0x9fc15f23u,*(volatile u8 *)0x9fc15f39u);
    }

    u32 ctx,installed;
    if (!open_card(&ctx,&installed)) return 0;
    struct auto_reader r;
    r.ctx=ctx;
    r.index_sector=index_sector;
    r.first=first;
    r.count=count;
    r.source_id=2166136261u;
    const volatile u8 *identity=kind==1?VS->pl_off:(volatile u8 *)0x9fc15f38u;
    unsigned identity_size=kind==1?sizeof(VS->pl_off)+sizeof(VS->pl_dir):1;
    for (unsigned i=0;i<identity_size;i++) r.source_id=(r.source_id^identity[i])*16777619u;
    r.source_id^=(index_sector<<16)|first;
    r.source_id^=kind<<28;
    if (kind==1) r.source_id^=VS->disk;
    r.budget=VS->native_visible?2:3;
    r.kind=kind;
    r.work=buffer+384;
    r.file=0;
    if (kind==1) copy(&r.snapshot,(const void *)VS,sizeof(r.snapshot));
    else {
        r.file=open_named(ctx,"MUSIC.LIB");
        if (!r.file) { close_card(ctx,installed); return 0; }
        if (FS(ctx,(u32)&r.sectors,r.file,0,OP_SIZE)<0) {
            FS(ctx,r.file,0,0,OP_CLOSE); close_card(ctx,installed); return 0;
        }
        r.sectors=(r.sectors+511)/512;
        for (unsigned i=0;i<FLOW_SLOTS;i++) r.positions[i]=tracks[i];
        r.valid=LIBRARY(read_library,&r,r.sectors,index_sector,first,0xffff,tracks,r.locations);
    }

    struct flow_window window={0};
    struct native_center image;
    struct flow_tile *slots=(void *)((u8 *)buffer+(bytes&~511u)-FLOW_SLOTS*sizeof(*slots));
    int ok=PREPARE(slots,&window,tracks,r.source_id,count,load_or_empty,&r);
    /* Decoding uses the scratch area first; drawing then owns the beginning. */
    u32 available=(u8 *)slots-(u8 *)buffer;
    available=(available-512-FLOW_CENTER_WIDTH*2)&~3u;
    u16 *scratch=(u16 *)((u8 *)buffer+available);
    int have_center=NATIVE_PREPARE(&window,&image,scratch);
    int drawn=0;
    if (ok && buffer==*(u16 *volatile *)0x9fc0bf50u &&
        bytes==*(volatile u16 *)0x9fc0bf54u &&
        (kind==1 ? VS->magic==M3UPLAY_MAGIC && VS->pos==pos && VS->count==count :
         *(volatile u16 *)0x9fc15f24u==pos && *(volatile u16 *)0x9fc15f26u==count)) {
        drawn=DRAW(&window,buffer,available);
    }
    if (have_center) NATIVE_FINISH(&image);
    if (kind==2 && r.file) FS(ctx,r.file,0,0,OP_CLOSE);
    close_card(ctx,installed);
    return drawn;
}

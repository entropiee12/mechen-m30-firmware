#ifndef M30_FLOW_H
#define M30_FLOW_H
#include "../m3u/m3uplay.h"

#define FLOW_MAGIC 0x3146434du
#define FLOW_MAGIC_48 0x3246434du
#define FLOW_MAGIC_DETAIL 0x3346434du
enum { FLOW_SLOTS=5, FLOW_VISIBLE=3, FLOW_PIXELS=40, FLOW_WIDTH=320, FLOW_HEIGHT=240, FLOW_HEADER=28, FLOW_FOOTER=179, FLOW_TOP=39, FLOW_COVER_HEIGHT=104, FLOW_BACKGROUND=0x18c3 };
struct flow_tile {
    u32 magic, pack;
    u16 track, count, palette[64];
    u8 pixels[800], padding[80];
    u32 checksum;
};
_Static_assert(sizeof(struct flow_tile)==1024,"two aligned SD sectors per cover");

enum { FLOW_CENTER_WIDTH=124, FLOW_CENTER_STRIDE=(FLOW_COVER_HEIGHT/2)*512 };
struct flow_center { u32 ctx,file,offset,sector; u16 *pixels; };
struct flow_window {
    struct flow_tile *visible[FLOW_VISIBLE];
    struct flow_center *center;
    const u16 *(*native_row)(struct flow_center *,unsigned);
};
struct flow_region { u16 x,y,width,height; };

/* Stock artwork readers and writers use this shared filename. The prepared
 * path never changes it; ordinary neighbor decodes briefly use NEIGH.PIC so
 * the live track's ALBUM.PIC stays untouched, even across bank reloads. */
static inline void flow_name_album(void)
{
    volatile u32 *p=(volatile u32 *)VS->flow_name;
    p[0]=0x55424c41u; p[1]=0x49502e4du; *(volatile u16 *)(p+2)='C';
}
static inline void flow_name_neighbor(void)
{
    volatile u32 *p=(volatile u32 *)VS->flow_name;
    p[0]=0x4749454eu; p[1]=0x49502e48u; *(volatile u16 *)(p+2)='C';
}

struct native_center {
    struct flow_center input;
    u16 *row;
    unsigned width,height,previous,failed;
};
int native_prepare(struct flow_window *window,struct native_center *image,u16 *scratch);
void native_finish(struct native_center *image);

typedef int (*flow_tile_read)(void *ctx,u32 offset,u8 data[512]);
int flow_tile_encode(flow_tile_read read,void *ctx,struct flow_tile *tile,
                     u16 work[32*32],u16 track,u16 count,u32 pack);

typedef int (*flow_load)(u32 track,struct flow_tile *tile,void *context);
typedef int (*flow_prepare)(struct flow_tile *slots,struct flow_window *window,
                            const u16 tracks[FLOW_SLOTS],u32 pack,u32 count,flow_load load,void *ctx);
typedef int (*flow_draw)(const struct flow_window *window,u16 *pixels,u32 available_bytes);
#endif

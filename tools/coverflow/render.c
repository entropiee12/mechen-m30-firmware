#include "flow.h"
#ifndef FLOW_REGION
#define FLOW_REGION (*(int (*volatile *)(struct flow_region *,u32))0x9fc0bf98u)
#define FLOW_SEND (*(int (*volatile *)(u32,u32))0x9fc0bf84u)
#endif
static u16 sample(const struct flow_tile *tile,unsigned x,unsigned y)
{
    if (tile->magic!=FLOW_MAGIC) {
        unsigned bit=(y*48+x)*3;
        const u8 *packed=(const u8 *)tile+140+(bit>>3);
        unsigned index=((packed[0]|(packed[1]<<8))>>(bit&7))&7;
        return tile->palette[((y/12)*2+(x/24))*8+index];
    }
    unsigned pixel=y*FLOW_PIXELS+x;
    unsigned palette=(x>=FLOW_PIXELS/2?16:0)+(y>=FLOW_PIXELS/2?32:0);
    u8 pair=tile->pixels[pixel>>1];
    return tile->palette[palette+((pixel&1)?pair>>4:pair&15)];
}

static const u16 *center_row(struct flow_center *center,unsigned y)
{
    if (!center || !center->file) return 0;
    unsigned sector=center->offset+(y/2)*512;
    if (sector!=center->sector) {
        fs_fn fs=(fs_fn)0xbfc16bb5u;
        if (fs(center->ctx,sector,0,center->file,OP_SEEK)<0 ||
            fs(center->ctx,(u32)center->pixels,512,center->file,OP_READ)<=0) {
            center->file=0;
            return 0;
        }
        center->sector=sector;
    }
    return center->pixels+(y%2)*FLOW_CENTER_WIDTH;
}

/* Draw back to front. Outer panel edges taper toward the horizon and
 * reflections use the corresponding cover row below the bottom edge. */
int flow_render(const struct flow_window *window,u16 *pixels,u32 available_bytes)
{
    static const u8 order[]={0,2,1};
    static const u8 left[]={26,98,216};
    static const u8 widths[]={78,124,78};
    struct flow_region area={0,FLOW_HEADER,FLOW_WIDTH,FLOW_FOOTER-FLOW_HEADER};
    if (!FLOW_REGION || !FLOW_SEND) return 0;
    unsigned rows=available_bytes/(FLOW_WIDTH*sizeof(*pixels));
    if (!rows) return 0;
    FLOW_REGION(&area,0);
    /* Side covers are mirror projections. Reuse the same column map for
     * every scanline and for the reflection. */
    u8 side_inset[78],side_x[2][78],center_x[2][124];
    for (unsigned x=0;x<78;x++) {
        side_inset[x]=11+x*22/78;
        side_x[0][x]=x*40/78;
        side_x[1][x]=x*48/78;
    }
    for (unsigned x=0;x<124;x++) {
        center_x[0][x]=x*40/124;
        center_x[1][x]=x*48/124;
    }
    unsigned pending=0;
    for (int y=FLOW_HEADER;y<FLOW_FOOTER;y++) {
        u16 *row=pixels+pending*FLOW_WIDTH;
        for (int x=0;x<FLOW_WIDTH;x++) row[x]=FLOW_BACKGROUND;
        for (unsigned depth=0;depth<FLOW_VISIBLE;depth++) {
            int slot=order[depth],width=widths[slot];
            const struct flow_tile *tile=window->visible[slot];
            if (!tile && !(slot==1 && window->center)) continue;
            unsigned dense=tile && tile->magic!=FLOW_MAGIC;
            unsigned size=dense?48:40;
            int center_y=y-FLOW_TOP;
            if (center_y>=FLOW_COVER_HEIGHT)
                center_y=FLOW_COVER_HEIGHT-1-2*(center_y-FLOW_COVER_HEIGHT);
            const u16 *detail=0;
            if (slot==1 && center_y>=0 && window->center)
                detail=window->native_row ? window->native_row(window->center,center_y) :
                    center_row(window->center,center_y);
            for (int dx=0;dx<width;dx++) {
                int t=slot==0?width-1-dx:dx;
                int inset=slot==1?0:side_inset[t];
                int top=FLOW_TOP+inset,height=FLOW_COVER_HEIGHT-2*inset;
                int dy=y-top;
                if (dy<0 || dy>=height+height/3) continue;
                int reflection=dy>=height;
                if (reflection) dy=height-1-(dy-height)*2;
                unsigned x=slot==1?center_x[dense][dx]:side_x[dense][dx];
                unsigned ty=dy*size/height;
                u16 color=detail?detail[dx]:tile?sample(tile,x,ty):0x2947;
                if (reflection) color=(color&0xe79c)>>2;
                else if (slot!=1) {
                    unsigned neighbor=x+(x!=size-1);
                    u16 next=sample(tile,neighbor,ty);
                    color=((color&0xe79c)>>2)+((next&0xe79c)>>2);
                }
                row[left[slot]+dx]=color;
            }
        }
        if (++pending==rows || y==FLOW_FOOTER-1) {
            if (!FLOW_SEND(pending*FLOW_WIDTH,FLOW_WIDTH)) return 0;
            pending=0;
        }
    }
    return 1;
}

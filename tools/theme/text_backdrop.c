#include "wallpaper.h"

/* The stock text reader handles mode 2 in columns, using the descriptor
 * at 0x9fc0be4c. Its x input is relative to the backing image. */
struct text_region { unsigned short x, y, width, height; };
struct text_backdrop { unsigned short id, x, y, width, height; };
#ifndef STOCK_TEXT
#define STOCK_TEXT ((void (*)(unsigned short *,const struct text_region *,unsigned))0xbfc09ce5u)
#endif

void text_backdrop(unsigned short *pixels,const struct text_region *slice,unsigned mode)
{
    const volatile struct text_backdrop *bg=(void *)0x9fc0be4cu;
    if (mode==2 && WALL->magic==WALL_MAGIC && pixels && slice) {
        unsigned style=*(volatile unsigned *)0x9fc0bff8u;
        int matched=(style==0x50690 && bg->id==10 && bg->width==129 && bg->height==20) ||
            (style==0x1c4330 && bg->id==17 && bg->width==160 && bg->height==24) ||
            (bg->id==0x8031 && bg->width==110 && bg->height==20);
        unsigned x=bg->x+slice->x;
        if (matched && slice->x+slice->width<=bg->width && slice->height==bg->height &&
            x+slice->width<=320 && bg->y+bg->height<=240) {
            for (unsigned i=0;i<slice->width;i++,x++) {
                unsigned ridge=wall_ridge(x);
                for (unsigned j=0;j<slice->height;j++)
                    *pixels++=wall_pixel(x,bg->y+j,ridge);
            }
            return;
        }
    }
    STOCK_TEXT(pixels,slice,mode);
}

#ifdef FLOW_PREF
#include "../m3u/m3uplay.h"
struct header_region {u16 x,y,width,height;};
int flow_header_bitmap(u32 fd,u32 offset,const struct header_region *area)
{
    u32 target=WALL_DRAW_ENTRY;
    /* Music lists share the style with Now Playing. Common list art must
     * still pass through draw_wall, including rows in the footer band. */
    if (fd!=*(volatile u32 *)0x9fc0bfd4u &&
        (area->y+area->height<=28 || area->y>=179) && !VS->flow_disabled &&
        *(volatile u32 *)0x9fc0bff8u==0x50690)
        target=0xbfc0aa19u;
    return ((int (*)(u32,u32,const struct header_region *))target)(fd,offset,area);
}
#endif

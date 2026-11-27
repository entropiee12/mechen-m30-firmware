#include "flow.h"

/* Convert a stock NEIGH.PIC (OK header + RGB565) into the prepared 1 KiB
 * side-tile format. The 32x32 working image is bounded, and median-cut uses
 * no histogram or allocation, so this fits in the small LCD scratch area. */
typedef int (*flow_tile_read)(void *ctx,u32 offset,u8 data[512]);
struct box { u16 start,end; };

static unsigned channel(u16 pixel,unsigned which)
{
    if (which==0) return (pixel>>11)&31;
    if (which==1) return (pixel>>5)&63;
    return pixel&31;
}

static void sort_box(u16 *pixels,const struct box *box,unsigned which)
{
    /* Shell sort: bounded and much faster than insertion on a large box. */
    for (unsigned gap=(box->end-box->start)/2;gap;gap/=2) {
        for (unsigned i=box->start+gap;i<box->end;i++) {
            u16 value=pixels[i];
            unsigned j=i;
            while (j>=box->start+gap && channel(pixels[j-gap],which)>channel(value,which)) {
                pixels[j]=pixels[j-gap];
                j-=gap;
            }
            pixels[j]=value;
        }
    }
}

static unsigned split(u16 *pixels,struct box boxes[16],unsigned count)
{
    unsigned best=count,best_range=0,best_which=0;
    for (unsigned i=0;i<count;i++) {
        if (boxes[i].end-boxes[i].start<2) continue;
        unsigned lo[3]={~0u,~0u,~0u},hi[3]={0,0,0};
        for (unsigned j=boxes[i].start;j<boxes[i].end;j++) {
            for (unsigned c=0;c<3;c++) {
                unsigned v=channel(pixels[j],c);
                if (v<lo[c]) lo[c]=v;
                if (v>hi[c]) hi[c]=v;
            }
        }
        for (unsigned c=0;c<3;c++) {
            unsigned range=hi[c]-lo[c];
            if (range>best_range) { best=i; best_range=range; best_which=c; }
        }
    }
    if (best==count) return count;
    sort_box(pixels,boxes+best,best_which);
    unsigned middle=(boxes[best].start+boxes[best].end)/2;
    boxes[count].start=middle;
    boxes[count].end=boxes[best].end;
    boxes[best].end=middle;
    return count+1;
}

static int sample_image(flow_tile_read read,void *ctx,u16 *work,unsigned width,unsigned height)
{
    u8 row[512];
    u32 loaded=~0u;
    for (unsigned y=0;y<32;y++) {
        unsigned sy=y*height/32;
        for (unsigned x=0;x<32;x++) {
            unsigned sx=x*width/32;
            u32 offset=512+(sy*width+sx)*2,sector_offset=offset&~511u;
            if (sector_offset!=loaded) {
                if (!read(ctx,sector_offset,row)) return 0;
                loaded=sector_offset;
            }
            unsigned at=offset&511u;
            work[y*32+x]=row[at]|(row[at+1]<<8);
        }
    }

    return 1;
}

int flow_tile_encode(flow_tile_read read,void *ctx,struct flow_tile *tile,
                     u16 work[32*32],u16 track,u16 count,u32 pack)
{
    u8 sector[512];
    if (!read(ctx,0,sector) || sector[16]!='O' || sector[17]!='K') return 0;
    unsigned width=sector[20],height=sector[21];
    if (!width || !height || width*height>22500) return 0;
    if (!sample_image(read,ctx,work,width,height)) return 0;

    struct box boxes[16];
    boxes[0].start=0; boxes[0].end=32*32;
    unsigned colors=1;
    while (colors<16) {
        unsigned next=split(work,boxes,colors);
        if (next==colors) break;
        colors=next;
    }
    u16 palette[16];
    for (unsigned i=0;i<16;i++) {
        if (i<colors) {
            unsigned r=0,g=0,b=0,n=boxes[i].end-boxes[i].start;
            for (unsigned j=boxes[i].start;j<boxes[i].end;j++) {
                r+=channel(work[j],0); g+=channel(work[j],1); b+=channel(work[j],2);
            }
            palette[i]=((r/n)<<11)|((g/n)<<5)|(b/n);
        } else palette[i]=colors?palette[colors-1]:0;
    }
    /* Sorting is only for the palette. Reload spatial samples instead of
     * adding a second 2 KiB image to the foreground task's stack. */
    if (!sample_image(read,ctx,work,width,height)) return 0;
    for (unsigned i=0;i<64;i++) tile->palette[i]=palette[i%16];
    for (unsigned i=0;i<sizeof(tile->pixels);i++) tile->pixels[i]=0;
    for (unsigned y=0;y<40;y++) {
        for (unsigned x=0;x<40;x++) {
            u16 pixel=work[(y*32/40)*32+(x*32/40)];
            unsigned best=0,distance=~0u;
            for (unsigned i=0;i<16;i++) {
                int dr=(int)channel(pixel,0)-(int)channel(palette[i],0);
                int dg=(int)channel(pixel,1)-(int)channel(palette[i],1);
                int db=(int)channel(pixel,2)-(int)channel(palette[i],2);
                unsigned d=dr*dr+dg*dg+db*db;
                if (d<distance) { distance=d; best=i; }
            }
            unsigned at=y*40+x;
            if (at&1) tile->pixels[at/2]=(tile->pixels[at/2]&15)|(best<<4);
            else tile->pixels[at/2]=best;
        }
    }
    tile->magic=FLOW_MAGIC;
    tile->pack=pack;
    tile->track=track;
    tile->count=count;
    for (unsigned i=0;i<sizeof(tile->padding);i++) tile->padding[i]=0;
    const u8 *bytes=(const u8 *)tile;
    u32 hash=2166136261u;
    for (unsigned i=0;i<sizeof(*tile)-sizeof(tile->checksum);i++) hash=(hash^bytes[i])*16777619u;
    tile->checksum=hash;
    return 1;
}

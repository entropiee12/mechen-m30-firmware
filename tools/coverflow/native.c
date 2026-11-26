#include "flow.h"
#define FS ((fs_fn)0xbfc16bb5u)
#define DRAW ((flow_draw)FLOW_DRAW_ENTRY)

/* The artwork cache contains tightly packed RGB565 after its header sector.
 * Keep one input sector and one output row, separate from the LCD buffer. */
struct native_center {
    struct flow_center input;
    u16 *row;
    unsigned width,height,previous;
};

static const u16 *native_row(struct flow_center *input,unsigned y)
{
    struct native_center *image=(struct native_center *)input;
    if (!input->file) return 0;
    unsigned source_y=y*image->height/FLOW_COVER_HEIGHT;
    if (source_y==image->previous) return image->row;
    unsigned start=source_y*image->width;
    for (unsigned x=0;x<FLOW_CENTER_WIDTH;x++) {
        unsigned pos=start+x*image->width/FLOW_CENTER_WIDTH;
        unsigned sector=1+pos/256;
        if (input->sector!=sector) {
            if (FS(input->ctx,sector*512,0,input->file,OP_SEEK)<0 ||
                FS(input->ctx,(u32)input->pixels,512,input->file,OP_READ)<=0) {
                input->file=0;
                return 0;
            }
            input->sector=sector;
        }
        image->row[x]=input->pixels[pos%256];
    }
    image->previous=source_y;
    return image->row;
}

int native_cover(void)
{
    u16 *lcd=*(u16 *volatile *)0x9fc0bf50u;
    unsigned capacity=*(volatile u16 *)0x9fc0bf54u;
    if (!lcd || ((u32)lcd&3) || capacity<4096) return 0;
    unsigned available=(capacity-512-FLOW_CENTER_WIDTH*2)&~3u;
    u16 *scratch=lcd+available/2;
    struct native_center image;
    image.input.file=0;
    struct flow_window window={.center=&image.input,.native_row=native_row};
    u32 ctx=*(volatile u32 *)0x9fc15404u;
    static const u16 name[]={0xfeff,'A','L','B','U','M','.','P','I','C',0};
    u32 file=0;
    int drawn=0;
    if (*(volatile u32 *)0x9fc15418u&4) {
        FS(ctx,':',0,0,OP_CD);
        file=FS(ctx,(u32)name,0,0,OP_OPEN);
    }
    if (file) {
        u8 *header=(u8 *)scratch;
        if (FS(ctx,(u32)header,30,file,OP_READ)>0 &&
            header[16]=='O' && header[17]=='K' &&
            header[20] && header[21] && header[20]*header[21]<=22500) {
            image.input.ctx=ctx;
            image.input.file=file;
            image.input.pixels=scratch;
            image.input.sector=~0u;
            image.previous=~0u;
            image.row=scratch+256;
            image.width=header[20];
            image.height=header[21];
            drawn=DRAW(&window,lcd,available);
        }
        FS(ctx,file,0,0,OP_CLOSE);
    }
    image.input.file=0;
    return drawn || DRAW(&window,lcd,available);
}

/* These literal pools belong to the stock Now Playing widget painters.
 * Keep decoder, transport, and event dispatch on their original paths. */
u32 flow_scene_ui(u32 a,u32 b,u32 c,u32 op)
{
    if (!VS->flow_disabled) {
        if (op==18) {
            const struct flow_region *region=(const struct flow_region *)a;
            if (!((region->y==0 && region->height==FLOW_HEADER) ||
                  (region->y==FLOW_FOOTER && region->height==FLOW_HEIGHT-FLOW_FOOTER)))
                return 0;
            return ((u32 (*)(const struct flow_region *,u32,u32))0x36119d81u)(region,b,c);
        }
        if (op==2) {
            u16 widget=*(const u16 *)a;
            if (widget!=0x1000 && widget!=0x1001 && widget!=0x1004) return 0;
        }
        if (op==3 || op==5) return 0;
        if (op==20) a=FLOW_BACKGROUND;
    }
    return ((u32 (*)(u32,u32,u32,u32))0xbfc16405u)(a,b,c,op);
}

/* The stock metadata painter also arms a one-second repaint timer. Use its
 * own cancellation path when Cover Flow owns the Now Playing display. */
void flow_metadata(void)
{
    if (VS->flow_disabled) {
        ((void (*)(void))0x40154c89u)();
        return;
    }
    volatile signed char *timer=(volatile signed char *)0x9fc15401u;
    if (*timer>=0) {
        ((void (*)(int))0x4845473du)(*timer);
        *timer=-1;
    }
}

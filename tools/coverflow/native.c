#include "flow.h"
#define FS ((fs_fn)0xbfc16bb5u)
#define DRAW ((flow_draw)FLOW_DRAW_ENTRY)

/* The artwork cache contains tightly packed RGB565 after its header sector.
 * Keep one input sector and one output row, separate from the LCD buffer. */
static const u16 *native_row(struct flow_center *input,unsigned y)
{
    struct native_center *image=(struct native_center *)input;
    if (!input->file || image->failed) return 0;
    unsigned source_y=y*image->height/FLOW_COVER_HEIGHT;
    if (source_y==image->previous) return image->row;
    unsigned start=source_y*image->width;
    for (unsigned x=0;x<FLOW_CENTER_WIDTH;x++) {
        unsigned pos=start+x*image->width/FLOW_CENTER_WIDTH;
        unsigned sector=1+pos/256;
        if (input->sector!=sector) {
            if (FS(input->ctx,sector*512,0,input->file,OP_SEEK)<0 ||
                FS(input->ctx,(u32)input->pixels,512,input->file,OP_READ)<=0) {
                image->failed=1;
                return 0;
            }
            input->sector=sector;
        }
        image->row[x]=input->pixels[pos%256];
    }
    image->previous=source_y;
    return image->row;
}

int native_prepare(struct flow_window *window,struct native_center *image,u16 *scratch)
{
    image->input.file=0;
    image->failed=0;
    window->center=&image->input;
    window->native_row=native_row;
    u32 ctx=*(volatile u32 *)0x9fc15404u;
    static const u16 name[]={0xfeff,'A','L','B','U','M','.','P','I','C',0};
    if (!(*(volatile u32 *)0x9fc15418u&4) || FS(ctx,':',0,0,OP_CD)<=0) return 0;
    u32 file=FS(ctx,(u32)name,0,0,OP_OPEN);
    if (!file) return 0;
    u8 *header=(u8 *)scratch;
    if (FS(ctx,(u32)header,512,file,OP_READ)<=0 ||
        header[16]!='O' || header[17]!='K' ||
        !header[20] || !header[21] || header[20]*header[21]>22500) {
        FS(ctx,file,0,0,OP_CLOSE);
        return 0;
    }
    image->input.ctx=ctx;
    image->input.file=file;
    image->input.pixels=scratch;
    image->input.sector=~0u;
    image->previous=~0u;
    image->row=scratch+256;
    image->width=header[20];
    image->height=header[21];
    return 1;
}

void native_finish(struct native_center *image)
{
    if (image->input.file) FS(image->input.ctx,image->input.file,0,0,OP_CLOSE);
    image->input.file=0;
}

int native_cover(int status)
{
    u16 *lcd=*(u16 *volatile *)0x9fc0bf50u;
    unsigned capacity=*(volatile u16 *)0x9fc0bf54u;
    if (!lcd || ((u32)lcd&3) || capacity<4096) return 0;
    unsigned available=(capacity-512-FLOW_CENTER_WIDTH*2)&~3u;
    u16 *scratch=lcd+available/2;
    struct native_center image;
    struct flow_window window={0};
    int drawn=0;
    if (native_prepare(&window,&image,scratch)) {
        drawn=DRAW(&window,lcd,available);
        native_finish(&image);
    }
    image.input.file=0;
    if (!drawn) drawn=DRAW(&window,lcd,available);
#ifdef FLOW_DIAG
    /* Temporary on-device probe: 1..8 magenta blocks at the upper-left of
     * the artwork area identify the automatic path's exit stage. */
    if (status<0 && status>=-8) {
        unsigned blocks=(unsigned)-status;
        struct flow_region marker={0,FLOW_HEADER,blocks*4,4};
        int (*region)(const struct flow_region *,u32)=
            *(int (*volatile *)(const struct flow_region *,u32))0x9fc0bf98u;
        int (*send)(u32,u32)=*(int (*volatile *)(u32,u32))0x9fc0bf84u;
        if (region && send && region(&marker,0)) {
            for (unsigned i=0;i<blocks*16;i++)
                lcd[i]=(i%(blocks*4))%4==3?FLOW_BACKGROUND:0xf81f;
            send(blocks*16,blocks*4);
        }
    }
#else
    (void)status;
#endif
    return drawn;
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
    flow_name_album();
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

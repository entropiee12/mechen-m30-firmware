#include "flow.h"

static int valid(const struct flow_tile *tile,u32 pack,u32 count)
{
    if ((tile->magic!=FLOW_MAGIC && tile->magic!=FLOW_MAGIC_48 && tile->magic!=FLOW_MAGIC_DETAIL) || tile->pack!=pack || tile->count!=count ||
        !tile->track || tile->track>count) return 0;
    const u8 *bytes=(const u8 *)tile;
    u32 hash=2166136261u;
    for (unsigned i=0;i<sizeof(*tile)-sizeof(tile->checksum);i++) hash=(hash^bytes[i])*16777619u;
    return hash==tile->checksum;
}

int flow_cache(struct flow_tile *slots,struct flow_window *window,const u16 tracks[FLOW_SLOTS],
               u32 pack,u32 count,flow_load load,void *ctx)
{
    /* LCD scratch may be reused by stock between refreshes. Validate each
     * resident sector before reuse; pin all hits before evicting any misses. */
    int at[FLOW_SLOTS];
    for (unsigned i=0;i<FLOW_SLOTS;i++) at[i]=-1;
    unsigned pinned=0;
    for (unsigned j=0;j<FLOW_SLOTS;j++) {
        if (!valid(slots+j,pack,count)) continue;
        for (unsigned i=0;i<FLOW_SLOTS;i++) {
            if (tracks[i]==slots[j].track && at[i]<0) {
                at[i]=j;
                pinned|=1u<<j;
            }
        }
    }
    static const u8 priority[]={2,1,3,0,4};
    for (unsigned k=0;k<FLOW_SLOTS;k++) {
        unsigned i=priority[k];
        if (!tracks[i] || at[i]>=0) continue;
        unsigned j=0;
        while (j<FLOW_SLOTS && (pinned&(1u<<j))) j++;
        if (j==FLOW_SLOTS) return 0;
        if (!load(tracks[i],slots+j,ctx) || !valid(slots+j,pack,count) ||
            slots[j].track!=tracks[i]) {
            slots[j].magic=0;
            if (i>=1 && i<=FLOW_VISIBLE) return 0;
            continue;
        }
        pinned|=1u<<j;
        for (unsigned m=0;m<FLOW_SLOTS;m++) if (tracks[m]==tracks[i]) at[m]=j;
    }
    for (unsigned i=0;i<FLOW_VISIBLE;i++) window->visible[i]=at[i+1]<0?0:slots+at[i+1];
    return window->visible[FLOW_VISIBLE/2]!=0;
}

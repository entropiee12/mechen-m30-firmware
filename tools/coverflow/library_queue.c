#include "library_queue.h"

static unsigned read16(const u8 *p)
{
    return (unsigned)p[0] | (unsigned)p[1]<<8;
}

unsigned flow_library_window(flow_library_read read, void *ctx, u32 sectors,
                             u16 index_sector, u16 first, u16 index_end,
                             const u16 positions[FLOW_SLOTS],
                             struct flow_track_location tracks[FLOW_SLOTS])
{
    union { u32 align[128]; u8 bytes[512]; } buffer;
    u8 *data=buffer.bytes;
    /* Runtime callers may pass 0xffff: positions are already bounded by the
     * active queue count, so stop at that rank instead of an explicit group end. */
    if (index_end==0xffff && index_sector<sectors) {
        u32 available=sectors-index_sector;
        index_end=available>=256?0xffff:(u16)(available*256);
    }
    if (!index_sector || index_sector>=sectors || index_end<=first ||
        !read(ctx,0,data) || data[0]!='M' || data[1]!='U' || data[2]!='S' ||
        data[3]!='I' || data[4]!='C' || data[510]!=0x55 || data[511]!=0xaa)
        return 0;
    const unsigned count=read16(data+8), records=read16(data+10);
    if (!count || !records || records+(count+1)/2>index_sector) return 0;

    /* Stock scans past 0xffff holes. Stop at the end of this queue, even for
     * a damaged table, so the next album's entries cannot leak into it. */
    unsigned wanted=0;
    for (unsigned slot=0;slot<FLOW_SLOTS;slot++)
        if (positions[slot]>wanted) wanted=positions[slot];
    if (!wanted || wanted>count ||
        (u32)index_sector+((index_end-1u)>>8)>=sectors) return 0;

    u16 ids[FLOW_SLOTS]={0};
    unsigned rank=0, found=0, loaded=0;
    for (unsigned i=first;i<index_end && rank<wanted;i++) {
        unsigned sector=index_sector+(i>>8);
        if (sector!=loaded) {
            if (!read(ctx,sector,data)) break;
            loaded=sector;
        }
        unsigned id=read16(data+2*(i&255));
        if (id==0xffff) continue;
        rank++;
        if (id>=count) continue;
        for (unsigned slot=0;slot<FLOW_SLOTS;slot++) {
            if (positions[slot]!=rank) continue;
            ids[slot]=id;
            found|=1u<<slot;
        }
    }

    loaded=0;
    for (unsigned slot=0;slot<FLOW_SLOTS;slot++) {
        if (!(found&(1u<<slot))) continue;
        unsigned id=ids[slot], sector=records+(id>>1);
        if (sector!=loaded) {
            if (!read(ctx,sector,data)) {
                loaded=0;
                found&=~(1u<<slot);
                continue;
            }
            loaded=sector;
        }
        const u8 *record=data+(id&1)*256;
        for (unsigned j=0;j<75;j++) tracks[slot].directory[j]=record[0xa9+j];
        for (unsigned j=0;j<8;j++) tracks[slot].cursor[j]=record[0xf4+j];
        for (unsigned j=0;j<4;j++) tracks[slot].extension[j]=record[0xa4+j];
    }
    return found;
}

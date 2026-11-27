#ifndef M30_FLOW_LIBRARY_QUEUE_H
#define M30_FLOW_LIBRARY_QUEUE_H
#include "flow.h"

struct flow_track_location {
    u8 directory[75];           /* VFS directory data from record +0xa9 */
    u8 cursor[8];               /* VFS file position from record +0xf4 */
    u8 extension[4];
};

/* Read a sector from the already opened library with an independent cursor. */
typedef int (*flow_library_read)(void *ctx, u32 sector, u8 data[512]);

/* first is a zero-based entry offset within the index table. positions are
 * one-based within the active queue, with zero representing an empty slot. */
unsigned flow_library_window(flow_library_read read, void *ctx, u32 sectors,
                             u16 index_sector, u16 first, u16 index_end,
                             const u16 positions[FLOW_SLOTS],
                             struct flow_track_location tracks[FLOW_SLOTS]);
#endif

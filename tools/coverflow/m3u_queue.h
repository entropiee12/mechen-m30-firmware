#ifndef M30_FLOW_M3U_QUEUE_H
#define M30_FLOW_M3U_QUEUE_H
#include "library_queue.h"

/* The caller must pass a separate card context and a snapshot of the active
 * playlist. This lookup changes only the directory cursor in that context. */
int flow_m3u_track(fs_fn fs, u32 ctx, const struct vstate *playlist,
                   unsigned position, struct flow_track_location *location);
#endif

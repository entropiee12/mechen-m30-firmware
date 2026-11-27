#include "m3u_queue.h"

struct lookup {
    fs_fn fs;
    u32 ctx;
    const struct vstate *playlist;
    char line[M3U_LINE_CAP];
};

#define M3U_LOOKUP_ARGS struct lookup *request,
#define M3U_LOOKUP_PASS request,
#define FS_CALL (request->fs)
#define VFS_CTX (request->ctx)
#define PL_DIR (request->playlist->pl_dir)
#define PL_OFF (request->playlist->pl_off)
#define PL_LAYER (request->playlist->pl_layer)
#define LINE_BUF (request->line)
#include "../m3u/m3u_resolve.h"

int flow_m3u_track(fs_fn fs, u32 ctx, const struct vstate *playlist,
                   unsigned position, struct flow_track_location *location)
{
    if (playlist->magic!=M3UPLAY_MAGIC || !position || position>playlist->count)
        return 0;

    struct lookup request;
    request.fs=fs;
    request.ctx=ctx;
    request.playlist=playlist;
    union { u32 align[128]; u8 bytes[512]; } sector;
    u32 length=m3u_scan_entry(&request,sector.bytes,position-1);
    u32 extension;
    u8 layer;
    if (!m3u_resolve(&request,length,&extension,&layer))
        return 0;

    /* OP_OFFSET writes a 73-byte directory stack and an aligned file cursor.
     * Keep its output disjoint from the live playback selector. */
    u32 directory[19], cursor[2];
    if (fs(ctx,(u32)directory,(u32)cursor,0,OP_OFFSET)<=0)
        return 0;
    const u8 *bytes=(const u8 *)directory;
    for (unsigned i=0;i<73;i++) location->directory[i]=bytes[i];
    location->directory[73]=location->directory[74]=0;
    bytes=(const u8 *)cursor;
    for (unsigned i=0;i<8;i++) location->cursor[i]=bytes[i];
    for (unsigned i=0;i<4;i++) location->extension[i]=extension>>(8*i);
    return 1;
}

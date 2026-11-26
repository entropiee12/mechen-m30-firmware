/* Virtual-folder .m3u/.m3u8 click dispatcher + VFS track resolver for browser.ap
 * (linked at 0x71252500 in unused group-0x71 bank, max 2048 bytes, window 0x71).
 *
 * m3u_file_click MUST be the first function in this unit (links at 0x71252500).
 * Living in window 0x71 guarantees browser.ap's 0x40CD4C00..0x40CD4E00
 * row_names[0..6] buffers and 0x40CD4E00 / 0x40D14E00 UI banks in window 0x40
 * are NEVER evicted or overwritten!
 */
#define M3U_BROWSER 1
#include "m3uplay.h"

#ifdef M3UPLAY_TEST
extern fs_fn test_fs;
extern volatile struct brow_fsel *test_bf;
extern u8 *test_brow_hdr;
#define FS_CALL     (test_fs)
#define BF          (test_bf)
#define BROW_HDR    (test_brow_hdr)
#define CALL_OPEN   (m3u_vdir_open)
#define CALL_FMT    (m3u_format_row)
#else
#define FS_CALL     ((fs_fn)0xBFC16A2Du)
#define BF          ((volatile struct brow_fsel *)0x9FC15734u)
#define BROW_HDR    ((u8 *)0x9FC15598u)
#define CALL_OPEN   ((u32 (*)(u32, const u8 *, u8 *, u32 (*)(void)))M3U_SYM_OPEN)
#define CALL_FMT    ((void (*)(const u8 *, u32, u8 *))M3U_SYM_FMT)
#endif

#define VFS_CTX         (BF->vfs_ctx)
#define PL_DIR          (VD->pl_dir)
#define PL_OFF          (VD->pl_off)
#define PL_LAYER        (VD->pl_layer)
#define LINE_BUF        (BW->line)
#define format_vdir_row CALL_FMT

static long vfs(u32 a1, u32 a2, u32 a3, u32 op);
static u32 m3u_scan_ex(u8 *sec, u32 want, u32 cnt, u8 *row_out);
static int m3u_resolve(u32 len, u32 *out_ext, u8 *out_layer);
static void copy_bytes(volatile void *dst, const volatile void *src, u32 n);
static u32 scan_count_cb(void);

u32 m3u_file_click(u8 *arg0, u8 *state)
{
    struct vdir_state *vd = VD;
    volatile struct brow_fsel *bf = BF;
    u8 *hdr = BROW_HDR;
    u32 sel, list_no, skip, file_idx;
    const u8 *row;

    if (!state) {
        return 0xFFFEu;
    }
    sel = *(const u16 *)(state + 0x10);
    if (sel == 0 || sel >= 8u) {
        return 0xFFFEu;
    }
    list_no = *(const u16 *)(state + 0x0E);
    skip = (u32)(*(const u16 *)(hdr + 0x62)) + (u32)(*(const u16 *)(hdr + 0));
    if (list_no <= skip) {
        return 0xFFFEu;
    }
    file_idx = list_no - skip;
    row = hdr + sel * 12u;

    /* Case 1: Inside an open .m3u virtual folder -> play clicked track! */
    if (vd->magic == M3UVD_MAGIC) {
        volatile struct vstate *vs = VS;
        u8 *sec = BW->sec;
        u32 count = vd->count;
        u32 start_k = file_idx - 1u;
        u32 tries, k = count;
        u32 ext = 0;
        u8 layer = 0;
        u8 *desc;

        if (bf->vfs_ctx == 0xFFFFFFFFu || !arg0 || file_idx > count) {
            goto play_fail_no_restore;
        }
        for (tries = 0; tries < 16u && start_k + tries < count; tries++) {
            u32 cand = start_k + tries;
            u32 len = m3u_scan_ex(sec, cand, 0, 0);
            if (len != 0xFFFFFFFFu && m3u_resolve(len, &ext, &layer)) {
                k = cand;
                break;
            }
        }
        if (k >= count) {
            goto play_restore_fail;
        }

        arg0[0] = 0;
        desc = arg0 + 4;
        {
            u32 j;
            for (j = 0; j < 0xA4u / 4u; j++) {
                ((u32 *)desc)[j] = 0;
            }
        }
        if (!vfs((u32)(desc + 0x10), (u32)(desc + 8), 0, OP_OFFSET)) {
            goto play_restore_fail;
        }
        desc[0] = vd->disk;
        desc[1] = layer;
        *(u32 *)(desc + 4) = ext;
        *(u16 *)(desc + 0x5C) = (u16)count;
        *(u16 *)(desc + 0x5E) = (u16)(k + 1u);
        bf->sel_item = (u16)file_idx;

        vs->magic = 0;
        copy_bytes(vs->pl_off, vd->pl_off, 84u);
        vs->pl_layer = vd->pl_layer;
        vs->disk = vd->disk;
        vs->cur_off[0] = *(const u32 *)(desc + 8);
        vs->cur_off[1] = *(const u32 *)(desc + 12);
        vs->pos = (u16)(k + 1u);
        vs->count = (u16)count;
        vs->shuf_key = 0;
        vs->shuf_pos = 0;
#ifdef M30_COVERFLOW
        vs->flow_pack=vd->flow_pack;
#ifndef M3UPLAY_TEST
        /* Publish the first shuffle epoch before the artwork preview runs.
         * Cursor zero preserves the engine's first-skip starting position. */
        if (vd->flow_pack)
            vs->shuf_key=((((u32 (*)(u32,u32,u32,u32))0xbfc16a25u)(0,0,0,0x35)^0x3d)&0xffffu)|1u;
#endif
        vs->handoff=M3U_HANDOFF_CLICK;
#endif
        vs->magic = M3UPLAY_MAGIC;

        vfs((u32)vd->pl_dir, (u32)vd->pl_off, 1u, OP_OFFSET);
        return 0x8001u;

play_restore_fail:
        vfs((u32)vd->pl_dir, (u32)vd->pl_off, 1u, OP_OFFSET);
play_fail_no_restore:
#ifdef M3UPLAY_PROBE
        return 0u;
#else
        return 0x8019u;
#endif
    }

    /* Case 2: Normal folder, non-.m3u file -> stock Play/Delete prompt. */
    if (!((row[0] | 0x20u) == 'm' && row[1] == '3' && (row[2] | 0x20u) == 'u'
          && (row[3] == 0 || row[3] == ' ' || row[3] == '8'))) {
        return 0xFFFEu;
    }

    /* Case 3: Normal folder, .m3u/.m3u8 row -> open as virtual folder! */
    return CALL_OPEN(file_idx, row, state, scan_count_cb);
}

u32 m3u_vdir_get_items(const u8 *req, u8 *row_out)
{
    u32 cnt, start;

    if (!req || !row_out || BF->vfs_ctx == 0xFFFFFFFFu) {
        return 0u;
    }
    cnt = req[1];
    if (cnt == 0) {
        return 1u;
    }
    start = *(const u16 *)(req + 2);
    return m3u_scan_ex(BW->sec, start ? (start - 1u) : 0u, cnt, row_out);
}

static void copy_bytes(volatile void *dst, const volatile void *src, u32 n)
{
    volatile u8 *d = (volatile u8 *)dst;
    const volatile u8 *s = (const volatile u8 *)src;
    while (n--) {
        *d++ = *s++;
    }
}

static u32 scan_count_cb(void)
{
    return m3u_scan_ex(BW->sec, 0xFFFFFFFFu, 0, 0);
}

#ifdef M30_COVERFLOW
#include "../coverflow/marker.h"
#endif
#include "m3u_resolve.h"

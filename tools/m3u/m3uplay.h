/* Virtual-folder .m3u/.m3u8 browsing + mengine playlist playback.
 *
 * Architecture (verified by IDA Pro / Hex-Rays disassembly of browser.ap,
 * playlist.ap, mengine.ap, and exfatn.drv):
 *
 *   1. Kernel VFS (module 0x11) has NO multi-component path open, rejects
 *      vfs_dir (op 1) commands < 0x80 with error 21, and returns boolean 1/0
 *      (NOT byte count!) from OP_READ (0xD), requiring OP_SIZE (0x9, handle in
 *      $a2) beforehand to bound sector reads.
 *
 *   2. browser.ap virtual-folder hooks:
 *      - File-row click hook at 0x40D15108 -> m3uplay_tramp (0x400D4CD0):
 *        * Clicking a .m3u/.m3u8 row in a normal folder snapshots its VFS
 *          location into VD (0x9FC15B20, unreferenced resident RAM isolated
 *          from mengine.ap), counts its tracks, populates brow_hdr (0 dirs,
 *          N files, layer+1, .m3u title), and triggers an immediate folder
 *          redraw (bypassing the Play/Delete prompt).
 *        * Clicking track k inside an open .m3u virtual folder resolves entry
 *          k-1 across folders ('/', '\\', '..', UTF-8 LFNs), fills arg0+4's
 *          0xA4-byte file_location_t, publishes the playlist to VS
 *          (0xBFC10880, key.drv resident tail), and returns 0x8001 to launch
 *          music.ap immediately.
 *        * Clicking any non-.m3u file in a normal folder tail-calls stock
 *          0x4875493C (Play/Delete prompt) unchanged.
 *      - Four mode-0 fsel pointers in 0x780DB200 redirect through
 *        m3u_brow_tramp (0x780DB7E0):
 *        * get_items[0] renders 7 visible track basenames in one sequential
 *          pass over VD's .m3u.
 *        * enter_dir[0] handles virtual refresh (cmd=0), Back to parent folder
 *          (cmd=2), and root reset (cmd=3).
 *        * set_location[0] reopens the virtual .m3u folder when returning from
 *          music.ap while VS is active.
 *        * delete[0] guards against accidental deletion inside virtual folders.
 *
 *   3. mengine.ap runs concurrently in its own address space (VFS stub
 *      0xBFC11CCD, context *0x9FC15F34). Four mode-0 fsel pointers in
 *      0x7A052D00 redirect through m3u_meng_tramp (0x7A053338) to
 *      m3u_meng_step (0x7B268000) to step Next/Prev/Byno/Repeat/Shuffle across
 *      the active playlist in VS.
 */
#ifndef M3UPLAY_H
#define M3UPLAY_H

#define M3U_STATE_ADDRESS 0xBFC10880
#define M3U_STATE_CAPACITY 0x180
#define M3UPLAY_WAITING 0x4d335657
#define M3U_HANDOFF_CLICK 1
#define M3U_HANDOFF_MISMATCH 2
#define M3U_HANDOFF_OFFSET 0x160
#define M3UPLAY_MAGIC_RAW 0x4D335650
#define M3UVD_MAGIC_RAW   0x4D335644
#define M3U_LINE_CAP      240           /* max UTF-8 track path bytes (card max: 168) */

#ifdef __ASSEMBLER__
#define M3UPLAY_MAGIC M3UPLAY_MAGIC_RAW
#define M3UVD_MAGIC   M3UVD_MAGIC_RAW
#else
#define M3UPLAY_MAGIC 0x4D335650u       /* 'M3VP': active mengine playlist */
#define M3UVD_MAGIC   0x4D335644u       /* 'M3VD': open browser virtual .m3u folder */

typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;

typedef long (*fs_fn)(u32, u32, u32, u32, u32);

/* VFS module 0x11 operation IDs (5th o32 stack arg at 0x10($sp)). */
#define OP_CD       0x0
#define OP_DIR      0x1
#define OP_GETNAME  0x2
#define OP_OFFSET   0x3
#define OP_OPEN     0x5
#define OP_CLOSE    0x7
#define OP_SIZE     0x9     /* ($a0=ctx, $a1=&u32_sz, $a2=handle, $a3=0, 9) */
#define OP_SEEK     0xB     /* ($a0=ctx, $a1=byte_off, $a2=0, $a3=handle, 0xB) */
#define OP_READ     0xD     /* ($a0=ctx, $a1=buf, $a2=512, $a3=handle, 0xD) -> 1/0 */

#define DIR_NEXT    0x80u
#define DIR_HEAD    0x82u
#define EXT_DIRS    1u
/* Stock audio extension mask with the repurposed M3U/M3U8 slots (bits 5,8,10,12)
 * cleared so playlist files themselves are never matched as audio tracks. */
#define AUDIO_MASK  0x7E190A80u

/* Browser virtual-folder state in unreferenced resident RAM (0x9FC15B20..0x9FC15BB4,
 * 0x94 = 148 bytes; verified 0 references across all firmware binaries, above
 * every foreground .ap .bss end 0x9FC15AF4 and below scroll 0x9FC15BF4 and
 * mengine.ap 0x9FC15C00). Isolated from VS so browsing another .m3u never
 * races with background mengine.ap track stepping. */
struct vdir_state {
    u32  magic;                 /* +0x00: M3UVD_MAGIC when virtual .m3u is open */
    u16  count;                 /* +0x04: total entries in browsed .m3u */
    u8   pl_layer;              /* +0x06: parent directory depth of the .m3u */
    u8   disk;                  /* +0x07: disk byte from browser (e.g. 'H') */
    u8   pl_off[8];             /* +0x08: 8-byte VFS file_off of the .m3u */
    u8   pl_dir[76];            /* +0x10: 73-byte VFS dir_stack of the .m3u (+3 pad) */
    u8   title[52];             /* +0x5C: 50-byte display title of the .m3u (+2 pad) */
    u32 flow_pack;              /* +0x90: cover-pack tag in the prepared playlist */
};

/* Shared playback state in key.drv resident padding, outside the card driver overlays. */
struct vstate {
    u32  magic;                 /* +0x00: M3UPLAY_MAGIC when a playlist is playing */
    u16  pos;                   /* +0x04: 1-based current entry index (1..count) */
    u16  count;                 /* +0x06: total entries in the playlist */
    u8   pl_layer;              /* +0x08: directory depth of the .m3u file */
    u8   disk;                  /* +0x09: disk byte from browser (e.g. 'H') */
    u16  shuf_key;              /* +0x0A: Feistel key (0 = needs seed) */
    u32  cur_off[2];            /* +0x0C: 8-byte VFS file_off of the active track */
    u8   pl_off[8];             /* +0x14: 8-byte VFS file_off of the .m3u file */
    u8   pl_dir[76];            /* +0x1C: 73-byte VFS dir_stack of the .m3u (+3 pad) */
    u32  shuf_pos;              /* +0x68: current Feistel domain cursor */
    char line[M3U_LINE_CAP];    /* +0x6C: UTF-8 path buffer for mengine */
    u32 flow_pack;              /* +0x15c: prepared artwork pack, zero for ordinary playlists */
    u32 handoff;
    u32 flow_disabled;
    u32 native_visible;
    u32 flow_override;
};

_Static_assert(sizeof(struct vstate)<=M3U_STATE_CAPACITY,"playlist state exceeds resident padding");
_Static_assert(__builtin_offsetof(struct vstate,handoff)==M3U_HANDOFF_OFFSET,"handoff offset for engine trampoline");

struct brow_fsel {
    u8   mode;                  /* +0x00 (0x9FC15734) */
    u8   err;                   /* +0x01 (0x9FC15735) */
    u8   layer;                 /* +0x02 (0x9FC15736) */
    u8   pad0[17];              /* +0x03..+0x13 */
    u32  vfs_ctx;               /* +0x14 (0x9FC15748) */
    u8   cfg[3];                /* +0x18..+0x1A */
    u8   disk;                  /* +0x1B (0x9FC1574F) */
    u32  mask;                  /* +0x1C (0x9FC15750) */
    u8   pad1[0xA4];            /* +0x20..+0xC3 */
    u16  sel_item;              /* +0xC4 (0x9FC157F8) */
    u16  pad2;                  /* +0xC6 */
    u16  dir_cnt;               /* +0xC8 (0x9FC157FC) */
    u16  file_cnt;              /* +0xCA (0x9FC157FE) */
};

struct brow_work {
    u8   sec[512];              /* 0x9FC15830..0x9FC15A30 */
    char line[240];             /* 0x9FC15A30..0x9FC15B20 */
    struct vdir_state vd;       /* 0x9FC15B20..0x9FC15BB0 */
};

#ifdef M3UPLAY_TEST
extern volatile struct vstate *test_vs;
extern struct brow_work *test_bw;
#define VS (test_vs)
#define BW (test_bw)
#define VD (&test_bw->vd)
#else
#define VS ((volatile struct vstate *)M3U_STATE_ADDRESS)
#define BW ((struct brow_work *)0x9FC15830u)
#define VD ((struct vdir_state *)0x9FC15B20u)
#endif

/* Entry points and cross-bank helpers. */
u32 m3u_file_click(u8 *arg0, u8 *state);
u32 m3u_vdir_get_items(const u8 *req, u8 *row_out);
u32 m3u_vdir_open(u32 file_idx, const u8 *row, u8 *state, u32 (*scan_count)(void));
void m3u_format_row(const u8 *line, u32 len, u8 *row);
u32 m3u_vdir_dir_op(u32 cmd, u32 idx, u8 *out);
u32 m3u_vdir_setloc(const u8 *loc, u32 flag);
u32 m3u_meng_step(u32 *out, u32 arg, u32 op);

#endif /* !__ASSEMBLER__ */
#endif /* M3UPLAY_H */

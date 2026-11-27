/* Host (-m32) scenario harness for m3uplay.c + m3u_brow.c + m3u_meng.c.
 *
 * Models the exact ATJ2137 exFAT VFS ABI verified from exfatn.drv, browser.ap,
 * playlist.ap, and mengine.ap:
 *   - op 0 (OP_CD): ':', '|', '/' over a multi-folder directory tree
 *   - op 1 (OP_DIR): ONLY accepts 0x82 (DIR_HEAD) and 0x80 (DIR_NEXT);
 *                    rejects < 0x80 with 0 (just like real exfatn.drv err 21)
 *   - op 2 (OP_GETNAME): writes FF FE + UTF-16LE + 00 00 when max_units > 0,
 *                        or the 4-byte packed extension when max_units == 0
 *   - op 3 (OP_OFFSET): snapshots/restores 73B dir_stack + 8B file_off
 *   - op 5 (OP_OPEN): opens a file by its UTF-16LE name
 *   - op 7 (OP_CLOSE): closes open file handle
 *   - op 9 (OP_SIZE): handle in $a2, writes exact byte length to *(u32 *)$a1
 *   - op 0xB (OP_SEEK): handle in $a3, sector-aligned seek
 *   - op 0xD (OP_READ): handle in $a3, fills trailing bytes past EOF in the
 *                       512B sector with 0xAA garbage, advances seek_pos by
 *                       512, and returns boolean 1 (NOT byte count 512!)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../coverflow/m3u_queue.h"
#include "m3uplay.h"

volatile struct vstate *test_vs;
struct brow_work *test_bw;
volatile struct brow_fsel *test_bf;
u8 *test_brow_hdr;

volatile u8 *test_meng_eng;
u8 *test_meng_sec;

/* ---- Mock exFAT tree ---------------------------------------------------- */

struct vnode {
    int parent;         /* -1 for root */
    int is_dir;
    const char *name;   /* UTF-8 */
    u32 ext4;           /* 4-byte packed ext as stored on exFAT */
    u32 ext_bit;        /* mask bit in rom_ext_name */
    const char *data;   /* file contents (for .m3u files) */
    u32 size;
};

#define EXT4(a,b,c,d) ((u32)(u8)(a) | ((u32)(u8)(b)<<8) | ((u32)(u8)(c)<<16) | ((u32)(u8)(d)<<24))

#define BIT_MP3  (1u << 30)
#define BIT_FLAC (1u << 26)
#define BIT_M3U  (1u << 8)
#define BIT_M3U8 (1u << 12)

/* Multi-sector (> 1100 bytes, non-512-aligned) playlist exercising BOM, CRLF,
 * LF, >240-byte comment skipping, '../', UTF-8 CJK/accents, >100-char LFNs,
 * missing ghost entry skipping, 7-row paging across 9 entries, and an
 * unterminated final line! */
static const char idk_m3u[] =
    "\xEF\xBB\xBF"
    "#EXTM3U\r\n"
    "#CF7=123B6D9\r\n"
    "#EXTINF:210,Very long comment line 1 -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------\n"
    "#EXTINF:210,Padding comment to push playlist across multiple 512-byte sectors ============================================================================================================================================================================================================\n"
    "#EXTINF:210,More multi-sector padding ====================================================================================================================================================================================================================================================\n"
    "../Music/2hollis/boy/04 - crash.flac\n"
    "../Music/missing_ghost.flac\n"
    "../Music/T\xC3\x81VOL/\xE2\x98\x86NoAki\xE2\x98\x86/\xE3\x80\x8CDigital Twin\xE3\x80\x8D.flac\r\n"
    "../Music/LongDir/09 - One Day The Only Butterflies Left Will Be In Your Chest As You March Towards Your Death (feat. Amy L.flac\n"
    "../Music/2hollis/boy/03 - intro.flac\n"
    "../Music/2hollis/boy/04 - crash.flac\n"
    "../Music/T\xC3\x81VOL/\xE2\x98\x86NoAki\xE2\x98\x86/\xE3\x80\x8CDigital Twin\xE3\x80\x8D.flac\n"
    "../Music/2hollis/boy/03 - intro.flac\n"
    "/Music/root_hit.mp3";

static const char chill_m3u[] =
    "../Music/2hollis/boy/03 - intro.flac\n"
    "/Music/root_hit.mp3\n";

static const char empty_m3u[] = "#EXTM3U\r\n   \r\n# comment only\n";
static const char broken_m3u[] = "../Music/nope1.flac\n../Music/nope2.mp3\n";

static struct vnode tree[] = {
    /*  0 */ { -1, 1, "",           0, 0, 0, 0 },
    /*  1 */ {  0, 1, "Playlists",  0, 0, 0, 0 },
    /*  2 */ {  0, 1, "Music",      0, 0, 0, 0 },
    /*  3 */ {  2, 1, "2hollis",    0, 0, 0, 0 },
    /*  4 */ {  3, 1, "boy",        0, 0, 0, 0 },
    /*  5 */ {  2, 1, "T\xC3\x81VOL", 0, 0, 0, 0 },
    /*  6 */ {  5, 1, "\xE2\x98\x86NoAki\xE2\x98\x86", 0, 0, 0, 0 },
    /*  7 */ {  2, 1, "LongDir",    0, 0, 0, 0 },
    /* Subfolders inside Playlists/ (to verify dir_cnt subtraction!): */
    /*  8 */ {  1, 1, "SubA",       0, 0, 0, 0 },
    /*  9 */ {  1, 1, "SubB",       0, 0, 0, 0 },
    /* Files in Playlists/ (parent 1): */
    /* 10 */ {  1, 0, "idk.m3u",    EXT4('m','3','u',0),   BIT_M3U,  idk_m3u,    sizeof idk_m3u - 1 },
    /* 11 */ {  1, 0, "empty.m3u",  EXT4('m','3','u',0),   BIT_M3U,  empty_m3u,  sizeof empty_m3u - 1 },
    /* 12 */ {  1, 0, "broken.m3u8",EXT4('m','3','u','8'), BIT_M3U8, broken_m3u, sizeof broken_m3u - 1 },
    /* 13 */ {  1, 0, "other.mp3",  EXT4('m','p','3',0),   BIT_MP3,  "ID3",      3 },
    /* 14 */ {  1, 0, "chill.m3u",  EXT4('m','3','u',0),   BIT_M3U,  chill_m3u,  sizeof chill_m3u - 1 },
    /* Audio tracks: */
    /* 15 */ {  4, 0, "03 - intro.flac", EXT4('f','l','a','c'), BIT_FLAC, "fLaC", 4 },
    /* 16 */ {  4, 0, "04 - crash.flac", EXT4('f','l','a','c'), BIT_FLAC, "fLaC", 4 },
    /* 17 */ {  6, 0, "\xE3\x80\x8CDigital Twin\xE3\x80\x8D.flac", EXT4('f','l','a','c'), BIT_FLAC, "fLaC", 4 },
    /* 18 */ {  7, 0, "09 - One Day The Only Butterflies Left Will Be In Your Chest As You March Towards Your Death (feat. Amy L.flac", EXT4('f','l','a','c'), BIT_FLAC, "fLaC", 4 },
    /* 19 */ {  2, 0, "root_hit.mp3", EXT4('m','p','3',0), BIT_MP3, "ID3", 3 },
};
#define NTREE ((int)(sizeof tree / sizeof tree[0]))

struct vfs_ctx {
    int cwd;
    int cur_entry;
    int open_node;
    u32 seek_pos;
    int open_count;
};

static struct vfs_ctx brow_vfs = { 1, -1, -1, 0, 0 };
static struct vfs_ctx meng_vfs = { 0, -1, -1, 0, 0 };

static int node_depth(int n)
{
    int d = 0;
    while (n > 0 && tree[n].parent >= 0) {
        d++;
        n = tree[n].parent;
    }
    return d;
}

static u32 write_utf16_bom(u8 *dst, const char *s, u32 max_units)
{
    const u8 *p = (const u8 *)s;
    u32 u = 1;

    if (max_units < 2) return 0;
    dst[0] = 0xFF; dst[1] = 0xFE;
    while (*p && u + 1 < max_units) {
        u32 b0 = *p++, cp;
        if (b0 < 0x80) cp = b0;
        else if ((b0 & 0xE0) == 0xC0) { cp = ((b0 & 0x1F) << 6) | (*p++ & 0x3F); }
        else { cp = ((b0 & 0x0F) << 12) | ((u32)(p[0] & 0x3F) << 6) | (p[1] & 0x3F); p += 2; }
        dst[2 * u] = (u8)(cp & 0xFF);
        dst[2 * u + 1] = (u8)(cp >> 8);
        u++;
    }
    dst[2 * u] = 0;
    dst[2 * u + 1] = 0;
    return u + 1;
}

static int utf16_eq_utf8(const u8 *ubuf, const char *s, u32 max_chars)
{
    const u8 *p = (const u8 *)s;
    u32 i = 0;
    if (ubuf[0] != 0xFF || ubuf[1] != 0xFE) return 0;
    for (i = 0; i < max_chars && *p; i++) {
        u32 b0 = *p++, cp;
        u32 got = (u32)ubuf[2 + 2 * i] | ((u32)ubuf[3 + 2 * i] << 8);
        if (b0 < 0x80) cp = b0;
        else if ((b0 & 0xE0) == 0xC0) { cp = ((b0 & 0x1F) << 6) | (*p++ & 0x3F); }
        else { cp = ((b0 & 0x0F) << 12) | ((u32)(p[0] & 0x3F) << 6) | (p[1] & 0x3F); p += 2; }
        if (got != cp) return 0;
    }
    return ((u32)ubuf[2 + 2 * i] | ((u32)ubuf[3 + 2 * i] << 8)) == 0;
}

static long run_vfs(struct vfs_ctx *vc, u32 a1, u32 a2, u32 a3, u32 op)
{
    switch (op) {
    case OP_CD:
        if (a1 == ':') { vc->cwd = 0; vc->cur_entry = -1; return 1; }
        if (a1 == '|') {
            if (vc->cwd <= 0) return 0;
            vc->cwd = tree[vc->cwd].parent;
            vc->cur_entry = -1;
            return 1;
        }
        if (a1 == '/') {
            if (vc->cur_entry < 0 || !tree[vc->cur_entry].is_dir) return 0;
            vc->cwd = vc->cur_entry;
            vc->cur_entry = -1;
            return 1;
        }
        return 0;

    case OP_DIR: {
        int start, i;
        if (a1 == DIR_HEAD) start = 1;
        else if (a1 == DIR_NEXT) start = (vc->cur_entry >= 0) ? vc->cur_entry + 1 : NTREE;
        else return 0;

        for (i = start; i < NTREE; i++) {
            if (tree[i].parent != vc->cwd) continue;
            if (a3 == EXT_DIRS) {
                if (tree[i].is_dir) { vc->cur_entry = i; return 0x44495200; }
            } else {
                if (!tree[i].is_dir && (tree[i].ext_bit & a3)) {
                    vc->cur_entry = i;
                    return (long)tree[i].ext4;
                }
            }
        }
        vc->cur_entry = -1;
        return 0;
    }

    case OP_GETNAME: {
        u8 *buf = (u8 *)(unsigned long)a1;
        if (vc->cur_entry < 0) return 0;
        if (a2 == 0) {
            *(u32 *)buf = tree[vc->cur_entry].ext4;
            return 4;
        }
        return (long)write_utf16_bom(buf, tree[vc->cur_entry].name, a2);
    }

    case OP_OFFSET: {
        u8 *dir_buf = (u8 *)(unsigned long)a1;
        u32 *off_buf = (u32 *)(unsigned long)a2;
        if (a3 == 0) {
            if (vc->cur_entry < 0) return 0;
            memset(dir_buf, 0, 73);
            *(u32 *)dir_buf = (u32)vc->cwd;
            dir_buf[72] = (u8)node_depth(vc->cwd);
            off_buf[0] = 0xA0000000u | (u32)vc->cur_entry;
            off_buf[1] = (u32)(vc->cwd * 0x100 + vc->cur_entry);
            return 1;
        } else {
            int ent = (int)(off_buf[0] & 0xFFFFu);
            if ((off_buf[0] & 0xFFFF0000u) != 0xA0000000u || ent <= 0 || ent >= NTREE) return 0;
            vc->cwd = tree[ent].parent;
            vc->cur_entry = ent;
            return 1;
        }
    }

    case OP_OPEN:
        if (!a1 || vc->cur_entry < 0 || tree[vc->cur_entry].is_dir ||
            !utf16_eq_utf8((const u8 *)(unsigned long)a1, tree[vc->cur_entry].name, 255)) return 0;
        vc->open_node = vc->cur_entry;
        vc->seek_pos = 0;
        vc->open_count++;
        return 0x55;

    case OP_CLOSE:
        if (a1 == 0x55 && vc->open_node >= 0) {
            vc->open_node = -1;
            vc->open_count--;
            return 1;
        }
        return 0;

    case OP_SIZE:
        /* Verified in playlist.ap sub_480D45E8: handle is in $a2, $a3 == 0! */
        if (a2 != 0x55 || a3 != 0 || vc->open_node < 0 || a1 == 0) return 0;
        *(u32 *)(unsigned long)a1 = tree[vc->open_node].size;
        return 1;

    case OP_SEEK:
        if (a3 != 0x55 || vc->open_node < 0 || (a1 & 511u) != 0) return 0;
        vc->seek_pos = a1;
        return 1;

    case OP_READ: {
        /* Verified on hardware: OP_READ returns 1 (boolean), NOT byte count 512!
         * Fill trailing sector bytes past EOF with 0xAA garbage to prove bounded reads. */
        u8 *dst = (u8 *)(unsigned long)a1;
        u32 sz, n;
        if (a3 != 0x55 || a2 != 512u || vc->open_node < 0) return 0;
        sz = tree[vc->open_node].size;
        if (vc->seek_pos >= sz) return 0;
        n = (sz - vc->seek_pos < 512u) ? (sz - vc->seek_pos) : 512u;
        memset(dst, 0xAA, 512u);
        memcpy(dst, tree[vc->open_node].data + vc->seek_pos, n);
        vc->seek_pos += 512u;
        return 1;
    }
    }
    return 0;
}

static long mock_brow_fs(u32 ctx, u32 a1, u32 a2, u32 a3, u32 op)
{
    if (ctx != 0xB1u) return 0;
    return run_vfs(&brow_vfs, a1, a2, a3, op);
}

static long mock_meng_fs(u32 ctx, u32 a1, u32 a2, u32 a3, u32 op)
{
    if (ctx != 0xE1u) return 0;
    return run_vfs(&meng_vfs, a1, a2, a3, op);
}

fs_fn test_fs = mock_brow_fs;
fs_fn test_meng_fs = mock_meng_fs;

static u32 rng_state = 0x12345678u;
static u32 mock_rand(void)
{
    rng_state = rng_state * 1664525u + 1013904223u;
    return rng_state;
}
u32 (*test_meng_rand)(void) = mock_rand;

static u32 mock_step_dir(u32 mask, u16 n)
{
    u32 cmd = DIR_HEAD;
    if (n == 0) return 0;
    while (mock_brow_fs(test_bf->vfs_ctx, cmd, 0, mask, OP_DIR) != 0) {
        if (--n == 0) return 1;
        cmd = DIR_NEXT;
    }
    return 0;
}
u32 (*test_step_dir)(u32, u16) = mock_step_dir;

static u32 sim_meng_setloc(u8 *desc)
{
    u32 ext_now = 0;
    if (!mock_meng_fs(0xE1u, (u32)(unsigned long)(desc + 0x10), (u32)(unsigned long)(desc + 8), 1, OP_OFFSET)) {
        test_vs->magic = 0;
        return 0;
    }
    mock_meng_fs(0xE1u, (u32)(unsigned long)&ext_now, 0, 0, OP_GETNAME);
    if (ext_now != *(u32 *)(desc + 4)) {
        test_vs->magic = 0;
        return 0;
    }
    test_meng_eng[0x22] = desc[1];
    *(u32 *)(test_meng_eng + 0x28) = ext_now;
    *(u16 *)(test_meng_eng + 0x26) = 2;
    *(u16 *)(test_meng_eng + 0x24) = 2;

    if (test_vs->magic == M3UPLAY_MAGIC
            && *(u32 *)(desc + 8) == test_vs->cur_off[0]
            && *(u32 *)(desc + 12) == test_vs->cur_off[1]) {
        *(u16 *)(test_meng_eng + 0x26) = test_vs->count;
        *(u16 *)(test_meng_eng + 0x24) = test_vs->pos;
    } else {
        test_vs->magic = 0;
    }
    return 1;
}

static u32 fails;
static void check(int ok, const char *msg)
{
    printf("%s  %s\n", ok ? "PASS" : "FAIL", msg);
    if (!ok) fails++;
}

static void setup_playlists_hdr(u8 *hdr, struct brow_fsel *bf, u8 *tbuf, u8 nbufs[7][50])
{
    int i;
    memset(hdr, 0, 0x68);
    *(u16 *)(hdr + 0) = 2;          /* 2 subfolders: SubA, SubB */
    *(u16 *)(hdr + 2) = 5;          /* 5 files in Playlists/ */
    hdr[4] = 50;
    hdr[5] = 1;                     /* Playlists/ is at layer 1 */
    *(u8 **)(hdr + 8) = tbuf;
    write_utf16_bom(tbuf, "Playlists", 25);
    *(u16 *)(hdr + 0x62) = 1;       /* special_count = 1 to test skip = spec + dir_cnt = 3! */
    for (i = 0; i < 7; i++) {
        u8 *r = hdr + (i + 1) * 12;
        r[4] = 50;
        *(u8 **)(r + 8) = nbufs[i];
    }
    bf->vfs_ctx = 0xB1u;
    bf->mask = 0x7E191FA0u;
    bf->disk = 'H';
    bf->layer = 1;
    bf->dir_cnt = 2;
    bf->file_cnt = 5;
    bf->sel_item = 0;
}

int main(void)
{
    static struct vstate vs;
    static struct brow_work bw;
    static struct brow_fsel bf;
    static u8 brow_hdr[0x68] __attribute__((aligned(4)));
    static u8 title_buf[50];
    static u8 row_names[7][50];
    static u8 state[32];
    static u8 meng_eng[0x60];
    static u8 meng_sec[512] __attribute__((aligned(4)));
    static u8 arg0[0xA8] __attribute__((aligned(4)));
    u8 *desc = arg0 + 4;
    u32 rc, ext = 0;

    test_vs = &vs;
    test_bw = &bw;
    test_bf = &bf;
    test_brow_hdr = brow_hdr;
    test_meng_eng = meng_eng;
    test_meng_sec = meng_sec;
    *(u32 *)(meng_eng + 0x34) = 0xE1u;

    check(sizeof(struct vstate) <= M3U_STATE_CAPACITY, "sizeof(struct vstate) fits in the key driver resident padding");
    check(sizeof(struct brow_work) <= 0x390, "sizeof(struct brow_work) fits in 0x9FC15830..0x9FC15BC0");
    check(sizeof idk_m3u - 1 > 1024 && ((sizeof idk_m3u - 1) & 511u) != 0,
          "idk.m3u spans 3 sectors with non-512-aligned EOF");

    /* 1. Click idk.m3u in Playlists/ (with spec=1, dir_cnt=2 -> list_no=4 => file_idx=1) */
    brow_vfs.cwd = 1;
    setup_playlists_hdr(brow_hdr, &bf, title_buf, row_names);
    memcpy(brow_hdr + 3 * 12, "m3u\0", 4);
    write_utf16_bom(row_names[2], "idk.m3u", 25);
    *(u16 *)(state + 0x0E) = 4;     /* list_no = 1 (spec) + 2 (dirs) + 1 (idk.m3u) */
    *(u16 *)(state + 0x10) = 3;     /* sel = row 3 */
    memset(state + 0x14, 0, 3);

    rc = m3u_file_click(arg0, state);
    check(rc == 0,
          "clicking idk.m3u opens virtual folder directly (rc=0, no Play/Delete prompt)");
    check(bw.vd.magic == M3UVD_MAGIC && bw.vd.count == 9 && bw.vd.pl_layer == 1 && bw.vd.disk == 'H',
          "VD populated: magic=M3VD, count=9 tracks across 3 sectors, pl_layer=1");
    check(*(u16 *)(brow_hdr + 0) == 0 && *(u16 *)(brow_hdr + 2) == 9 && brow_hdr[5] == 2
          && bf.dir_cnt == 0 && bf.file_cnt == 9 && bf.layer == 2
          && utf16_eq_utf8(title_buf, "idk.m3u", 23)
          && state[0x16] == 1 && state[0x14] == 4 && state[0x15] == 3,
          "browser header updated to virtual folder (0 dirs, 9 files, layer=2, title='idk.m3u')");
    check(vs.magic == 0 && brow_vfs.open_count == 0,
          "opening virtual folder leaves mengine VS untouched and leaks 0 handles");

    /* 2. Render visible 7-row pages via m3u_vdir_get_items */
    {
        u8 req[4] = { 0, 7, 1, 0 };     /* count=7, start=1 */
        /* inside virtual folder special_count in Folders mode is 0; test hdr+0x62 = 0 */
        *(u16 *)(brow_hdr + 0x62) = 0;
        rc = m3u_vdir_get_items(req, brow_hdr + 12);
        check(rc == 1 && brow_vfs.open_count == 0, "m3u_vdir_get_items(1..7) succeeds in 1 pass");
        check(memcmp(brow_hdr + 1 * 12, "FLAC", 4) == 0
              && utf16_eq_utf8(row_names[0], "04 - crash.flac", 23),
              "row 1 formatted: ext='FLAC', name='04 - crash.flac'");
        check(memcmp(brow_hdr + 3 * 12, "FLAC", 4) == 0
              && utf16_eq_utf8(row_names[2], "\xE3\x80\x8CDigital Twin\xE3\x80\x8D.flac", 23),
              "row 3 formatted: UTF-8 CJK basename '「Digital Twin」.flac'");
        check(memcmp(brow_hdr + 4 * 12, "FLAC", 4) == 0
              && utf16_eq_utf8(row_names[3], "09 - One Day The Only B", 23),
              "row 4 formatted: 110-char LFN cleanly capped to 23 UTF-16LE chars + BOM + NUL");

        /* Page 2: items 8..11 (only items 8 and 9 exist; 10..11 must be cleared) */
        req[1] = 4; *(u16 *)(req + 2) = 8;
        rc = m3u_vdir_get_items(req, brow_hdr + 12);
        check(rc == 1
              && memcmp(brow_hdr + 2 * 12, "MP3\0", 4) == 0
              && utf16_eq_utf8(row_names[1], "root_hit.mp3", 23)
              && brow_hdr[3 * 12] == 0 && row_names[2][0] == 0,
              "m3u_vdir_get_items(8..11) renders root_hit.mp3 (#9) and zeroes trailing rows");
    }

    /* 3. Click track #1 inside virtual folder -> immediate launch (0x8001) */
    *(u16 *)(state + 0x0E) = 1;     /* list_no = 1 */
    *(u16 *)(state + 0x10) = 1;     /* sel = 1 */
    rc = m3u_file_click(arg0, state);
    check(rc == 0x8001u,
          "clicking track #1 inside virtual .m3u returns 0x8001 immediately");
    check(vs.magic == M3UPLAY_MAGIC && vs.count == 9 && vs.pos == 1
          && desc[0] == 'H' && desc[1] == 3
          && *(u32 *)(desc + 4) == EXT4('f','l','a','c')
          && (*(u32 *)(desc + 8) & 0xFFFFu) == 16
          && *(u16 *)(desc + 0x5C) == 9 && *(u16 *)(desc + 0x5E) == 1,
          "desc + VS armed for track #1 (04 - crash.flac, 1/9)");

#ifdef M30_COVERFLOW
    check(bw.vd.flow_pack==0x123b6d9 && vs.flow_pack==bw.vd.flow_pack,
          "prepared playlist publishes its cover pack on playback");
#endif
    /* 4. Click missing track #2 inside virtual folder -> skips forward to track #3! */
    *(u16 *)(state + 0x0E) = 2;
    *(u16 *)(state + 0x10) = 2;
    rc = m3u_file_click(arg0, state);
    check(rc == 0x8001u && vs.pos == 3 && (*(u32 *)(desc + 8) & 0xFFFFu) == 17,
          "clicking missing track #2 skips forward to UTF-8 track #3 (3/9)");

    /* 5. Re-select track #1 and verify mengine playback + stepping across all 3 sectors */
    *(u16 *)(state + 0x0E) = 1;
    m3u_file_click(arg0, state);
    rc = sim_meng_setloc(desc);
    check(rc == 1 && vs.magic == M3UPLAY_MAGIC
          && *(u16 *)(meng_eng + 0x26) == 9 && *(u16 *)(meng_eng + 0x24) == 1
          && meng_vfs.cur_entry == 16,
          "mengine set_location positions on 04 - crash.flac and sets 1/9");

    /* Resolve an ordinary playlist's covers through a separate card context. */
    struct vstate snapshot=vs;
    snapshot.flow_pack=0;
    struct flow_track_location location;
    check(flow_m3u_track(mock_brow_fs,0xB1u,&snapshot,3,&location) &&
          location.cursor[0]==17 && memcmp(location.extension,"flac",4)==0 &&
          !flow_m3u_track(mock_brow_fs,0xB1u,&snapshot,2,&location) &&
          flow_m3u_track(mock_brow_fs,0xB1u,&snapshot,9,&location) &&
          location.cursor[0]==19 && meng_vfs.cur_entry==16 &&
          vs.pos==1 && !brow_vfs.open_count,
          "artwork lookup resolves ordinary playlist tracks without advancing playback");

    meng_eng[0x39] = 0; /* Repeat Off */
    meng_eng[0x23] = 0; /* Shuffle Off */
    rc = m3u_meng_step(&ext, 0, 1);
    check(rc == 1 && meng_vfs.cur_entry == 17 && vs.pos == 3,
          "mengine next skips missing #2 and resolves UTF-8 track #3");
    rc = m3u_meng_step(&ext, 0, 1);
    check(rc == 1 && meng_vfs.cur_entry == 18 && vs.pos == 4,
          "mengine next resolves 110-char LFN track #4");
    rc = m3u_meng_step(&ext, 9, 0); /* get_byno(9) */
    check(rc == 1 && meng_vfs.cur_entry == 19 && vs.pos == 9 && ext == EXT4('m','p','3',0),
          "mengine get_byno(9) resolves final sector's unterminated '/Music/root_hit.mp3'");
    rc = m3u_meng_step(&ext, 0, 1);
    check(rc == 0 && vs.pos == 9, "mengine next at end with Repeat Off stops cleanly");
    meng_eng[0x39] = 2; /* Repeat All */
    rc = m3u_meng_step(&ext, 0, 1);
    check(rc == 1 && meng_vfs.cur_entry == 16 && vs.pos == 1,
          "mengine next with Repeat All wraps 9 -> 1");
    meng_eng[0x39] = 1; /* Repeat One */
    rc = m3u_meng_step(&ext, 0, 1);
    check(rc == 1 && vs.pos == 1, "mengine Repeat One holds track #1");
    meng_eng[0x39] = 2;
    rc = m3u_meng_step(&ext, 0, 2);
    check(rc == 1 && vs.pos == 9 && meng_vfs.cur_entry == 19,
          "mengine prev with Repeat All wraps 1 -> 9");

    /* 6. Reversible Shuffle in mengine */
    {
        u32 hist[6], i;
        vs.shuf_key = 0; vs.shuf_pos = 0;
        meng_eng[0x23] = 1; meng_eng[0x39] = 0;
        for (i = 0; i < 6; i++) {
            if (!m3u_meng_step(&ext, 0, 1)) break;
            hist[i] = (u32)meng_vfs.cur_entry;
        }
        check(i >= 5, "shuffle steps across multi-sector playlist");
        rc = m3u_meng_step(&ext, 0, 2);
        check(rc == 1 && (u32)meng_vfs.cur_entry == hist[i - 2],
              "shuffle prev reverses to exact prior shuffled track");
        meng_eng[0x23] = 0;
    }

    /* 7. Background VS isolation: press Back to Playlists/, browse chill.m3u without playing */
    m3u_meng_step(&ext, 5, 0); /* mengine is on track #5 of idk.m3u (node 15) */
    rc = m3u_vdir_dir_op(2, 0, brow_hdr);
    check(rc == 0xFFFEu && bw.vd.magic == 0 && bf.layer == 1 && brow_vfs.cwd == 1,
          "Back (cmd=2) clears VD, restores browser cwd to Playlists/, requests parent refresh");
    setup_playlists_hdr(brow_hdr, &bf, title_buf, row_names);
    memcpy(brow_hdr + 1 * 12, "m3u\0", 4);
    write_utf16_bom(row_names[0], "chill.m3u", 25);
    *(u16 *)(state + 0x0E) = 8; /* spec(1)+dirs(2)+file#5(chill.m3u) = 8 */
    *(u16 *)(state + 0x10) = 1;
    rc = m3u_file_click(arg0, state);
    check(rc == 0 && bw.vd.magic == M3UVD_MAGIC && bw.vd.count == 2,
          "browsing chill.m3u opens 2-track virtual folder in VD");
    {
        u8 req[4] = { 0, 2, 1, 0 };
        m3u_vdir_get_items(req, brow_hdr + 12);
    }
    /* Now background mengine advances idk.m3u #5 -> #6 (04 - crash.flac, node 16)! */
    rc = m3u_meng_step(&ext, 0, 1);
    check(rc == 1 && vs.count == 9 && vs.pos == 6 && meng_vfs.cur_entry == 16,
          "background mengine advances idk.m3u #5 -> #6 completely uncorrupted by browsing chill.m3u");

    /* 8. Return from music.ap (m3u_vdir_setloc + m3u_vdir_dir_op(0)) */
    *(u32 *)(desc + 8) = vs.cur_off[0];
    *(u32 *)(desc + 12) = vs.cur_off[1];
    rc = m3u_vdir_setloc(desc, 0);
    check(rc == 1 && bw.vd.magic == M3UVD_MAGIC && bw.vd.count == 9 && bf.sel_item == 6
          && utf16_eq_utf8(bw.vd.title, "idk.m3u", 23),
          "m3u_vdir_setloc restores idk.m3u virtual folder at track #6 when returning from music.ap");
    rc = m3u_vdir_dir_op(0, 0, brow_hdr);
    check(rc == 1 && *(u16 *)(brow_hdr + 2) == 9 && brow_hdr[5] == 2
          && utf16_eq_utf8(title_buf, "idk.m3u", 23),
          "m3u_vdir_dir_op(0) refreshes virtual folder header and title");

    /* Non-matching song in m3u_vdir_setloc delegates to stock (0xFFFE) and clears VD */
    *(u32 *)(desc + 8) = 0xDEADBEEFu;
    rc = m3u_vdir_setloc(desc, 0);
    check(rc == 0xFFFEu && bw.vd.magic == 0,
          "m3u_vdir_setloc returns 0xFFFE and clears VD for non-playlist track");

    /* 9. Empty .m3u -> 0x8018 (Empty folder); all-missing .m3u8 -> 0x8019 (Format error) */
    brow_vfs.cwd = 1;
    setup_playlists_hdr(brow_hdr, &bf, title_buf, row_names);
    memcpy(brow_hdr + 1 * 12, "m3u\0", 4);
    *(u16 *)(state + 0x0E) = 5; /* file #2 = empty.m3u */
    *(u16 *)(state + 0x10) = 1;
    rc = m3u_file_click(arg0, state);
    check(rc == 0x8018u && bw.vd.magic == 0,
          "clicking empty.m3u returns 0x8018 ('Empty folder!') and stays in Playlists/");

    memcpy(brow_hdr + 1 * 12, "M3U8", 4);
    *(u16 *)(state + 0x0E) = 6; /* file #3 = broken.m3u8 */
    rc = m3u_file_click(arg0, state);
    check(rc == 0 && bw.vd.magic == M3UVD_MAGIC && bw.vd.count == 2
          && utf16_eq_utf8(title_buf, "broken.m3u8", 23),
          "clicking broken.m3u8 opens its 2-entry virtual folder with VFS title");
    *(u16 *)(brow_hdr + 0x62) = 0;
    *(u16 *)(state + 0x0E) = 1;
    rc = m3u_file_click(arg0, state);
    check(rc == 0x8019u,
          "clicking unresolvable track inside broken.m3u8 returns 0x8019 ('File format not supported!')");

    /* 10. Root reset (cmd=3) + ordinary .mp3 click in normal folder */
    rc = m3u_vdir_dir_op(3, 0, brow_hdr);
    check(rc == 0xFFFDu && bw.vd.magic == 0, "m3u_vdir_dir_op(3) clears VD and delegates root reset");
    setup_playlists_hdr(brow_hdr, &bf, title_buf, row_names);
    memcpy(brow_hdr + 1 * 12, "mp3\0", 4);
    *(u16 *)(state + 0x0E) = 7; /* file #4 = other.mp3 */
    *(u16 *)(state + 0x10) = 1;
    rc = m3u_file_click(arg0, state);
    check(rc == 0xFFFEu && vs.magic == M3UPLAY_MAGIC,
          "clicking ordinary .mp3 returns 0xFFFE (stock Play/Delete prompt) without disarming background VS");

    /* 11. Selecting another song in mengine disarms VS */
    *(u32 *)(desc + 8) = 0xA000000Fu;
    rc = sim_meng_setloc(desc);
    check(rc == 1 && vs.magic == 0, "selecting non-playlist track in mengine disarms VS");
    check(brow_vfs.open_count == 0 && meng_vfs.open_count == 0,
          "0 leaked file handles in both browser and mengine VFS contexts");

    printf("%s\nm3uplay test done, failures %u\n", fails ? "ALL FAIL" : "ALL PASS", fails);
    return fails ? 1 : 0;
}

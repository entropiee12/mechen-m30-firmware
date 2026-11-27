/* Shared freestanding M3U line scanner and VFS path resolver.
 *
 * Included by m3uplay.c (browser.ap, with M3U_BROWSER defined) and
 * m3u_meng.c (mengine.ap) after defining FS_CALL and VFS_CTX.
 *
 * Critical hardware note (verified in playlist.ap sub_480D45E8 & sub_40214FE0):
 *   - OP_SIZE (0x9) takes (ctx, &u32_fsize, handle [$a2], 0, 9).
 *   - OP_READ (0xD) takes (ctx, buf, 512, handle [$a3], 0xD) and returns
 *     boolean 1 on success / 0 on failure -- NOT the byte count read!
 *   - Sector valid byte count MUST therefore be bounded by fsize - off.
 */
#ifndef M3U_RESOLVE_H
#define M3U_RESOLVE_H

#include "m3uplay.h"

#define NBUF_UNITS 80u   /* 160 bytes on stack: BOM + up to 78 UTF-16 units + NUL */

#ifndef PL_DIR
#define PL_DIR   (VS->pl_dir)
#define PL_OFF   (VS->pl_off)
#define PL_LAYER (VS->pl_layer)
#define LINE_BUF ((char *)VS->line)
#endif

#ifndef M3U_LOOKUP_ARGS
#define M3U_LOOKUP_ARGS
#define M3U_LOOKUP_PASS
#endif
#define M3U_VFS(...) vfs(M3U_LOOKUP_PASS __VA_ARGS__)

static __attribute__((noinline)) long vfs(M3U_LOOKUP_ARGS u32 a1, u32 a2, u32 a3, u32 op)
{
    return FS_CALL(VFS_CTX, a1, a2, a3, op);
}

static inline __attribute__((always_inline)) u32 fold_ascii(u32 c)
{
    return (c - 'A' < 26u) ? (c | 0x20u) : c;
}

#ifdef M3U_BROWSER
#define UTF8_ATTR __attribute__((noinline))
#else
#define UTF8_ATTR inline __attribute__((always_inline))
#endif

static UTF8_ATTR u32 utf8_next(const u8 **pp, const u8 *end)
{
    const u8 *p = *pp;
    u32 b0 = *p++, cp = b0;

    if (b0 >= 0x80u) {
        if ((b0 & 0xE0u) == 0xC0u && p < end) {
            cp = ((b0 & 0x1Fu) << 6) | (*p++ & 0x3Fu);
        } else if ((b0 & 0xF0u) == 0xE0u && p + 1 < end) {
            cp = ((b0 & 0x0Fu) << 12) | ((u32)(p[0] & 0x3Fu) << 6) | (p[1] & 0x3Fu);
            p += 2;
        } else if ((b0 & 0xF8u) == 0xF0u && p + 2 < end) {
            cp = '?';
            p += 3;
        }
    }
    *pp = p;
    return cp;
}

/* Match UTF-8 path component comp[0..clen-1] against an OP_GETNAME buffer
 * (FF FE BOM + UTF-16LE + 00 00, full BMP UTF-8 U+0000..U+FFFF). */
static int name_matches(const u8 *comp, u32 clen, const u8 *nbuf, u32 got_units)
{
    const u8 *p = comp;
    const u8 *end = comp + clen;
    u32 j;

    if (nbuf[0] != 0xFFu || nbuf[1] != 0xFEu) {
        return 0;
    }

    for (j = 0; j < NBUF_UNITS - 2; j++) {
        u32 ud = (u32)nbuf[2 + 2 * j] | ((u32)nbuf[3 + 2 * j] << 8);
        if (ud == 0) {
            return p == end;
        }
        if (p >= end || fold_ascii(ud) != fold_ascii(utf8_next(&p, end))) {
            return 0;
        }
    }
    return got_units >= NBUF_UNITS - 1;
}

#ifdef M3U_BROWSER
static __attribute__((noinline)) u32 m3u_scan_ex(u8 *sec, u32 want, u32 cnt, u8 *row_out)
#else
static __attribute__((noinline)) u32 m3u_scan_entry(M3U_LOOKUP_ARGS u8 *sec, u32 want)
#endif
{
    char *lbuf = LINE_BUF;
    long h;
    u32 fsize = 0, off, idx = 0, len = 0;
#ifdef M3U_BROWSER
    u32 res = row_out ? 0u : 0xFFFFFFFFu;
#else
    u32 res = 0xFFFFFFFFu;
#endif
    int skip = 0;

    if (!M3U_VFS((u32)PL_DIR, (u32)PL_OFF, 1u, OP_OFFSET)) {
        return res;
    }
    /* Use the named-open form used by the stock library builder. */
    if (!M3U_VFS((u32)sec, 255u, 0, OP_GETNAME)) {
        return res;
    }
    h = M3U_VFS((u32)sec, 0, 0, OP_OPEN);
    if (!h) {
        return res;
    }
    M3U_VFS((u32)&fsize, (u32)h, 0, OP_SIZE);

    for (off = 0; off < fsize; off += 512u) {
        u32 rem = fsize - off;
        u32 n = (rem < 512u) ? rem : 512u;
        u32 pos = 0;
        u32 lim = (off + n >= fsize) ? (n + 1u) : n;

        if (!M3U_VFS((u32)sec, 512u, (u32)h, OP_READ)) {
            goto out;
        }
        if (off == 0 && n >= 3 && sec[0] == 0xEFu && sec[1] == 0xBBu && sec[2] == 0xBFu) {
            pos = 3;
        }
#if defined(M3U_BROWSER) && defined(M30_COVERFLOW)
        if (!off && want==0xffffffffu && !row_out)
            VD->flow_pack=flow_header(sec+pos,n-pos);
#endif
        while (pos < lim) {
            u8 c = (pos < n) ? sec[pos] : (u8)'\n';
            pos++;
            if (c == '\r' || c == '\n') {
                if (!skip && len > 0) {
                    while (len > 0 && (lbuf[len - 1] == ' ' || lbuf[len - 1] == '\t')) {
                        len--;
                    }
                    if (len > 0) {
#ifdef M3U_BROWSER
                        if (row_out) {
                            if (idx >= want) {
                                format_vdir_row((const u8 *)lbuf, len, row_out);
                                row_out += 12;
                                if (--cnt == 0) {
                                    res = 1u;
                                    goto out;
                                }
                            }
                        } else
#endif
                        if (want == idx) {
                            lbuf[len] = 0;
                            res = len;
                            goto out;
                        }
                        idx++;
                    }
                }
                len = 0;
                skip = 0;
            } else if (!skip) {
                if (len == 0 && (c == ' ' || c == '\t')) {
                    /* skip leading whitespace */
                } else if ((len == 0 && c == '#') || len >= M3U_LINE_CAP - 1) {
                    skip = 1;
                } else {
                    lbuf[len++] = (char)c;
                }
            }
        }
    }
#ifdef M3U_BROWSER
    if (row_out) {
        while (cnt > 0) {
            u8 *dst = *(u8 **)(row_out + 8);
            if (dst) {
                dst[0] = 0;
            }
            row_out[0] = 0;
            row_out += 12;
            cnt--;
        }
        res = 1u;
    } else
#endif
    if (want == 0xFFFFFFFFu) {
        res = idx;
    }
out:
    M3U_VFS((u32)h, 0, 0, OP_CLOSE);
    return res;
}

static int m3u_resolve(M3U_LOOKUP_ARGS u32 len, u32 *out_ext, u8 *out_layer)
{
    u8 nbuf[NBUF_UNITS * 2];
    const u8 *local_line = (const u8 *)LINE_BUF;
    u32 i = 0, layer;

    if (len == 0 || len >= M3U_LINE_CAP) {
        return 0;
    }
    if (!M3U_VFS((u32)PL_DIR, (u32)PL_OFF, 1u, OP_OFFSET)) {
        return 0;
    }
    layer = PL_LAYER;

    if (len >= 2 && local_line[1] == ':') {
        i = 2;
    }
    if (i > 0 || local_line[0] == '/' || local_line[0] == '\\') {
        if (i < len && (local_line[i] == '/' || local_line[i] == '\\')) {
            i++;
        }
        M3U_VFS(':', 0, 0, OP_CD);
        layer = 0;
    }

    while (i < len) {
        u32 start = i, clen, cmd, mask;
        int is_dir, found = 0;

        while (i < len && local_line[i] != '/' && local_line[i] != '\\') {
            i++;
        }
        clen = i - start;
        is_dir = (i < len);
        if (is_dir) {
            i++;
            if (clen == 0 || (clen == 1 && local_line[start] == '.')) {
                continue;
            }
            if (clen == 2 && local_line[start] == '.' && local_line[start + 1] == '.') {
                if (layer == 0 || !((u8)M3U_VFS('|', 0, 0, OP_CD))) {
                    return 0;
                }
                layer--;
                continue;
            }
            if (layer >= 8) {
                return 0;
            }
            mask = EXT_DIRS;
        } else {
            if (clen == 0) {
                return 0;
            }
            mask = AUDIO_MASK;
        }

        cmd = DIR_HEAD;
        while (M3U_VFS(cmd, 0, mask, OP_DIR) != 0) {
            u32 got = (u32)M3U_VFS((u32)nbuf, NBUF_UNITS, 0, OP_GETNAME);
            if (name_matches(&local_line[start], clen, nbuf, got)) {
                found = 1;
                break;
            }
            cmd = DIR_NEXT;
        }
        if (!found) {
            return 0;
        }
        if (is_dir) {
            if (!((u8)M3U_VFS('/', 0, 0, OP_CD))) {
                return 0;
            }
            layer++;
        } else {
            *out_ext = 0;
            M3U_VFS((u32)out_ext, 0, 0, OP_GETNAME);
            *out_layer = (u8)layer;
            return 1;
        }
    }
    return 0;
}

#endif /* M3U_RESOLVE_H */

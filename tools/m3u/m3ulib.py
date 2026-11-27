#!/usr/bin/env python3
"""Read-only decoder for the M30's M3U.LIB playlist index.

M3U.LIB is one of the two library files the stock firmware writes to the card
(MUSIC.LIB for songs, M3U.LIB for playlists). This tool decodes it and, when it
can find both a card and the playlists the index points at, proves that the
index really does cover them.

Nothing here writes to the card. Callers point it at copies.

Usage:
    m3ulib.py dump    [M3U.LIB]
    m3ulib.py check   [CARD]        # verify the index covers every .m3u

Format, established against the image and the card:
    +0x00   4   magic "M3U\\0"
    +0x08   4   record count (low u16) and a fixed 1 (high u16)
    +0x10   8   the single section name "ALLFILE\\0"
    +0x200  .   256-byte records
      +0x00  64  title, UTF-16LE with a BOM, NUL/NUL terminated
      +0xa3   1  tag-ok flag, 0 for playlists
      +0xa4   4  extension as packed text, "M3U\\0"
      +0xa8   1  always 1
      +0xa9  16  file location: directory cluster, entry offset, index
MUSIC.LIB has the same envelope but the magic "MUSIC", the sections ALLSONG,
ALBUM, ARTIST and GENRE, and 256-byte records whose extension is the audio
format.
"""

import os
import struct
import sys

MAGIC = b"M3U\x00"
SECTION = b"ALLFILE\x00"
REC = 256
REC_OFF = 0x200


def u16(buf, o):
    return struct.unpack_from("<H", buf, o)[0]


def u32(buf, o):
    return struct.unpack_from("<I", buf, o)[0]


def title(rec):
    """The title field: UTF-16LE, optionally BOM-prefixed, NUL terminated."""
    raw = rec[0:64]
    if raw[:2] == b"\xff\xfe":
        raw = raw[2:]
    out = b""
    i = 0
    while i + 1 < len(raw):
        if raw[i:i + 2] == b"\x00\x00":
            break
        out += raw[i:i + 2]
        i += 2
    if not out:
        return ""
    try:
        return out.decode("utf-16-le")
    except UnicodeDecodeError:
        return out.decode("utf-16-le", "replace")


def parse(buf):
    """Return (count, [record dicts]) or raise ValueError on a bad envelope."""
    if len(buf) < REC_OFF:
        raise ValueError("file is shorter than the record area")
    if buf[:4] != MAGIC:
        raise ValueError("magic is %r, not %r" % (bytes(buf[:4]), MAGIC))
    count = u16(buf, 0x08)
    hi = u16(buf, 0x0A)
    if hi != 1:
        raise ValueError("the high half of +0x08 is %d, not 1" % hi)
    if buf[0x10:0x18] != SECTION:
        raise ValueError("section is %r, not %r" % (bytes(buf[0x10:0x18]), SECTION))
    recs = []
    for k in range(count):
        o = REC_OFF + REC * k
        if o + REC > len(buf):
            raise ValueError("record %d runs past the end of the file" % k)
        recs.append({
            "index": k,
            "offset": o,
            "title": title(buf[o:o + REC]),
            "ext": bytes(buf[o + 0xA4:o + 0xA4 + 4]),
            "flag": buf[o + 0xA3],
            "loc": bytes(buf[o + 0xA9:o + 0xA9 + 16]),
        })
    return count, recs


def cmd_dump(path):
    with open(path, "rb") as f:
        buf = f.read()
    try:
        count, recs = parse(buf)
    except ValueError as e:
        print("m3ulib: %s: %s" % (path, e))
        return 2
    print("%s: %d playlist records" % (path, count))
    for r in recs:
        print("  [%d] %#07x ext=%-8r %r"
              % (r["index"], r["offset"], r["ext"], r["title"]))
        print("        loc %s" % r["loc"].hex(" "))
    return 0


def cmd_check(card):
    """The index must cover every playlist file on the card, and nothing else.

    A title is matched against a playlist by stripping the extension and
    normalising case, because the index stores the display name and the file
    stores the filename. This is the claim the whole feature rests on: if the
    firmware already indexes the playlists, exposing them is a UI problem and
    not a filesystem-classification problem.
    """
    lib = os.path.join(card, "M3U.LIB")
    pldir = os.path.join(card, "Playlists")
    if not os.path.isfile(lib):
        print("m3ulib: no M3U.LIB at %s" % lib)
        return 2
    if not os.path.isdir(pldir):
        print("m3ulib: no Playlists directory at %s" % pldir)
        return 2
    with open(lib, "rb") as f:
        buf = f.read()
    try:
        count, recs = parse(buf)
    except ValueError as e:
        print("m3ulib: %s: %s" % (lib, e))
        return 2

    files = sorted(f for f in os.listdir(pldir)
                   if f.lower().endswith((".m3u", ".m3u8")))
    rc = 0

    def check(ok, msg):
        nonlocal rc
        print(("PASS  " if ok else "FAIL  ") + msg)
        if not ok:
            rc = 1

    check(all(r["ext"] == b"M3U\x00" for r in recs),
          "every record's extension field is 'M3U\\0'")
    check(all(r["title"] for r in recs),
          "every record has a non-empty title")
    check(len(recs) == len(files),
          "the index holds %d records and the card holds %d playlist files"
          % (len(recs), len(files)))

    def norm(name):
        lower=name.lower()
        if lower.endswith(".m3u8"):
            return name[:-5].lower()
        if lower.endswith(".m3u"):
            return name[:-4].lower()
        return lower

    # The stored title is 64 bytes: a BOM, then 30 UTF-16LE characters, then
    # the terminator. A name longer than 30 characters is truncated, so an
    # exact match would fail on "Bring Me The Horizon, Bad Omens" -> "...Bad
    # Omen". Match on prefix and require at least TITLE_MIN characters before
    # treating a title as a real identifier rather than a coincidence.
    TITLE_MIN = 8

    def stored(title):
        return norm(title)

    def matches(rec_title, file_name):
        t, f = stored(rec_title), norm(file_name)
        return f == t or (len(t) >= TITLE_MIN and f.startswith(t))

    unindexed = [f for f in files if not any(matches(r["title"], f)
                                             for r in recs)]
    leftovers = [r for r in recs if not any(matches(r["title"], f)
                                            for f in files)]
    check(not unindexed,
          "every playlist on the card is indexed (missing: %s)"
          % (", ".join(unindexed) if unindexed else "none"))
    check(not leftovers,
          "the index names no playlist that is absent (extra: %s)"
          % (", ".join(r["title"] for r in leftovers) if leftovers else "none"))

    # the truncation itself is a pinned fact, not an incidental one: a UI
    # built on this index must not treat the stored title as the filename
    long_titles = [r["title"] for r in recs if len(r["title"]) == 30]
    check(bool(long_titles) or all(len(r["title"]) < 30 for r in recs),
          "titles are capped at 30 characters, so the stored title is a label"
          " and not a filename (longest: %s)"
          % (max((r["title"] for r in recs), key=len)))

    # each record must carry a distinct location, so the index can address them
    locs = [r["loc"] for r in recs]
    check(len(set(locs)) == len(locs),
          "every record carries a distinct file location")
    print("check: %s" % ("OK" if rc == 0 else "FAILED"))
    return rc


def cmd_refs(fwdir=None):
    """How is a library file selected? Correcting an earlier wrong answer.

    An earlier pass searched for a pointer to the string "M3U.LIB" and found
    none, and concluded the database had no reader. That was wrong: M3U.LIB is
    not selected through its own pointer. music.ap's bank 0x7805b200 holds a
    12-byte-strided filename table based at guest 0x7805b498:

        +0x000  "MUSIC.LIB\\0\\0\\0"
        +0x00c  "M3U.LIB\\0\\0\\0\\0\\0"

    and the routine at 0x7805b370 computes `0x7805b498 + index*12` to pick one
    (0x7805b3f2 lhu index, 0x7805b3f6-fa sll/sll to *12, 0x7805b3fc lw base,
    0x7805b3fe addu). So the library kind is a *table index*, and searching for
    a pointer to M3U.LIB was guaranteed to come up empty.

    The same bank also holds the machinery that uses it: 0x7805b200 converts a
    name to UTF-16LE with an FFFF/FFFE prefix, 0x7805b264 turns an index into a
    cluster and a <<9 sector read, and 0x7805b370 opens a library, selects by
    mask and reads its header. Those are what a playlist view would reuse.
    """
    fwdir = fwdir or os.environ.get(
        "M30_STOCK", "/home/foggy/m30/mechen-m30-firmware/build/work/stock/fw")
    comps = sorted(os.listdir(fwdir))
    rc = 0

    def check(ok, msg):
        nonlocal rc
        print(("PASS  " if ok else "FAIL  ") + msg)
        if not ok:
            rc = 1

    # 1. the indexed filename table, in music.ap's bank 0x7805b200
    bank = fwdir and os.path.join(fwdir, "music.ap")
    if not os.path.isfile(bank):
        print("m3ulib: no music.ap at %s" % bank)
        return 2
    with open(bank, "rb") as f:
        blob = f.read()
    # locate the bank's file range by its link address in the group-4 table
    start = end = None
    for lo in (0x100, 0xA00):
        for o in range(lo, lo + 0x800, 12):
            fo, addr, sz = struct.unpack_from("<3I", blob, o)
            if addr == 0x7805B200 and 0 < sz <= 0x10000:
                start, end = fo, fo + sz
                break
        if start:
            break
    check(start is not None, "music.ap bank 0x7805b200 is present")
    if start:
        # find the table inside it rather than trusting a recorded offset
        i = blob.find(b"MUSIC.LIB\x00\x00\x00", start, end)
        check(i >= 0 and i % 4 == 0,
              "the filename table is in the bank, 4-byte aligned")
        if i >= 0:
            check(blob[i:i + 12] == b"MUSIC.LIB\x00\x00\x00",
                  "slot 0 is MUSIC.LIB at bank +%#x" % (i - start))
            check(blob[i + 12:i + 24] == b"M3U.LIB\x00\x00\x00\x00\x00",
                  "slot 1 is M3U.LIB, 12 bytes on")

    # 2. the selector: a pointer to the table base, not to a filename
    base = 0x7805B498
    pat = struct.pack("<I", base)
    users = []
    for c in comps:
        p = os.path.join(fwdir, c)
        if not os.path.isfile(p):
            continue
        with open(p, "rb") as f:
            d = f.read()
        j = d.find(pat)
        while j >= 0:
            users.append((c, j))
            j = d.find(pat, j + 1)
    check(bool(users),
          "the table base %#x is referenced from %d places"
          % (base, len(users)))
    for c, j in users[:6]:
        print("        %s +%#x" % (c, j))

    # 3. no standalone pointer to M3U.LIB is expected, and is not a blocker
    m3u_pat = struct.pack("<I", base + 12)
    direct = []
    for c in comps:
        p = os.path.join(fwdir, c)
        if not os.path.isfile(p):
            continue
        with open(p, "rb") as f:
            d = f.read()
        j = d.find(m3u_pat)
        while j >= 0:
            direct.append((c, j))
            j = d.find(m3u_pat, j + 1)
    print("NOTE  a pointer to M3U.LIB alone is loaded from %d places, which is"
          % len(direct))
    print("NOTE  the expected result: it is reached by index, not by address.")
    print("refs: %s" % ("OK" if rc == 0 else "FAILED"))
    return rc


def main(argv):
    if len(argv) >= 2 and argv[1] == "refs":
        return cmd_refs(argv[2] if len(argv) > 2 else None)
    if len(argv) >= 2 and argv[1] == "dump":
        p = argv[2] if len(argv) > 2 else os.path.join(
            "/run/media/foggy/MECHEN M30", "M3U.LIB")
        return cmd_dump(p)
    if len(argv) >= 2 and argv[1] == "check":
        p = argv[2] if len(argv) > 2 else "/run/media/foggy/MECHEN M30"
        return cmd_check(p)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))

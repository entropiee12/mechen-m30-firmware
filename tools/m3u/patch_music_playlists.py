"""Expose the stock Playlists row in both Music category menus."""
from pathlib import Path
import struct

from patch_m3uvis import DRV_SLOTS, MUSIC_MASK

menu = Path("music.mcg")
data = bytearray(menu.read_bytes())
descriptor = struct.Struct("<HHIHH")
row = struct.Struct("<HHIIII")
assert struct.unpack_from("<HH", data, 12) == (16, 60)
assert data[0xeac:0xeb5] == b"ACTMCGGM0"
label, count, rows, parent, selected = descriptor.unpack_from(data, 16)
_, extended_count, extended_rows, _, _ = descriptor.unpack_from(data, 16 + descriptor.size)
assert (label, count, rows, extended_count, extended_rows) == (10, 4, 736, 8, 816)
assert data[rows:rows + count * row.size] == data[extended_rows:extended_rows + count * row.size]
assert row.unpack_from(data, extended_rows + count * row.size) == (
    0x7fff, 63, 0, 0x40294cf9, 0, 0x402d4c01,
)
descriptor.pack_into(data, 16, label, count + 1, extended_rows, parent, selected)

indexer = Path("playlist.ap")
code = bytearray(indexer.read_bytes())
# The driver chooses mask bit 30 minus its extension slot. Use the same
# playlist slots as Folders, and keep them out of the audio library pass.
playlist_mask = sum(1 << (30 - slot) for slot, _, _ in DRV_SLOTS)
assert struct.unpack_from("<III", code, 0x2a20) == (MUSIC_MASK, 0xbfc166c8, 0x00080400)
assert struct.unpack_from("<I", code, 0x92f8)[0] == MUSIC_MASK
audio_mask = MUSIC_MASK & ~playlist_mask
struct.pack_into("<III", code, 0x2a20, audio_mask, 0xbfc166c8, playlist_mask)
struct.pack_into("<I", code, 0x92f8, audio_mask)

# The stock suffix search starts four UTF-16 characters from the end.
# M3U8 needs five. The helper rejoins at the original store of that offset.
start = 0x4920
site = 0x4534
blob = Path("index_title.bin").read_bytes()
assert struct.pack("<III", 0x4400, 0x40154c00, 0x800) in code[:0xc00]
assert code[site:site + 4] == bytes.fromhex("484240c1")
assert 0 < len(blob) <= 0x4c00 - start
assert not any(code[start:start + len(blob)])
delta = (start - site - 2) // 2
assert 0 <= delta < 1024
struct.pack_into("<H", code, site, 0x1000 | delta)
code[start:start + len(blob)] = blob

menu.write_bytes(data)
indexer.write_bytes(code)

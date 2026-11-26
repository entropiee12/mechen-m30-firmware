"""Install the slot-37 draw callback and a 116-byte cache in loaded driver padding."""
import hashlib
from pathlib import Path
import struct
import sys

source, blob, out = map(Path, sys.argv[1:4])
data = bytearray(source.read_bytes())
code = blob.read_bytes()
assert hashlib.sha256(data).hexdigest() == '011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a'
assert struct.unpack_from('<I', data, 0xdf1c)[0] == 0xbfc0a9ad
assert 0 < len(code) <= 0xe400-0xe274 and not any(data[0xe274:0xe600])
assert not any(data[0x1474:0x14f8])
assert struct.unpack_from("<I",data,0x107b4)[0]==0xbfc0aa19
wall=int(sys.argv[4],16)|1
renderer=Path('walldraw.bin').read_bytes()
clear=int(sys.argv[5],16)|1
assert struct.unpack_from('<HH',data,0x10e)==(0x8038,0x8040)
assert 0<len(renderer)<=0x568 and not any(data[0xa098:0xa098+len(renderer)])
assert all(0x261c9698<=entry<0x261c9698+len(renderer) for entry in (wall,clear))
data[0xa098:0xa098+len(renderer)]=renderer
assert struct.unpack_from('<I',data,0x1548)[0]==0x36119d81
struct.pack_into('<I',data,0x1548,clear)
struct.pack_into("<I",data,0x107b4,wall)
assert 0x1474+132 <= 0x1200+0x2fc
data[0xe274:0xe274+len(code)] = code
struct.pack_into('<I', data, 0xdf1c, 0x263c9875)
struct.pack_into('<I', data, 0x1474, 0x3154484d)
# In group B, bank 16 follows PictureBox bank 15 by 0x800 file bytes
# and 0x40000 virtual bytes. Both use the 0x9400 window.
assert struct.unpack_from('<HH',data,0x11e)==(0x8078,0x8080)
helper=Path('listicon.bin').read_bytes()
assert 0<len(helper)<=0x400 and not any(data[0xea00:0xea00+len(helper)])
data[0xea00:0xea00+len(helper)]=helper
# Tail of bank 15, following showicon. The text reader also handles LCD
# readback, so its replacement delegates every non-SD-image mode.
text = Path('textback.bin').read_bytes()
entry = int(sys.argv[6], 16) | 1
assert 0 < len(text) <= 0x200 and not any(data[0xe400:0xe600])
assert 0x263c9a00 <= (entry & ~1) < 0x263c9a00 + len(text)
assert struct.unpack_from('<II',data,0x10788) == (0x9fc0bf88,0xbfc09ce5)
data[0xe400:0xe400+len(text)] = text
struct.pack_into('<I',data,0x1078c,entry)
if len(sys.argv)>7 and sys.argv[7]:
    header = int(sys.argv[7],16)|1
    assert 0x263c9a00 <= header < 0x263c9a00 + len(text)
    struct.pack_into('<I',data,0x107b4,header)
out.write_bytes(data)

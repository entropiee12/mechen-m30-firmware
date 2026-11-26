"""Install SD import at Config startup and SD export at Settings exit."""
from pathlib import Path
import struct
import sys

for name, offset, bank, start in [
    ("config", 0x9e00, 0x71292500, 0x100),
    ("setting", 0xe700, 0x48594400, 0x300),
]:
    path = Path(name + ".ap")
    data = bytearray(path.read_bytes())
    blob = Path("native_" + name + ".bin").read_bytes()
    assert struct.pack("<III", offset, bank, 0x800) in data[:0xc00]
    assert 0 < len(blob) <= 0x800 - start
    assert not any(data[offset + start:offset + start + len(blob)])
    data[offset + start:offset + start + len(blob)] = blob
    if name == "setting":
        entry = int(sys.argv[1], 16)
        assert bank + start <= entry < bank + start + len(blob)
        # Replace only the Settings exit VM write, preserving the call arguments.
        assert data[0x1a9a:0x1aa8] == bytes.fromhex("0db28267006d386e136f0bb2c0ea")
        assert data[0x1acc:0x1ad4] == struct.pack("<II", 0x9fc15408, 0xbfc16585)
        word = entry >> 2
        struct.pack_into("<HHH", data, 0x1aa2, 0x1800 | ((word >> 21) & 31) |
                         (((word >> 16) & 31) << 5), word & 65535, 0x6f13)
    path.write_bytes(data)

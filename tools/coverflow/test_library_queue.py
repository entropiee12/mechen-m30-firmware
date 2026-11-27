"""Check the read-only Cover Flow queue lookup against a generated MUSIC.LIB."""
import ctypes as ct
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
LIBRARY = HERE.parent / "library"


class Location(ct.Structure):
    _fields_ = [("directory", ct.c_ubyte * 75),
                ("cursor", ct.c_ubyte * 8),
                ("extension", ct.c_ubyte * 4)]


def main():
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        raw, indexed = work / "raw.lib", work / "MUSIC.LIB"
        subprocess.run([sys.executable, LIBRARY / "mklib.py", "512", raw], check=True)
        subprocess.run([sys.executable, LIBRARY / "libmodel.py", raw, indexed], check=True)
        data = bytearray(indexed.read_bytes())
        album_index = struct.unpack_from("<H", data, 0x2c + 20)[0]
        assert album_index > 256

        binary = work / "queue.so"
        subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC",
                        HERE / "library_queue.c", "-o", binary], check=True)
        read_fn = ct.CFUNCTYPE(ct.c_int, ct.c_void_p, ct.c_uint, ct.c_void_p)
        resolve = ct.CDLL(str(binary)).flow_library_window
        resolve.argtypes = [read_fn, ct.c_void_p, ct.c_uint, ct.c_uint16,
                            ct.c_uint16, ct.c_uint16,
                            ct.POINTER(ct.c_uint16), ct.POINTER(Location)]
        resolve.restype = ct.c_uint
        sectors = len(data) // 512
        reads = []

        @read_fn
        def read(_ctx, sector, destination):
            reads.append(sector)
            if sector >= sectors:
                return 0
            ct.memmove(destination, bytes(data[sector * 512:(sector + 1) * 512]), 512)
            return 1

        locations = (Location * 5)()
        positions = (ct.c_uint16 * 5)(2, 1, 3, 2, 0)
        # Begin at the last entry in a sector and leave a hole at the start of
        # the next one. Verify repeat requests, a missing rank, and queue end.
        start = album_index * 512 + 255 * 2
        struct.pack_into("<4H", data, start, 9, 0xffff, 14, 11)
        assert resolve(read, None, sectors, album_index, 255, 258, positions, locations) == 0b01011
        for slot, record_id in ((0, 14), (1, 9), (3, 14)):
            record = data[512 + record_id * 256:512 + (record_id + 1) * 256]
            assert bytes(locations[slot].directory) == record[0xa9:0xf4]
            assert bytes(locations[slot].cursor) == record[0xf4:0xfc]
            assert bytes(locations[slot].extension) == record[0xa4:0xa8]
        assert reads[:3] == [0, album_index, album_index + 1]
        assert max(reads) < sectors

        # Runtime sentinel bounds are relative to the index sector, not file start.
        assert resolve(read, None, sectors, album_index, 255, 0xffff,
                       positions, locations) == 0b01111

        # A truncated library must never read beyond its final sector.
        reads.clear()
        assert resolve(read, None, album_index + 1, album_index, 255, 258,
                       positions, locations) == 0
        assert reads == [0]
        data[510] = 0
        assert resolve(read, None, sectors, album_index, 255, 258, positions, locations) == 0
    print("PASS: read-only album queue lookup, holes, sector boundary, and truncation")


if __name__ == "__main__":
    main()

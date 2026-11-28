from __future__ import annotations

import hashlib
import struct
import unittest

from m3u_library_evidence_probe import (
    M3U_HEADER_BYTES,
    inspect_m3u_library,
)


def make_m3u_library() -> bytes:
    data = bytearray(M3U_HEADER_BYTES + 0x400)
    data[:4] = b"M3U\x00"
    data[0x10:0x17] = b"ALLFILE"
    struct.pack_into("<H", data, 0x08, 0)
    struct.pack_into("<H", data, 0x0A, 1)
    data[M3U_HEADER_BYTES:] = bytes(value % 256 for value in range(len(data) - M3U_HEADER_BYTES))
    return bytes(data)


class M3uLibraryEvidenceProbeTests(unittest.TestCase):
    def test_probe_accepts_header_and_uninterpreted_payload(self):
        data = make_m3u_library()
        summary = inspect_m3u_library(data, expected_sha256=None)

        self.assertEqual(summary["bytes"], len(data))
        self.assertEqual(summary["payload_bytes"], 0x400)
        self.assertEqual(summary["entry_count_field"], 0)
        self.assertEqual(summary["record_start_sector"], 1)

    def test_probe_rejects_a_hash_mismatch_before_decoding(self):
        data = make_m3u_library()
        with self.assertRaises(ValueError):
            inspect_m3u_library(data, expected_sha256=hashlib.sha256(b"other").hexdigest())

    def test_probe_rejects_nonzero_entry_count(self):
        data = bytearray(make_m3u_library())
        struct.pack_into("<H", data, 0x08, 1)
        with self.assertRaises(ValueError):
            inspect_m3u_library(bytes(data), expected_sha256=None)


if __name__ == "__main__":
    unittest.main()

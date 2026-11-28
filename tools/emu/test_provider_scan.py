import contextlib
import io
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from provider_scan import main, scan_driver_directory, scan_file, scan_tree


class ProviderScanTests(unittest.TestCase):
    def test_default_pointer_set_includes_resident_forwarder_target(self) -> None:
        from provider_scan import OBSERVED_POINTERS

        self.assertEqual(
            OBSERVED_POINTERS[0x164490A1],
            "A17 + 0x0a1 (resident forwarder target)",
        )

    def test_scan_file_reports_all_little_endian_occurrences(self) -> None:
        pointer = 0x26089471
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "drv_ui.drv"
            path.write_bytes(b"x" + struct.pack("<I", pointer) + b"y" + struct.pack("<I", pointer))
            result = scan_file(path, (pointer,))
        self.assertEqual([item.offset for item in result[pointer]], [1, 6])

    def test_directory_scan_merges_driver_files(self) -> None:
        first = 0x164C9001
        second = 0x26409401
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "a.drv").write_bytes(struct.pack("<I", first))
            (root / "b.drv").write_bytes(struct.pack("<I", second))
            (root / "ignored.bin").write_bytes(struct.pack("<I", first))
            result = scan_driver_directory(root)
        self.assertEqual(len(result[first]), 1)
        self.assertEqual(len(result[second]), 1)
        self.assertEqual(result[first][0].path, str(root / "a.drv"))

    def test_tree_scan_includes_nested_non_driver_members(self) -> None:
        pointer = 0x164C9001
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            nested = root / "nested"
            nested.mkdir()
            path = nested / "music.ap"
            path.write_bytes(struct.pack("<I", pointer))
            result = scan_tree(root)
        self.assertEqual(len(result[pointer]), 1)
        self.assertEqual(result[pointer][0].path, str(path))

    def test_tree_scan_can_exclude_known_consumer(self) -> None:
        pointer = 0x164490A1
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "drv_ui.drv").write_bytes(struct.pack("<I", pointer))
            (root / "kernel.drv").write_bytes(b"clean")
            result = scan_tree(root, (pointer,), ("drv_ui.drv",))
        self.assertNotIn(pointer, result)

    def test_custom_pointer_set_is_supported(self) -> None:
        pointer = 0xBFC06D6C
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / "ker_xsys.bin"
            path.write_bytes(b"x" + struct.pack("<I", pointer))
            result = scan_tree(root, (pointer,))
        self.assertEqual([item.offset for item in result[pointer]], [1])

    def test_cli_require_zero_rejects_a_present_pointer(self) -> None:
        pointer = 0xBFC06D6C
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "ker_xsys.bin").write_bytes(struct.pack("<I", pointer))
            argv = [
                "provider_scan.py",
                str(root),
                "--all-files",
                "--pointer",
                hex(pointer),
                "--require-zero",
            ]
            with patch.object(sys, "argv", argv):
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(main(), 1)

    def test_cli_require_zero_accepts_excluded_consumer(self) -> None:
        pointer = 0x164490A1
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "drv_ui.drv").write_bytes(struct.pack("<I", pointer))
            argv = [
                "provider_scan.py",
                str(root),
                "--all-files",
                "--exclude-name",
                "drv_ui.drv",
                "--require-zero",
            ]
            with patch.object(sys, "argv", argv):
                self.assertEqual(main(), 0)


if __name__ == "__main__":
    unittest.main()

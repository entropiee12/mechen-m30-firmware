import contextlib
import io
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from compare_firmware_versions import compare_versions, main


class FirmwareVersionComparisonTests(unittest.TestCase):
    def test_reports_sorted_versions_hashes_and_consumer_exclusion(self) -> None:
        pointer = 0x164490A1
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for version in ("v1.292.00", "v1.101.11"):
                fw = root / version / "fw"
                fw.mkdir(parents=True)
                (fw / "drv_ui.drv").write_bytes(struct.pack("<I", pointer))
                (fw / "kernel.drv").write_bytes(b"kernel")
            reports = compare_versions(root, (pointer,), ("drv_ui.drv", "kernel.drv"))
        self.assertEqual([report.version for report in reports], ["v1.101.11", "v1.292.00"])
        self.assertEqual(reports[0].module_hashes["kernel.drv"], reports[1].module_hashes["kernel.drv"])
        self.assertEqual(reports[0].pointer_counts[pointer], 0)

    def test_reports_provider_occurrence_when_it_is_not_the_consumer(self) -> None:
        pointer = 0x164C9001
        with tempfile.TemporaryDirectory() as tmp:
            fw = Path(tmp) / "v1" / "fw"
            fw.mkdir(parents=True)
            (fw / "drv_ui.drv").write_bytes(b"consumer")
            (fw / "kernel.drv").write_bytes(struct.pack("<I", pointer))
            reports = compare_versions(Path(tmp), (pointer,))
        self.assertEqual(reports[0].pointer_counts[pointer], 1)

    def test_cli_require_zero_rejects_provider_occurrence(self) -> None:
        pointer = 0x164C9001
        with tempfile.TemporaryDirectory() as tmp:
            fw = Path(tmp) / "v1" / "fw"
            fw.mkdir(parents=True)
            (fw / "kernel.drv").write_bytes(struct.pack("<I", pointer))
            argv = [
                "compare_firmware_versions.py",
                tmp,
                "--pointer",
                hex(pointer),
                "--require-zero",
            ]
            with patch.object(sys, "argv", argv):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(main(), 1)


if __name__ == "__main__":
    unittest.main()

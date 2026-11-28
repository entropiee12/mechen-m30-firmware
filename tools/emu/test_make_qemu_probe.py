import struct
import tempfile
import unittest
from pathlib import Path

from make_qemu_probe import PATCHES, make_probe


ROOT = Path(__file__).parents[2]
AP = ROOT / "build/qemu-minimal-safe/work/verify/fw/music.ap"


@unittest.skipUnless(AP.exists(), "extracted music.ap is not present")
class QemuProbeTests(unittest.TestCase):
    def test_probe_patches_only_verified_literals(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            make_probe(AP, out)
            lines = (out / "probe-patches.txt").read_text().splitlines()
            self.assertEqual(len(lines), len(PATCHES))
            self.assertIn("0x40d5516c 0xbfc16405 -> 0x00700001", lines)
            self.assertIn("0x40d54f18 0x485547ed -> 0x007000a1", lines)
            self.assertTrue((out / "qemu_probe.S").exists())
            self.assertTrue((out / "qemu_probe_start.S").exists())
            start = (out / "qemu_probe_start.S").read_text()
            self.assertIn("jalx\t0x40d54f2c", start)
            self.assertIn("jalx\t0x40d54d80", start)
            self.assertIn("PROBE_MODE", start)
            self.assertIn("SEQUENCE_MODE", start)
            self.assertIn("seq_loop:", start)
            self.assertIn("0x10003fdc", start)
            self.assertIn(".probe_result", start)

    def test_probe_keeps_scroll_code_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            make_probe(AP, out)
            bank = out / "banks/034_40d54c00.bin"
            data = bank.read_bytes()
            self.assertEqual(data[0x478:0x47C], bytes.fromhex("04 f0 05 6a"))


if __name__ == "__main__":
    unittest.main()

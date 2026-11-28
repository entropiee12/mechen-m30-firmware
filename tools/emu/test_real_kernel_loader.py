import unittest
from pathlib import Path

from yqhx import YqhxImage, YqhxSegment


REPO = Path(__file__).resolve().parents[2]
KERNEL = REPO / "analysis" / "community" / "fw" / "kernel.drv"


@unittest.skipUnless(KERNEL.is_file(), "archived kernel.drv is unavailable")
class RealKernelLoaderTests(unittest.TestCase):
    def test_real_kernel_header_produces_read_only_load_plan(self) -> None:
        image = YqhxImage.load(KERNEL)

        self.assertEqual(image.entry, 0)
        self.assertEqual(
            image.segments[0],
            YqhxSegment(0x200, 0xB00, 0xBFC08000),
        )
        self.assertEqual(
            image.segments[3],
            YqhxSegment(0xD00, 0x8C, 0x9FC1A400),
        )
        self.assertEqual(image.segments[1].length, 0)
        self.assertEqual(image.segments[2].length, 0)
        self.assertEqual((image.bss_address, image.bss_size), (0x9FC1A48C, 0x7F4))
        self.assertEqual(
            image.load_plan(),
            (
                ("segment", 0xBFC08000, 0xB00),
                ("segment", 0x9FC1A400, 0x8C),
                ("bss-zero", 0x9FC1A48C, 0x7F4),
            ),
        )


if __name__ == "__main__":
    unittest.main()

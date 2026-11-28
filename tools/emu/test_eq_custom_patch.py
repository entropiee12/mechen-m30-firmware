import struct
import sys
import tempfile
import unittest
from pathlib import Path


TOOLS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_ROOT))

from patch_eq_custom import (  # noqa: E402
    BANK_VA,
    BANK_FILE_OFFSET,
    BANK_SIZE,
    CURVE_FILE_OFFSET,
    CUSTOM_GAINS,
    EXPECTED_SOURCE_POINTER,
    SOURCE_POINTER_FILE_OFFSET,
    patch,
)


ROOT = Path(__file__).parents[2]
SOURCE = ROOT / "analysis" / "community" / "fw" / "music.ap"


class CustomEqPatchTests(unittest.TestCase):
    def test_changes_only_copy_source_pointer_and_seven_runtime_gains(self):
        original = SOURCE.read_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "music.ap"
            patch(SOURCE, output)
            changed = output.read_bytes()

        self.assertEqual(len(changed), len(original))
        differences = {i for i, pair in enumerate(zip(original, changed)) if pair[0] != pair[1]}
        patch_ranges = set(range(SOURCE_POINTER_FILE_OFFSET, SOURCE_POINTER_FILE_OFFSET + 4))
        patch_ranges.update(range(CURVE_FILE_OFFSET, CURVE_FILE_OFFSET + len(CUSTOM_GAINS)))
        self.assertTrue(differences)
        self.assertLessEqual(differences, patch_ranges)
        self.assertEqual(
            changed[CURVE_FILE_OFFSET : CURVE_FILE_OFFSET + len(CUSTOM_GAINS)],
            bytes(value & 0xFF for value in CUSTOM_GAINS),
        )
        self.assertEqual(
            struct.unpack_from("<I", original, SOURCE_POINTER_FILE_OFFSET)[0],
            EXPECTED_SOURCE_POINTER,
        )
        self.assertEqual(
            struct.unpack_from("<I", changed, SOURCE_POINTER_FILE_OFFSET)[0],
            BANK_VA + CURVE_FILE_OFFSET - BANK_FILE_OFFSET,
        )
        self.assertEqual(
            tuple(value - 256 if value >= 128 else value for value in changed[CURVE_FILE_OFFSET:CURVE_FILE_OFFSET + 7]),
            CUSTOM_GAINS,
        )
        # The candidate curve must stay inside the same loaded segment.
        self.assertLess(CURVE_FILE_OFFSET + len(CUSTOM_GAINS), BANK_FILE_OFFSET + BANK_SIZE)

    def test_refuses_nonzero_curve_storage(self):
        original = bytearray(SOURCE.read_bytes())
        original[CURVE_FILE_OFFSET] = 0x7F
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "modified-music.ap"
            output = Path(temporary) / "out.ap"
            source.write_bytes(original)
            with self.assertRaises(SystemExit):
                patch(source, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()

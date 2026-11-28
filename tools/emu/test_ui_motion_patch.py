import struct
import sys
import tempfile
import unittest
from pathlib import Path


TOOLS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_ROOT))

from patch_ui_motion import (  # noqa: E402
    BANK_ADDR,
    BANK_SIZE,
    PATCHED_INSN,
    STOCK_INSN,
    TIMER_OFFSET,
    patch_ap,
)


ROOT = Path(__file__).parents[2]
FW = ROOT / "analysis" / "community" / "fw"


class UiMotionPatchTests(unittest.TestCase):
    def test_real_browser_and_music_overlays_change_only_the_80_to_50_literal(self):
        for member in ("browser.ap", "music.ap"):
            source = FW / member
            original = source.read_bytes()
            with tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary) / member
                patch_ap(source, output)
                patched = output.read_bytes()

            self.assertEqual(len(patched), len(original))
            differences = [
                index
                for index, (before, after) in enumerate(zip(original, patched))
                if before != after
            ]
            # The MIPS16 opcode keeps its high byte (0x6d); only the
            # immediate low byte changes from 0x50 to 0x32.
            self.assertEqual(len(differences), 1)

            bank_offsets = []
            for entry in range(0x100, 0x1000, 12):
                file_offset, address, size = struct.unpack_from("<III", original, entry)
                if address == BANK_ADDR and size == BANK_SIZE:
                    bank_offsets.append(file_offset)
            self.assertEqual(len(bank_offsets), 1)
            timer_file_offset = bank_offsets[0] + TIMER_OFFSET
            self.assertEqual(differences, [timer_file_offset])
            self.assertEqual(struct.unpack_from("<H", original, timer_file_offset)[0], STOCK_INSN)
            self.assertEqual(struct.unpack_from("<H", patched, timer_file_offset)[0], PATCHED_INSN)


if __name__ == "__main__":
    unittest.main()

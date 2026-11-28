import sys
import tempfile
import unittest
from pathlib import Path

LIBRARY_ROOT = Path(__file__).resolve().parents[1] / "library"
EMU_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(LIBRARY_ROOT))
sys.path.insert(0, str(EMU_ROOT))

from edit_library import apply_edits, edit_library
from libmodel import refine_order
from library_record_codec import decode_record
from library_primary_location_probe import inspect_primary_location
import mklib


class EditLibraryTests(unittest.TestCase):
    def make_input(self, directory: str) -> Path:
        path = Path(directory) / "input.lib"
        mklib.main(16, str(path), seed=7, location_mode="observed")
        return path

    def test_text_edit_preserves_location_tail_and_rebuilds_output(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = self.make_input(temp_dir)
            output_path = Path(temp_dir) / "output.lib"
            original = input_path.read_bytes()
            edit_library(
                input_path,
                output_path,
                [(3, "title", "Edited title"), (3, "artist", "New artist")],
            )
            rebuilt = output_path.read_bytes()

        record_start = 0x200 + 3 * 0x100
        self.assertEqual(
            rebuilt[record_start + 0xA9 : record_start + 0x100],
            original[record_start + 0xA9 : record_start + 0x100],
        )
        self.assertTrue(rebuilt[record_start : record_start + 8].startswith(b"\xff\xfeE\x00d\x00"))
        self.assertEqual(inspect_primary_location(rebuilt)["exclusive_cluster_matches"], 16)

    def test_apply_edits_rejects_an_out_of_range_record(self):
        with self.assertRaises(IndexError):
            apply_edits(bytes(0x300), [(1, "title", "not present")])

    def test_apply_edits_rejects_truncation_and_embedded_nul(self):
        data = bytearray(0x400)
        data[8:10] = (1).to_bytes(2, "little")
        with self.assertRaises(ValueError):
            apply_edits(bytes(data), [(0, "title", "x" * 64)])
        with self.assertRaises(ValueError):
            apply_edits(bytes(data), [(0, "title", "bad\0value")])

    def test_noop_edit_copies_a_realistic_input_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = self.make_input(temp_dir)
            output_path = Path(temp_dir) / "noop.lib"
            data = input_path.read_bytes()
            record = decode_record(data[0x200:0x300])
            edit_library(input_path, output_path, [(0, "title", record.title)])
            self.assertEqual(output_path.read_bytes(), data)

    def test_library_model_refines_a_short_key_run(self):
        keys = [b"A" * 16 + f"{index:04d}".encode() + b"\0" * 12 for index in range(4096)]
        self.assertEqual(tuple(refine_order(keys, len(keys), 0, 32, None)), tuple(range(4096)))

    def test_device_volume_output_is_refused(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = self.make_input(temp_dir)
            with self.assertRaises(ValueError):
                edit_library(
                    input_path,
                    Path("/Volumes/MECHEN M30/edited-MUSIC.LIB"),
                    [(0, "title", "blocked")],
                )


if __name__ == "__main__":
    unittest.main()

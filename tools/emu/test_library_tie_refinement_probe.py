import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[2]
SIM = ROOT / "tools/sim"
if str(SIM) not in sys.path:
    sys.path.insert(0, str(SIM))

from library_index_sort import INDEX_TEXT_CONVERSION_STOP_ZERO, SECTION_SPECS
from library_tie_refinement_probe import (
    compare_library,
    compare_library_conversion_matrix,
    compare_library_replacement_matrix,
    compare_library_rank_source_matrix,
    compare_library_section_modes,
)


SECTOR_SIZE = 512


def make_single_record_library() -> bytes:
    data = bytearray(6 * SECTOR_SIZE)
    struct.pack_into("<H", data, 0x08, 1)

    for section_index, (section_name, _) in enumerate(SECTION_SPECS.items()):
        section_offset = 0x10 + section_index * 0x1C
        data[section_offset : section_offset + 8] = section_name.encode("ascii").ljust(8, b"\0")
        table_sector = 2 + section_index
        struct.pack_into("<H", data, section_offset + 20, table_sector)
        struct.pack_into("<H", data, table_sector * SECTOR_SIZE, 0)

    return bytes(data)


class LibraryTieRefinementProbeTests(unittest.TestCase):
    def test_cli_rejects_hash_mismatch_for_stale_tail_mode(self) -> None:
        self.assert_cli_rejects_hash_mismatch("stale-tail")

    def test_cli_rejects_hash_mismatch_for_rank_source_matrix_mode(self) -> None:
        self.assert_cli_rejects_hash_mismatch("rank-source-matrix")

    def test_cli_rejects_hash_mismatch_for_replacement_matrix_mode(self) -> None:
        self.assert_cli_rejects_hash_mismatch("replace-matrix")

    def test_global_mode_comparison_preserves_existing_summary_shape(self) -> None:
        summary = compare_library(
            make_single_record_library(),
            conversion_mode=INDEX_TEXT_CONVERSION_STOP_ZERO,
        )

        self.assertEqual("stop-zero", summary["conversion_mode"])
        self.assertEqual(1, summary["record_count"])
        self.assertEqual(4, len(summary["sections"]))

    def test_section_modes_require_each_music_library_section(self) -> None:
        with self.assertRaises(ValueError):
            compare_library_section_modes(
                make_single_record_library(),
                {"ALLSONG": INDEX_TEXT_CONVERSION_STOP_ZERO},
            )

    def test_conversion_matrix_covers_all_sixteen_section_mode_combinations(self) -> None:
        results = compare_library_conversion_matrix(make_single_record_library())
        selections = {
            tuple(result["conversion_modes"][section_name] for section_name in SECTION_SPECS)
            for result in results
        }

        self.assertEqual(16, len(results))
        self.assertEqual(16, len(selections))

        for result in results:
            for section in result["sections"].values():
                self.assertEqual(1, section["matching_entries"])
                self.assertIsNone(section["first_mismatch"])

    def test_replacement_matrix_covers_each_candidate_byte(self) -> None:
        results = compare_library_replacement_matrix(make_single_record_library())

        self.assertEqual(0x25, len(results))
        self.assertEqual(
            [f"replace-continue:{value:02x}" for value in range(1, 0x26)],
            [result["conversion_mode"] for result in results],
        )

        for result in results:
            for section in result["sections"].values():
                self.assertEqual(1, section["matching_entries"])
                self.assertIsNone(section["first_mismatch"])

    def assert_cli_rejects_hash_mismatch(self, conversion_mode: str) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            library_path = Path(temporary_directory) / "MUSIC.LIB"
            library_path.write_bytes(make_single_record_library())
            result = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "tools/emu/library_tie_refinement_probe.py"),
                    str(library_path),
                    "0" * 64,
                    conversion_mode,
                ],
                capture_output=True,
                check=False,
                text=True,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("SHA-256", result.stderr)
        self.assertNotIn(" PASS ", result.stdout)

    def test_chained_matrix_uses_candidate_rank_for_later_sections(self) -> None:
        results = compare_library_conversion_matrix(
            make_single_record_library(),
            candidate_rank_chaining=True,
        )

        self.assertEqual(16, len(results))

        for result in results:
            self.assertEqual("candidate-previous-section", result["rank_source"])

    def test_rank_source_matrix_covers_each_transition_source_once_per_mode(self) -> None:
        results = compare_library_rank_source_matrix(make_single_record_library())
        selections = {
            (
                result["uniform_conversion_mode"],
                tuple(result["rank_sources"][section] for section in ("ALBUM", "ARTIST", "GENRE")),
            )
            for result in results
        }

        self.assertEqual(16, len(results))
        self.assertEqual(16, len(selections))

        for result in results:
            self.assertEqual("per-transition", result["rank_source"])
            for section in result["sections"].values():
                self.assertEqual(1, section["matching_entries"])
                self.assertIsNone(section["first_mismatch"])

    def test_rank_sources_must_name_each_non_title_section(self) -> None:
        with self.assertRaises(ValueError):
            compare_library_section_modes(
                make_single_record_library(),
                {
                    section_name: INDEX_TEXT_CONVERSION_STOP_ZERO
                    for section_name in SECTION_SPECS
                },
                rank_sources={"ALBUM": "device"},
            )


if __name__ == "__main__":
    unittest.main()

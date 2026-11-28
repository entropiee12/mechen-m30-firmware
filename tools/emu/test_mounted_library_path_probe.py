import sys
import tempfile
import unittest
from pathlib import Path

EMU_ROOT = Path(__file__).resolve().parent
LIBRARY_ROOT = EMU_ROOT.parent / "library"
sys.path.insert(0, str(EMU_ROOT))
sys.path.insert(0, str(LIBRARY_ROOT))
sys.path.insert(0, str(EMU_ROOT.parent / "sim"))

from library_record_codec import decode_record
from mounted_library_path_probe import inspect_mounted_library
import mklib


class MountedLibraryPathProbeTests(unittest.TestCase):
    def test_unique_file_names_group_to_one_parent_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "Music"
            directory = root / "Album"
            directory.mkdir(parents=True)
            library_path = Path(temp_dir) / "MUSIC.LIB"
            mklib.main(4, str(library_path), seed=3, location_mode="observed")
            data = library_path.read_bytes()
            for index in range(4):
                record = decode_record(data[0x200 + index * 0x100 : 0x200 + (index + 1) * 0x100])
                (directory / record.title).touch()

            summary = inspect_mounted_library(data, root)

        self.assertEqual(summary["matched_records"], 4)
        self.assertEqual(summary["unique_name_matches"], 4)
        self.assertEqual(summary["primary_grandparent_cluster_matches"], 0)
        self.assertEqual(summary["cluster_count"], 1)
        self.assertEqual(summary["single_parent_clusters"], 1)
        self.assertEqual(summary["single_parent_records"], 4)

    def test_rejects_a_missing_music_root(self):
        with self.assertRaises(ValueError):
            inspect_mounted_library(bytes(0x200), Path("/does/not/exist"))


if __name__ == "__main__":
    unittest.main()

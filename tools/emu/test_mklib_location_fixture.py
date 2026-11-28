import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "library"))

from library_primary_location_probe import inspect_primary_location
import mklib


class MklibLocationFixtureTests(unittest.TestCase):
    def test_observed_mode_reproduces_the_measured_location_relationships(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "observed.lib"
            mklib.main(128, str(output), seed=11, location_mode="observed")
            summary = inspect_primary_location(output.read_bytes())

        self.assertEqual(summary["record_count"], 128)
        self.assertEqual(summary["word6_cluster_matches"], 64)
        self.assertEqual(summary["word8_cluster_matches"], 64)
        self.assertEqual(summary["both_cluster_matches"], 0)
        self.assertEqual(summary["neither_cluster_matches"], 0)
        self.assertEqual(summary["companion_delta_non_32_aligned"], 0)
        self.assertEqual(summary["companion_delta_min"], 0)
        self.assertEqual(summary["companion_delta_max"], 0x8c0)

    def test_random_mode_remains_the_default_fixture_behavior(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "random.lib"
            mklib.main(4, str(output), seed=11)
            data = output.read_bytes()

        self.assertEqual(len(data), 0x200 + 4 * 0x100)


if __name__ == "__main__":
    unittest.main()

import unittest

from asrc_model import uses_end_of_track_advance_path


class AsrcModelTests(unittest.TestCase):
    def test_mode_three_uses_the_existing_advance_path_in_both_variants(self):
        self.assertTrue(uses_end_of_track_advance_path(3))
        self.assertTrue(uses_end_of_track_advance_path(3, drain_fix=True))

    def test_community_fix_adds_mode_four_to_the_advance_path(self):
        self.assertFalse(uses_end_of_track_advance_path(4))
        self.assertTrue(uses_end_of_track_advance_path(4, drain_fix=True))

    def test_other_modes_remain_outside_the_proven_boundary(self):
        self.assertFalse(uses_end_of_track_advance_path(0, drain_fix=True))
        self.assertFalse(uses_end_of_track_advance_path(5, drain_fix=True))


if __name__ == "__main__":
    unittest.main()

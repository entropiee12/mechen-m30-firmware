import unittest

from artwork_fallback import plan_community_jpeg_fallback


class ArtworkFallbackTests(unittest.TestCase):
    def test_fallback_requires_the_missing_artwork_status(self):
        plan = plan_community_jpeg_fallback(0, 150, 150, 3, 1)

        self.assertFalse(plan.attempted)
        self.assertFalse(plan.accepted)

    def test_valid_mono_and_colour_candidates_are_accepted(self):
        mono = plan_community_jpeg_fallback(0xFF, 150, 150, 1, 0xFFF)
        colour = plan_community_jpeg_fallback(0xFF, 120, 80, 3, 10)

        self.assertEqual((mono.accepted, mono.output_bytes), (True, 45_000))
        self.assertEqual((colour.accepted, colour.output_bytes), (True, 19_200))

    def test_dimensions_components_and_block_count_are_bounded(self):
        self.assertEqual(
            plan_community_jpeg_fallback(0xFF, 151, 150, 3, 1).reason,
            "width-out-of-range",
        )
        self.assertEqual(
            plan_community_jpeg_fallback(0xFF, 150, 150, 2, 1).reason,
            "unsupported-component-count",
        )
        self.assertEqual(
            plan_community_jpeg_fallback(0xFF, 150, 150, 3, 0).reason,
            "empty-JPEG-block-count",
        )
        self.assertEqual(
            plan_community_jpeg_fallback(0xFF, 150, 150, 3, 0x1001).reason,
            "JPEG-block-limit-exceeded",
        )

        self.assertTrue(plan_community_jpeg_fallback(0xFF, 150, 150, 3, 0x1000).accepted)


if __name__ == "__main__":
    unittest.main()

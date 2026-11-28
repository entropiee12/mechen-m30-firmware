import unittest

from library_scan import MAX_LIBRARY_RECORDS, plan_library_scan


class LibraryScanTests(unittest.TestCase):
    def test_stock_capacity_accepts_up_to_four_thousand_records(self):
        plan = plan_library_scan(MAX_LIBRARY_RECORDS)

        self.assertEqual(plan.accepted_records, MAX_LIBRARY_RECORDS)
        self.assertFalse(plan.stopped_at_capacity)

    def test_records_above_capacity_are_not_added_to_the_stock_scan(self):
        plan = plan_library_scan(MAX_LIBRARY_RECORDS + 1)

        self.assertEqual(plan.accepted_records, MAX_LIBRARY_RECORDS)
        self.assertTrue(plan.stopped_at_capacity)

    def test_custom_capacity_is_explicit_for_host_scenarios(self):
        plan = plan_library_scan(7, capacity=5)

        self.assertEqual(plan.accepted_records, 5)
        self.assertTrue(plan.stopped_at_capacity)

    def test_negative_candidates_and_non_positive_capacity_are_rejected(self):
        with self.assertRaises(ValueError):
            plan_library_scan(-1)
        with self.assertRaises(ValueError):
            plan_library_scan(1, capacity=0)


if __name__ == "__main__":
    unittest.main()

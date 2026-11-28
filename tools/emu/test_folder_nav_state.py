import unittest

from folder_nav_state import FolderNavigationState


def state(total, page, start, end, active, mode):
    model = FolderNavigationState(
        page_size=page,
        start=start,
        end=end,
        total=total,
        active=active,
        anchor=active,
        previous=active,
    )
    return model.apply(mode)


class FolderNavigationStateTests(unittest.TestCase):
    def test_single_item_is_a_noop(self):
        self.assertEqual(
            state(1, 1, 1, 1, 1, "next"),
            {"start": 1, "end": 1, "active": 1, "previous": 1,
             "status": 0, "update": 0, "redraw": 0, "reset": 0},
        )

    def test_next_at_page_end_redraws_and_advances_window(self):
        self.assertEqual(
            state(10, 7, 1, 7, 7, "next"),
            {"start": 2, "end": 8, "active": 8, "previous": 7,
             "status": 1, "update": 2, "redraw": 1, "reset": 0},
        )

    def test_next_at_bottom_wraps_to_first_page(self):
        self.assertEqual(
            state(10, 7, 4, 10, 10, "next"),
            {"start": 1, "end": 7, "active": 1, "previous": 1,
             "status": 4, "update": 2, "redraw": 0, "reset": 0},
        )

    def test_previous_at_top_wraps_to_last_page(self):
        self.assertEqual(
            state(10, 7, 1, 7, 1, "previous"),
            {"start": 4, "end": 10, "active": 10, "previous": 7,
             "status": 5, "update": 2, "redraw": 0, "reset": 0},
        )

    def test_previous_inside_last_page_keeps_raw_window_fields(self):
        self.assertEqual(
            state(10, 7, 4, 10, 4, "previous"),
            {"start": 4, "end": 10, "active": 3, "previous": 4,
             "status": 0, "update": 1, "redraw": 0, "reset": 0},
        )


if __name__ == "__main__":
    unittest.main()

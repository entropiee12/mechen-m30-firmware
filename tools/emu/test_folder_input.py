import unittest

from folder_input import FolderInputSession
from folder_ui import FolderList, FolderRow
from tools.sim.key_filter import KEY_DOWN, KEY_RELEASE, SCREEN_OFF


class FolderInputSessionTests(unittest.TestCase):
    def make_session(self, *, screen_state: int = 0) -> FolderInputSession:
        view = FolderList(tuple(FolderRow(str(index)) for index in range(4)))
        return FolderInputSession(view, screen_state=screen_state)

    def test_screen_on_next_key_navigates_folder_view(self) -> None:
        session = self.make_session()

        result = session.dispatch(0x03, KEY_DOWN, 0)

        self.assertEqual(result["action"], "navigate")
        self.assertEqual(session.view.selected_index, 1)
        self.assertEqual(result["navigation"]["selected_index"], 1)

    def test_stock_screen_off_next_key_only_wakes(self) -> None:
        session = self.make_session(screen_state=SCREEN_OFF)

        result = session.dispatch(0x03, KEY_DOWN, 0)

        self.assertEqual(result["action"], "wake")
        self.assertEqual(session.view.selected_index, 0)

    def test_patched_screen_off_next_key_navigates(self) -> None:
        session = self.make_session(screen_state=SCREEN_OFF)

        result = session.dispatch(0x03, KEY_DOWN, 0, patched=True)

        self.assertEqual(result["action"], "navigate")
        self.assertEqual(session.view.selected_index, 1)

    def test_release_does_not_repeat_navigation(self) -> None:
        session = self.make_session()
        session.dispatch(0x03, KEY_DOWN, 0)

        result = session.dispatch(0x03, KEY_RELEASE, 10)

        self.assertEqual(result["action"], "deliver")
        self.assertEqual(session.view.selected_index, 1)


if __name__ == "__main__":
    unittest.main()

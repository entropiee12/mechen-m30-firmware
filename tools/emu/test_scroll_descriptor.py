import struct
import unittest

from scroll_descriptor import (
    EVENT_CANCEL,
    EVENT_EXIT,
    EVENT_NEXT,
    EVENT_PREV,
    EVENT_REDRAW,
    STATUS_REDRAW,
    STATUS_SELECTION,
    ScrollContext,
    ScrollEntry,
    ScrollState,
)


class ScrollDescriptorTests(unittest.TestCase):
    def test_next_and_previous_match_decompiled_switch(self):
        context = ScrollContext(ScrollState(slot_count=3, entries=[ScrollEntry(), ScrollEntry(), ScrollEntry()]))
        self.assertEqual(context.apply_event(EVENT_NEXT), STATUS_SELECTION)
        self.assertEqual((context.state.active_slot, context.state.previous_slot), (1, 0))
        self.assertEqual(context.apply_event(EVENT_PREV), STATUS_SELECTION)
        self.assertEqual((context.state.active_slot, context.state.previous_slot), (0, 1))

    def test_redraw_uses_distinct_status_without_changing_selection(self):
        context = ScrollContext(
            ScrollState(
                slot_count=3,
                active_slot=2,
                previous_slot=1,
                entries=[ScrollEntry(), ScrollEntry(), ScrollEntry()],
            )
        )
        self.assertEqual(context.apply_event(EVENT_REDRAW), STATUS_REDRAW)
        self.assertEqual((context.state.active_slot, context.state.previous_slot), (2, 1))
        self.assertEqual(context.status, 1)

    def test_exit_values_are_preserved(self):
        context = ScrollContext()
        self.assertEqual(context.apply_event(EVENT_EXIT), -0x7FEF)
        self.assertEqual(context.apply_event(EVENT_CANCEL), -0x7FEC)

    def test_serialized_offsets_match_decompilation(self):
        model = ScrollState(slot_count=3, active_slot=2, previous_slot=1, entries=[ScrollEntry(), ScrollEntry(), ScrollEntry()])
        state, entries_and_context = model.bytes_for(0x10002000, 0x10002100)
        self.assertEqual(state[0x06:0x09], bytes((3, 2, 1)))
        self.assertEqual(struct.unpack_from("<I", state, 0x0C)[0], 0x10002100)
        self.assertEqual(struct.unpack_from("<h", entries_and_context, 0)[0], 0x2005)


if __name__ == "__main__":
    unittest.main()

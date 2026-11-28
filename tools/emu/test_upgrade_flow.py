import json
import unittest

from upgrade_flow import (
    ENTRY_SELECTORS,
    FAILURE_SELECTORS,
    UpgradeFlowTrace,
    UpgradePhase,
)


class UpgradeFlowTraceTests(unittest.TestCase):
    def test_selection_and_confirmation_are_trace_only(self):
        flow = UpgradeFlowTrace()

        flow.select(3)
        flow.request_confirm()
        flow.record_selector(ENTRY_SELECTORS[1], result=0)

        trace = flow.trace()
        self.assertEqual(trace["phase"], UpgradePhase.CONFIRM_REQUEST.value)
        self.assertEqual(trace["selected_option"], 3)
        self.assertEqual(
            [event["kind"] for event in trace["events"]],
            ["select", "confirm-request", "entry-selector"],
        )
        self.assertIn("flash", trace["forbidden_claims"])
        json.dumps(trace)

    def test_preflight_events_do_not_become_update_success(self):
        flow = UpgradeFlowTrace(selected_option=1)
        flow.request_confirm()
        flow.search_complete(filename="MECHEN_M30 HEX")
        self.assertEqual(flow.phase, UpgradePhase.SEARCH_COMPLETE)
        self.assertNotIn("flash", [event.kind for event in flow.events or []])

        flow.decrypt_error()
        self.assertEqual(flow.phase, UpgradePhase.DECRYPT_ERROR)
        self.assertEqual(flow.events[-1].kind, "decrypt-error")

    def test_known_failure_selector_and_unknown_selector_keep_meaning_unresolved(self):
        flow = UpgradeFlowTrace()
        failure = flow.record_selector(FAILURE_SELECTORS[0], result=-1)
        unknown = flow.record_selector(0x1234, result=0x1_0000_0002)

        self.assertEqual(failure.kind, "failure-selector")
        self.assertEqual(failure.result, 0xFFFFFFFF)
        self.assertEqual(unknown.kind, "unresolved-selector")
        self.assertEqual(unknown.result, 2)

    def test_selection_rejects_sixth_option(self):
        flow = UpgradeFlowTrace()
        with self.assertRaisesRegex(ValueError, "range 0..4"):
            flow.select(5)


if __name__ == "__main__":
    unittest.main()

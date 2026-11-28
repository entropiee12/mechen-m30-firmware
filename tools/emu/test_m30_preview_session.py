import json
import struct
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).parents[2]
SIM = ROOT / "tools/sim"
if str(SIM) not in sys.path:
    sys.path.insert(0, str(SIM))

from m30_preview_session import M30PreviewSession
from dae_shm_flow import FirmwareEqAudioSession
from dsp_model import DAE_CONFIG_BYTES
from m30_model import FIRMWARE_EQ_SLIDER_INCREASE_EVENT
from test_kernel_ops import load_kernel
from usound_callback import Break0ExceptionContext, UsoundCallbackBoundary
from usb_audio_stream import UsbAudioStream


AP = ROOT / "build/qemu-minimal-safe/work/verify/fw/music.ap"
STY = ROOT / "build/qemu-minimal-safe/work/verify/fw/music.sty"


@unittest.skipUnless(AP.exists() and STY.exists(), "extracted firmware resources are not present")
class M30PreviewSessionTests(unittest.TestCase):
    def make_session(self, *, eq=None) -> M30PreviewSession:
        return M30PreviewSession.create(
            AP,
            STY,
            callback=UsoundCallbackBoundary(),
            audio=UsbAudioStream(),
            title="LONG TITLE",
            artist="ARTIST",
            album="ALBUM",
            filename="FILE",
            scroll_offset=7,
            eq=eq,
        )

    def test_one_session_records_lcd_audio_and_callback_boundaries(self) -> None:
        session = self.make_session()
        exception = Break0ExceptionContext(
            exception_code=9,
            epc=0x8005_26AB,
            bad_vaddr=0,
            status=2,
        )
        session.observe_callback(
            0x10,
            (1, 2, 3, 0x1_0000_0004),
            exception=exception,
        )
        self.assertEqual(session.submit_audio_packet(bytes(range(200))), 50)

        trace = session.trace()
        self.assertEqual(trace["lcd"]["pixels"], 320 * 240)
        self.assertEqual(trace["lcd"]["remaining_pixels"], 0)
        self.assertEqual(trace["audio"]["queued_bytes"], 200)
        self.assertEqual(trace["callback"]["calls"][0]["module_id"], "0x00000010")
        self.assertEqual(
            trace["callback"]["calls"][0]["args"],
            ["0x00000001", "0x00000002", "0x00000003", "0x00000004"],
        )
        self.assertEqual(
            trace["callback"]["calls"][0]["exception"],
            {
                "code": "0x00000009",
                "epc": "0x800526ab",
                "bad_vaddr": "0x00000000",
                "status": "0x00000002",
                "provenance": "caller-provided",
            },
        )
        self.assertEqual(
            [call["identifier"] for call in trace["state_dispatch"]["transport"]],
            [0x1002, 0x1004, 0x1003],
        )

    def test_trace_is_json_safe_and_identifies_hybrid_boundary(self) -> None:
        session = self.make_session()
        session.observe_callback(0x10, (0, 0, 0, 0))

        trace = session.trace()
        self.assertEqual(trace["provenance"]["kind"], "hybrid-host-session")
        self.assertIn("fixed-ROM service not executed", trace["provenance"]["callback"])
        self.assertNotIn("exception", trace["callback"]["calls"][0])

    def test_trace_includes_extracted_handoff_for_caller_supplied_ra(self) -> None:
        session = self.make_session()
        session.observe_callback(
            0x10,
            (0, 0, 0, 0),
            exception=Break0ExceptionContext(9, 0x1234, 0, 0, ra=0x5678),
        )

        exception = session.trace()["callback"]["calls"][0]["exception"]

        self.assertEqual(
            exception["ker_xsys_entry_candidate"],
            {
                "handler_target": "0xbfc06d6c",
                "stores": [
                    {"address": "0x00013dd8", "value": "0x00001234"},
                    {"address": "0x00013ddc", "value": "0x00005678"},
                ],
                "provenance": (
                    "derived from ker_xsys.bin; register values caller-provided; "
                    "vector path unverified"
                ),
            },
        )

    def test_observe_callback_keeps_two_argument_injection_compatible(self) -> None:
        class LegacyCallbackBoundary:
            def __init__(self) -> None:
                self.calls = []

            def trap(self, module_id: int, args: tuple[int, int, int, int]):
                call = SimpleNamespace(module_id=module_id, args=args)
                self.calls.append(call)

                return call

        session = self.make_session()
        session.callback = LegacyCallbackBoundary()
        call = session.observe_callback(0x10, (1, 2, 3, 4))

        self.assertEqual(call.module_id, 0x10)
        self.assertEqual(call.args, (1, 2, 3, 4))
        self.assertNotIn("exception", session.trace()["callback"]["calls"][0])

    def test_verified_kernel_events_are_json_safe_and_keep_other_calls_unchanged(self) -> None:
        session = self.make_session()
        session.callback = UsoundCallbackBoundary(kernel_image=load_kernel())
        session.observe_callback(0x0F, (3, 0x10001000, 0xF0, 0x47))
        session.observe_callback(0x0F, (3, 0, 0, 0x49))
        session.observe_callback(0x0F, (1, 2, 3, 0x4A))
        session.observe_callback(0x0F, (0x1234, 0, 0, 0x4B))
        session.observe_callback(0x10, (0, 0, 0, 0))

        calls = session.trace()["callback"]["calls"]

        self.assertEqual(calls[0]["kernel_event"]["pointer"], "0x1f189175")
        self.assertEqual(calls[0]["kernel_event"]["result"], "0x00000000")
        self.assertEqual(calls[1]["kernel_event"]["result"], "0x10001000")
        self.assertEqual(calls[2]["kernel_event"]["label"], "synthetic=task-create-recorded")
        self.assertEqual(calls[3]["kernel_event"]["label"], "synthetic=debug-print-recorded")
        self.assertEqual(calls[2]["kernel_event"]["far"]["file_offset"], "0x00002944")
        self.assertEqual(calls[2]["kernel_event"]["task"]["entry"], "UNKNOWN")
        self.assertEqual(calls[2]["kernel_event"]["task"]["raw_args"], calls[2]["args"])
        self.assertEqual(calls[2]["kernel_event"]["task"]["id"], "0x02")
        self.assertEqual(calls[2]["kernel_event"]["task"]["flags"], "0x03")
        self.assertNotIn("result", calls[2]["kernel_event"])
        self.assertNotIn("result", calls[3]["kernel_event"])
        self.assertNotIn("kernel_event", calls[4])
        json.dumps(session.trace())

    def test_session_traces_rom_response_and_caller_observed_task_record(self) -> None:
        session = self.make_session()
        session.callback = UsoundCallbackBoundary(kernel_image=load_kernel())
        rom_index = next(
            entry.index for entry in session.callback.kernel_entries if entry.kind == "rom"
        )
        session.observe_callback(0x0F, (0, 0, 0, rom_index))
        session.observe_callback(
            0x0F,
            (0x9FC27F00, 3, 0xFF, 0x4A),
            task_record=struct.pack("<IIIH", 0xBFC27735, 0x9FC21B08, 0x9FC1BDF0, 0x4B0),
        )

        rom, task = session.trace()["callback"]["calls"]

        self.assertEqual(rom["kernel_event"]["label"], "synthetic=rom-zero")
        self.assertEqual(rom["kernel_event"]["result"], "0x00000000")
        self.assertEqual(
            task["kernel_event"]["task"],
            {
                "record_pointer": "0x9fc27f00",
                "entry": "0xbfc27735",
                "arg": "0x9fc21b08",
                "stack_top": "0x9fc1bdf0",
                "size": "0x04b0",
                "id": "0x03",
                "flags": "0xff",
                "record_source": "caller-provided",
                "raw_args": task["args"],
            },
        )

    def test_session_connects_custom_edit_music_start_and_rate_choice(self) -> None:
        eq = FirmwareEqAudioSession(
            state_flag=1,
            dae_configuration=bytes(DAE_CONFIG_BYTES),
            custom_gains=(0, 0, 0, 0, 0, 0, 0),
        )
        session = self.make_session(eq=eq)

        self.assertTrue(session.start_music_eq(0, engine_playing=False))
        self.assertTrue(session.edit_custom_eq(0, FIRMWARE_EQ_SLIDER_INCREASE_EVENT))
        session.confirm_custom_eq()
        self.assertEqual(session.play_with_eq(44100), "adppdrc.dsp")
        self.assertEqual(session.play_with_eq(96000), "audiopp.dsp")

        trace = session.trace()["eq"]

        self.assertEqual(trace["selector"], 7)
        self.assertEqual(trace["custom_gains"], [1, 0, 0, 0, 0, 0, 0])
        self.assertEqual(trace["shared_plus_7"], 1)
        self.assertEqual(trace["last_dsp_name"], "audiopp.dsp")
        self.assertEqual(trace["auddev"]["eq_flag_3a"], 0)
        self.assertEqual(trace["auddev"]["band_bytes"], 0x80)

    def test_session_rejects_eq_actions_without_an_injected_model(self) -> None:
        session = self.make_session()

        with self.assertRaisesRegex(RuntimeError, "not configured"):
            session.play_with_eq(44100)


if __name__ == "__main__":
    unittest.main()

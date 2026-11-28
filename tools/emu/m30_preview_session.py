#!/usr/bin/env python3
"""Compose the evidence-backed M30 display and audio-boundary models.

The session deliberately joins two different levels of evidence: the real
``music.ap``/``music.sty`` host renderer is sent through the recovered LCD
command sink, while an injected callback boundary records the observed
four-argument usound trap.  The callback service is never executed because
the fixed-ROM dispatcher is not present in the extracted firmware.
"""

from __future__ import annotations

import json
import argparse
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from lcd_preview import render_preview
from lcd_transfer import LcdTransferBoundary
from m30_ui import NowPlaying


class CallbackBoundary(Protocol):
    """Minimal injected interface for the recovered callback envelope."""

    calls: list[Any]

    def trap(self, module_id: int, args: tuple[int, int, int, int]) -> Any:
        ...


class AudioStream(Protocol):
    """Minimal injected interface for the observed USB-audio ring."""

    available: int

    def submit_packet(self, payload: bytes) -> int:
        ...


class EqAudioSession(Protocol):
    """Injected host model for the recovered EQ/DAE playback flow."""

    def edit_custom(self, band_index: int, event: int) -> bool:
        ...

    def confirm_custom(self) -> None:
        ...

    def cancel_custom(self) -> None:
        ...

    def music_start(self, stored_selector: int, *, engine_playing: bool) -> bool:
        ...

    def play(self, sample_rate_hz: int) -> str:
        ...

    def trace(self) -> dict[str, object]:
        ...


@dataclass
class M30PreviewSession:
    """One deterministic visible-frame plus boundary-observation session."""

    sink: LcdTransferBoundary
    ui: NowPlaying
    callback: CallbackBoundary
    audio: AudioStream
    eq: EqAudioSession | None = None
    submitted_packets: int = 0
    submitted_frames: int = 0

    @classmethod
    def create(
        cls,
        music_ap: Path,
        music_sty: Path,
        *,
        callback: CallbackBoundary,
        audio: AudioStream,
        title: str,
        artist: str,
        album: str,
        filename: str,
        scroll_offset: int,
        eq: EqAudioSession | None = None,
    ) -> "M30PreviewSession":
        sink, ui = render_preview(
            music_ap,
            music_sty,
            title=title,
            artist=artist,
            album=album,
            filename=filename,
            scroll_offset=scroll_offset,
        )
        return cls(sink=sink, ui=ui, callback=callback, audio=audio, eq=eq)

    def observe_callback(
        self,
        module_id: int,
        args: tuple[int, int, int, int],
        *,
        exception: Any | None = None,
        task_record: bytes | None = None,
    ) -> Any:
        """Record the callback envelope and any supplied exception context."""

        if exception is None and task_record is None:
            return self.callback.trap(module_id, args)

        trap_with_context = getattr(self.callback, "trap")

        if exception is None:
            return trap_with_context(module_id, args, task_record=task_record)

        if task_record is not None:
            return trap_with_context(
                module_id,
                args,
                exception=exception,
                task_record=task_record,
            )

        return trap_with_context(module_id, args, exception=exception)

    def submit_audio_packet(self, payload: bytes) -> int:
        """Queue one observed USB packet and retain only framing statistics."""

        frames = self.audio.submit_packet(payload)
        self.submitted_packets += 1
        self.submitted_frames += frames
        return frames

    def edit_custom_eq(self, band_index: int, event: int) -> bool:
        if self.eq is None:
            raise RuntimeError("EQ/DAE host model is not configured")

        return self.eq.edit_custom(band_index, event)

    def confirm_custom_eq(self) -> None:
        if self.eq is None:
            raise RuntimeError("EQ/DAE host model is not configured")

        self.eq.confirm_custom()

    def cancel_custom_eq(self) -> None:
        if self.eq is None:
            raise RuntimeError("EQ/DAE host model is not configured")

        self.eq.cancel_custom()

    def start_music_eq(self, stored_selector: int, *, engine_playing: bool) -> bool:
        if self.eq is None:
            raise RuntimeError("EQ/DAE host model is not configured")

        return self.eq.music_start(stored_selector, engine_playing=engine_playing)

    def play_with_eq(self, sample_rate_hz: int) -> str:
        if self.eq is None:
            raise RuntimeError("EQ/DAE host model is not configured")

        return self.eq.play(sample_rate_hz)

    def trace(self) -> dict[str, object]:
        """Return JSON-safe evidence from the complete host session."""

        window = self.sink.window
        callbacks = []
        for call in self.callback.calls:
            exception = None
            exception_context = getattr(call, "exception", None)

            if exception_context is not None:
                exception = {
                    "code": f"0x{int(exception_context.exception_code):08x}",
                    "epc": f"0x{int(exception_context.epc):08x}",
                    "bad_vaddr": f"0x{int(exception_context.bad_vaddr):08x}",
                    "status": f"0x{int(exception_context.status):08x}",
                    "provenance": "caller-provided",
                }
                handoff_trace = getattr(exception_context, "handoff_trace", None)
                handoff = handoff_trace() if callable(handoff_trace) else None

                if handoff is not None:
                    stores = [
                        {
                            "address": f"0x{int(store['address']):08x}",
                            "value": f"0x{int(store['value']):08x}",
                        }
                        for store in handoff["stores"]
                    ]
                    exception["ker_xsys_entry_candidate"] = {
                        "handler_target": f"0x{int(handoff['handler_target']):08x}",
                        "stores": stores,
                        "provenance": handoff["provenance"],
                    }

            call_trace = {
                "module_id": f"0x{int(call.module_id):08x}",
                "args": [f"0x{int(value):08x}" for value in call.args],
            }

            if exception is not None:
                call_trace["exception"] = exception

            kernel_event = getattr(call, "kernel_event", None)

            if kernel_event is not None:
                event_trace = {
                    "label": kernel_event.label,
                    "index": f"0x{kernel_event.index:02x}",
                    "pointer": f"0x{kernel_event.pointer:08x}",
                    "kind": kernel_event.kind,
                    "mips16": kernel_event.mips16,
                    "dispatch": "inferred a3 table index",
                }

                if kernel_event.far is not None:
                    far = kernel_event.far
                    event_trace["far"] = {
                        "group": far.group,
                        "bank_index": far.bank_index,
                        "window_address": f"0x{far.window_address:08x}",
                        "file_offset": (
                            f"0x{far.file_offset:08x}"
                            if far.file_offset is not None
                            else "UNKNOWN"
                        ),
                    }

                if kernel_event.task_id is not None:
                    record = kernel_event.task_record
                    event_trace["task"] = {
                        "record_pointer": f"0x{int(call.args[0]):08x}",
                        "entry": f"0x{record.entry:08x}" if record is not None else "UNKNOWN",
                        "arg": f"0x{record.arg:08x}" if record is not None else "UNKNOWN",
                        "stack_top": (
                            f"0x{record.stack_top:08x}" if record is not None else "UNKNOWN"
                        ),
                        "size": f"0x{record.size:04x}" if record is not None else "UNKNOWN",
                        "id": f"0x{kernel_event.task_id:02x}",
                        "flags": f"0x{kernel_event.task_flags:02x}",
                        "record_source": "caller-provided" if record is not None else "UNKNOWN",
                        "raw_args": call_trace["args"],
                    }

                if kernel_event.result is not None:
                    event_trace["result"] = f"0x{kernel_event.result:08x}"

                call_trace["kernel_event"] = event_trace

            callbacks.append(call_trace)

        trace = {
            "provenance": {
                "kind": "hybrid-host-session",
                "lcd": "real music.ap/music.sty renderer plus recovered LCD sink",
                "callback": "observed usound envelope; fixed-ROM service not executed",
            },
            "lcd": {
                "commands": [
                    {
                        "opcode": f"0x{command.opcode:02x}",
                        "payload": list(command.payload),
                    }
                    for command in self.sink.commands
                ],
                "pixels": window.pixel_count if window else 0,
                "remaining_pixels": self.sink.remaining_pixels,
            },
            "paint_dispatch": self.ui.paint_dispatch_trace(),
            "ui_calls": self.ui.observed_dispatch_trace(),
            "state_dispatch": self.ui.state_dispatch_traces(),
            "audio": {
                "submitted_packets": self.submitted_packets,
                "submitted_frames": self.submitted_frames,
                "queued_bytes": self.audio.available,
            },
            "callback": {"calls": callbacks},
        }

        if self.eq is not None:
            trace["eq"] = self.eq.trace()

        return trace

    def write_trace(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.trace(), indent=2) + "\n")


def _parse_int(value: str) -> int:
    return int(value, 0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--music-ap", type=Path, required=True)
    parser.add_argument("--music-sty", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--title", default="TITLE")
    parser.add_argument("--artist", default="ARTIST")
    parser.add_argument("--album", default="ALBUM")
    parser.add_argument("--file", dest="filename", default="FILE")
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument(
        "--callback-module-id",
        "--callback-selector",
        dest="callback_module_id",
        type=_parse_int,
        default=0x10,
        help="module ID captured at the break-0 boundary (legacy alias: --callback-selector)",
    )
    parser.add_argument(
        "--callback-args",
        type=_parse_int,
        nargs=4,
        metavar=("ARG0", "ARG1", "ARG2", "ARG3"),
        default=(0, 0, 0, 0),
        help="four test inputs for the observed callback envelope",
    )
    parser.add_argument(
        "--callback-exception",
        type=_parse_int,
        nargs=4,
        metavar=("CODE", "EPC", "BADVADDR", "STATUS"),
        help="optional captured CP0 exception context for the callback trace",
    )
    parser.add_argument(
        "--callback-ra",
        type=_parse_int,
        help="optional caller-provided RA for the extracted exception-handoff trace",
    )
    parser.add_argument(
        "--kernel-drv",
        type=Path,
        help="verified kernel.drv image for synthetic slot-0x0f operation events",
    )
    parser.add_argument(
        "--callback-task-record",
        type=_parse_int,
        nargs=4,
        metavar=("ENTRY", "ARG", "STACK_TOP", "SIZE"),
        help="caller-observed op-0x4a record fields (little-endian words and u16 size)",
    )
    parser.add_argument(
        "--submit-packet",
        action="store_true",
        help="submit one deterministic 200-byte USB-audio test packet",
    )
    args = parser.parse_args()

    if args.callback_ra is not None and args.callback_exception is None:
        parser.error("--callback-ra requires --callback-exception")

    task_record = None

    if args.callback_task_record is not None:
        if args.callback_module_id != 0x0F or args.callback_args[3] != 0x4A:
            parser.error("--callback-task-record requires module 0x0f operation 0x4a")

        try:
            task_record = struct.pack("<IIIH", *args.callback_task_record)

        except struct.error:
            parser.error("--callback-task-record fields must fit three u32s and one u16")

    # The emulator directories intentionally use direct-module imports.  Add
    # the simulation directory only at this CLI boundary so the shared host
    # models remain single-sourced without turning the tools tree into a
    # package with import-time side effects.
    sim_dir = Path(__file__).resolve().parents[1] / "sim"
    sys.path.insert(0, str(sim_dir))
    from usound_callback import Break0ExceptionContext, UsoundCallbackBoundary
    from usb_audio_stream import UsbAudioStream

    session = M30PreviewSession.create(
        args.music_ap,
        args.music_sty,
        callback=UsoundCallbackBoundary(
            kernel_image=args.kernel_drv.read_bytes() if args.kernel_drv is not None else None
        ),
        audio=UsbAudioStream(),
        title=args.title,
        artist=args.artist,
        album=args.album,
        filename=args.filename,
        scroll_offset=args.offset,
    )
    exception = None

    if args.callback_exception is not None:
        exception = Break0ExceptionContext(
            *args.callback_exception,
            ra=args.callback_ra,
        )

    session.observe_callback(
        args.callback_module_id,
        tuple(args.callback_args),
        exception=exception,
        task_record=task_record,
    )
    if args.submit_packet:
        packet_bytes = getattr(session.audio, "contract").packet_bytes
        session.submit_audio_packet(bytes(range(packet_bytes)))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    session.sink.framebuffer.ppm(args.out)
    session.write_trace(args.trace)
    trace = session.trace()
    print(
        f"M30 PREVIEW SESSION PASS image={args.out} "
        f"pixels={trace['lcd']['pixels']} "
        f"callbacks={len(trace['callback']['calls'])} "
        f"queued={trace['audio']['queued_bytes']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

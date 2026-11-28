"""Evidence-based host preview for the M30 upgrade selection screen.

The upgrade style contains five 16x16 radio-like PicBoxes.  Their frame 0 and
frame 1 bitmaps are real ``upgrade.sty`` resources; this module only supplies
the host-side selected-index state and example text values. The AP dispatcher
and the source of these screen strings have not yet been recovered. It does
not claim to execute the firmware updater or write a card/device.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

from m30_font import M30Font
from ru3_preview import Ru3Preview, render_style


UPGRADE_OPTION_PICBOXES = (0, 1, 2, 3, 4)
UPGRADE_UNSELECTED_FRAME = 0
UPGRADE_SELECTED_FRAME = 1
SELECT_PROMPT = "SELECT UPGRADE FILE:"
CONFIRM_PROMPT = "CONFIRM UPGRADE"

# FUN_bfc37408 is reached through the upgrade AP literal pool at
# 0x40014e2c -> 0xbfc37409. Keep the recovered structure offsets explicit;
# names such as ``total`` and ``done`` would overstate what the ABI proves.
PROGRESS_TOTAL_PART_A_OFFSET = 0x18
PROGRESS_TOTAL_PART_B_OFFSET = 0x08
PROGRESS_DONE_OFFSET = 0x20
PROGRESS_SECTOR_SHIFT = 9
PROGRESS_SCALE = 10


@dataclass(frozen=True)
class UpgradeProgressResult:
    """One host replay of the verified upgrade progress normalizer."""

    value: int
    raw_value: int | None
    denominator_sectors: int
    callback_emitted: bool


def normalize_upgrade_progress(
    total_part_a_bytes: int,
    total_part_b_bytes: int,
    processed_bytes: int,
    *,
    previous_value: int = 0,
) -> UpgradeProgressResult:
    """Mirror ``FUN_bfc37408`` without assigning names to its state fields.

    The recovered code adds the two fields at +0x18 and +0x08, shifts by 9,
    scales the +0x20 field by 10, maps 1 to 2 and 9 to 10, then emits the
    indirect callback only when the value changed to an even number. A zero
    denominator leaves the previously stored value unchanged.
    """
    fields = (total_part_a_bytes, total_part_b_bytes, processed_bytes, previous_value)
    if any(value < 0 for value in fields):
        raise ValueError("progress fields must be non-negative")

    denominator_sectors = (
        total_part_a_bytes + total_part_b_bytes
    ) >> PROGRESS_SECTOR_SHIFT
    if denominator_sectors == 0:
        return UpgradeProgressResult(previous_value, None, 0, False)

    raw_value = (processed_bytes * PROGRESS_SCALE) // denominator_sectors
    value = 2 if raw_value == 1 else 10 if raw_value == 9 else raw_value
    callback_emitted = value != previous_value and value % 2 == 0
    return UpgradeProgressResult(value, raw_value, denominator_sectors, callback_emitted)


@dataclass(frozen=True)
class UpgradeScreen:
    style_path: Path
    selected_option: int = 0
    select_prompt: str = SELECT_PROMPT
    confirm_prompt: str = CONFIRM_PROMPT
    font: M30Font | None = None

    def __post_init__(self) -> None:
        if not 0 <= self.selected_option < len(UPGRADE_OPTION_PICBOXES):
            raise ValueError("selected_option must be one of the five upgrade options")

    def pic_frames(self) -> dict[int, int]:
        return {
            picbox: (
                UPGRADE_SELECTED_FRAME
                if picbox == self.selected_option
                else UPGRADE_UNSELECTED_FRAME
            )
            for picbox in UPGRADE_OPTION_PICBOXES
        }

    def render(self) -> Ru3Preview:
        preview = render_style(
            self.style_path,
            text_values={0: self.select_prompt, 1: self.confirm_prompt},
            font=self.font,
            pic_frames=self.pic_frames(),
        )
        preview.trace["kind"] = "upgrade-screen-preview"
        preview.trace["provenance"] = (
            "real upgrade.sty PicBox/TextBox records; host-selected option and text"
        )
        preview.trace["selected_option"] = self.selected_option
        preview.trace["state_boundary"] = (
            "AP input/upgrade state machine is not executed; selected index is host input"
        )
        for textbox in preview.trace["textboxes"]:
            textbox["text_origin"] = "host-supplied example; not recovered from upgrade.ap"
            measured_width = (
                self.font.text_width(str(textbox["text"]))
                if self.font
                else len(str(textbox["text"])) * 8
            )
            textbox["measured_text_width_px"] = measured_width
            textbox["renderer_clipped"] = measured_width > int(textbox["width"])
            textbox["measurement_source"] = (
                "host display glyph advances" if self.font else "8px fixed-cell fallback"
            )
        return preview


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--style", type=Path, required=True)
    parser.add_argument("--font", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--selected", type=int, default=0)
    parser.add_argument("--select-prompt", default=SELECT_PROMPT)
    parser.add_argument("--confirm-prompt", default=CONFIRM_PROMPT)
    args = parser.parse_args()

    font_path = args.font or args.style.with_name("new_m.fnt")
    font = M30Font.load(font_path) if font_path.exists() else None
    preview = UpgradeScreen(
        args.style,
        selected_option=args.selected,
        select_prompt=args.select_prompt,
        confirm_prompt=args.confirm_prompt,
        font=font,
    ).render()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    preview.framebuffer.ppm(args.out)
    args.trace.parent.mkdir(parents=True, exist_ok=True)
    args.trace.write_text(json.dumps(preview.trace, indent=2) + "\n")
    print(
        f"UPGRADE PREVIEW PASS {args.out} selected={args.selected} "
        f"picboxes={len(preview.trace['picboxes'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

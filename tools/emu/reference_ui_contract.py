#!/usr/bin/env python3
"""Check the public ATJ2127 UI-source contract used by the M30 analysis.

This is a source/API cross-check, not a claim that the public driver binary is
the M30 driver.  It records the minimum evidence needed for the M30 B3
classification: B05 owns horizontal scrolling, B16 owns SD-image string
rendering, and the tail restore reads the saved background before DMA.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path


DEFAULT_ROOT = Path("/private/tmp/PD196_ATJ2127")


@dataclass(frozen=True)
class UiSourceContract:
    root: str
    scroll_bank: str
    sdimage_bank: str
    scroll_clear_calls: int
    tail_restore_reads_background: bool
    tail_restore_sets_lcd_region: bool
    tail_restore_dma: bool
    listbox_loads_background_image: bool
    listbox_selects_sdimage_mode: bool


def _read(root: Path, relative: str) -> str:
    path = root / relative
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        raise ValueError(f"missing reference source file: {path}") from exc


def _bank_block(linker: str, bank: str, next_bank: str) -> str:
    start = linker.find(bank)
    end = linker.find(next_bank, start + len(bank))
    if start < 0 or end < 0:
        raise ValueError(f"linker has no bounded {bank} section")
    return linker[start:end]


def inspect(root: Path) -> UiSourceContract:
    linker = _read(root, "case/drv/ui/ui_driver.xn")
    scroll_block = _bank_block(linker, "BANK_B_5", "BANK_B_6")
    sdimage_block = _bank_block(linker, "BANK_B_16", "BANK_B_17")
    if "bank_b_ui_scroll_string.o" not in scroll_block:
        raise ValueError("B05 is not linked to bank_b_ui_scroll_string.o")
    if "bank_b_ui_string_sdimage.o" not in sdimage_block:
        raise ValueError("B16 is not linked to bank_b_ui_string_sdimage.o")

    scroll = _read(root, "case/drv/ui/bank_b_ui_scroll_string.c")
    sdimage = _read(root, "case/drv/ui/bank_b_ui_string_sdimage.c")
    listbox = _read(root, "case/drv/ui/bank_a_ui_show_listbox_2.c")
    clear_calls = scroll.count("clear_remain_pixel_sdimage")
    if clear_calls < 2:
        raise ValueError("scroll source does not show repeated tail-restore calls")
    required_sdimage = (
        "void clear_remain_pixel_sdimage",
        "read_str_bgpic",
        "set_lcd_param_hook",
        "dma_buffer_data_hook",
    )
    missing = [token for token in required_sdimage if token not in sdimage]
    if missing:
        raise ValueError(f"tail-restore source is missing: {', '.join(missing)}")
    required_listbox = ("load_bgpic_sd", "CHAR_FILL_SDIMAGE")
    missing = [token for token in required_listbox if token not in listbox]
    if missing:
        raise ValueError(f"listbox source is missing: {', '.join(missing)}")

    return UiSourceContract(
        root=str(root),
        scroll_bank="B05: bank_b_ui_scroll_string.o",
        sdimage_bank="B16: bank_b_ui_string_sdimage.o",
        scroll_clear_calls=clear_calls,
        tail_restore_reads_background="read_str_bgpic" in sdimage,
        tail_restore_sets_lcd_region="set_lcd_param_hook" in sdimage,
        tail_restore_dma="dma_buffer_data_hook" in sdimage,
        listbox_loads_background_image="load_bgpic_sd" in listbox,
        listbox_selects_sdimage_mode="CHAR_FILL_SDIMAGE" in listbox,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--json", action="store_true", dest="as_json")
    args = parser.parse_args()
    contract = inspect(args.root)
    if args.as_json:
        print(json.dumps(asdict(contract), indent=2))
    else:
        print(
            "REFERENCE UI CONTRACT PASS "
            f"scroll={contract.scroll_bank} sdimage={contract.sdimage_bank} "
            f"clear-calls={contract.scroll_clear_calls} "
            "restore=background-read+lcd-region+dma"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

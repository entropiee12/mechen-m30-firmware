#!/usr/bin/env python3
"""Compare host folder navigation against the real p-code boundary matrix."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "tools" / "ghidra" / "run_folder_nav_matrix_probe.sh"
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from folder_ui import FolderList, FolderRow
from folder_nav_state import FolderNavigationState
from scroll_accel import NEXT, PREVIOUS


PASS_RE = re.compile(
    r"^FOLDER NAV MATRIX PASS app=(?P<app>\w+) mode=(?P<mode>next|previous) "
    r"total=(?P<total>\d+) page=(?P<page>\d+) "
    r"initial_start=(?P<initial_start>\d+) initial_end=(?P<initial_end>\d+) "
    r"cursor=(?P<cursor>\d+) "
    r"start=(?P<start>\d+) end=(?P<end>\d+) active=(?P<active>\d+) "
    r"previous=(?P<previous>\d+) status=(?P<status>\d+) "
    r"update=(?P<update>\d+) redraw=(?P<redraw>\d+) reset=(?P<reset>\d+)"
)


def main() -> int:
    completed = subprocess.run(
        ["bash", str(MATRIX)],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode:
        print(completed.stdout, end="")
        print(completed.stderr, end="")
        return completed.returncode

    records = [PASS_RE.match(line) for line in completed.stdout.splitlines()]
    records = [record for record in records if record is not None]
    if len(records) != 28:
        raise SystemExit(f"expected 28 matrix records, got {len(records)}")

    active_matches = 0
    wrap_matches = 0
    state_matches = 0
    for record in records:
        assert record is not None
        app = record.group("app")
        mode = record.group("mode")
        total = int(record.group("total"))
        page = int(record.group("page"))
        initial_start = int(record.group("initial_start"))
        initial_end = int(record.group("initial_end"))
        cursor = int(record.group("cursor"))
        expected_active = int(record.group("active"))
        direction = NEXT if mode == "next" else PREVIOUS

        state = FolderNavigationState(
            page_size=page,
            start=initial_start,
            end=initial_end,
            total=total,
            active=cursor,
            anchor=cursor,
            previous=cursor,
        )
        expected_state = {
            name: int(record.group(name))
            for name in ("start", "end", "active", "previous", "status", "update", "redraw", "reset")
        }
        actual_state = state.apply(mode)
        if actual_state != expected_state:
            raise SystemExit(
                f"state mismatch app={app} mode={mode} total={total} cursor={cursor}: "
                f"host={actual_state} pcode={expected_state}"
            )
        state_matches += 1

        view = FolderList(
            tuple(FolderRow(f"row-{index}") for index in range(total)),
            selected_index=cursor - 1,
        )
        result = view.move_event(direction, 0)
        actual_active = int(result["selected_index"]) + 1
        expected_wrap = (
            mode == "next" and expected_active < cursor
        ) or (
            mode == "previous" and expected_active > cursor
        )
        if actual_active != expected_active:
            raise SystemExit(
                f"active mismatch app={app} mode={mode} total={total} "
                f"cursor={cursor}: host={actual_active} pcode={expected_active}"
            )
        active_matches += 1
        if bool(result["wrapped"]) != expected_wrap:
            raise SystemExit(
                f"wrap mismatch app={app} mode={mode} total={total} "
                f"cursor={cursor}: host={result['wrapped']} pcode={expected_wrap}"
            )
        wrap_matches += 1

    print(
        "FOLDER NAV HOST COMPARE PASS "
        f"cases={len(records)} state={state_matches} active={active_matches} wrap={wrap_matches}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

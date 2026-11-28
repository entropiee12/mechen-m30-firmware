#!/usr/bin/env python3
"""Cross-check the host EQ contract against extracted firmware bytes."""

from __future__ import annotations

import argparse
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "sim"))

from m30_model import (  # noqa: E402
    FIRMWARE_EQ_CURRENT_PROFILE,
    FIRMWARE_EQ_PROFILES,
    FirmwareEqProfile,
    find_firmware_eq_profile,
)


ENGINE_GAIN_TABLE = 0x440C
ENGINE_FREQUENCY_TABLE = 0x43EC
ENGINE_Q_TABLE = 0x43FC
SETTING_EQ_TABLE = 0x5A94
ENGINE_ROW_STRIDE = 7
SETTING_ROW_STRIDE = 16
MAINMENU_LANGUAGE_DIRECTORY = 0x580
MAINMENU_LANGUAGE_ENTRY_SIZE = 16
MAINMENU_ENGLISH_LANGUAGE_INDEX = 2
MAINMENU_STRING_COUNT = 34
MAINMENU_EQ_LABEL_FIRST = 0x12
MAINMENU_EQ_LABEL_COUNT = 8


def signed_bytes(data: bytes) -> tuple[int, ...]:
    return tuple(value - 256 if value >= 128 else value for value in data)


def u16_rows(data: bytes, offset: int, count: int) -> tuple[int, ...]:
    return struct.unpack_from("<" + "H" * count, data, offset)


def mainmenu_eq_labels(path: Path) -> tuple[str, ...]:
    """Read the eight English EQ selector labels from mainmenu.sty's RU3 table."""
    data = path.read_bytes()
    if data[:4] != b"RU3\x19":
        raise ValueError(f"not an RU3 resource file: {path}")

    language_entry = (
        MAINMENU_LANGUAGE_DIRECTORY
        + MAINMENU_ENGLISH_LANGUAGE_INDEX * MAINMENU_LANGUAGE_ENTRY_SIZE
    )
    if language_entry + MAINMENU_LANGUAGE_ENTRY_SIZE > len(data):
        raise ValueError("mainmenu.sty language directory is truncated")

    table_offset, table_size, version, string_count, language_index, _reserved = (
        struct.unpack_from("<IIHHHH", data, language_entry)
    )
    if version != 1 or string_count != MAINMENU_STRING_COUNT:
        raise ValueError("mainmenu.sty English string table has an unexpected layout")
    if language_index != MAINMENU_ENGLISH_LANGUAGE_INDEX:
        raise ValueError("mainmenu.sty language directory entry 2 is not English")
    if table_size < string_count * 8 or table_offset + table_size > len(data):
        raise ValueError("mainmenu.sty English string table exceeds the resource")

    labels = []
    for string_id in range(
        MAINMENU_EQ_LABEL_FIRST,
        MAINMENU_EQ_LABEL_FIRST + MAINMENU_EQ_LABEL_COUNT,
    ):
        record_offset = table_offset + string_id * 8
        string_offset, string_length = struct.unpack_from("<II", data, record_offset)
        if string_length % 2 or string_offset + string_length > len(data):
            raise ValueError(f"mainmenu.sty string {string_id} has invalid bounds")

        try:
            label = data[string_offset : string_offset + string_length].decode("utf-16le")
        except UnicodeDecodeError as exception:
            raise ValueError(f"mainmenu.sty string {string_id} is not UTF-16LE") from exception

        labels.append(label.rstrip("\x00"))

    return tuple(labels)


def verify(
    mengine_path: Path,
    setting_path: Path,
    profile: FirmwareEqProfile = FIRMWARE_EQ_CURRENT_PROFILE,
    mainmenu_sty_path: Path | None = None,
) -> None:
    mengine = mengine_path.read_bytes()
    setting = setting_path.read_bytes()

    frequencies = u16_rows(mengine, ENGINE_FREQUENCY_TABLE, len(profile.band_hz))
    if frequencies != profile.band_hz:
        raise AssertionError(f"frequency table {frequencies!r} != model {profile.band_hz!r}")

    q_values = u16_rows(mengine, ENGINE_Q_TABLE, len(profile.q_values))
    if q_values != profile.q_values:
        raise AssertionError(f"Q table {q_values!r} != model {profile.q_values!r}")

    for selector in range(7):
        offset = ENGINE_GAIN_TABLE + selector * ENGINE_ROW_STRIDE
        actual = signed_bytes(mengine[offset : offset + ENGINE_ROW_STRIDE])
        expected = profile.presets[selector].gain_int8
        if actual != expected:
            raise AssertionError(
                f"mengine selector {selector} {actual!r} != model {expected!r}"
            )

    if profile.setting_custom_row is not None:
        custom_offset = SETTING_EQ_TABLE + 7 * SETTING_ROW_STRIDE
        custom_row = signed_bytes(
            setting[custom_offset : custom_offset + len(profile.setting_custom_row)]
        )
        if custom_row != profile.setting_custom_row:
            raise AssertionError(
                f"setting Custom resource row {custom_row!r} "
                f"!= model {profile.setting_custom_row!r}"
            )

    if mainmenu_sty_path is not None:
        if profile.mainmenu_labels_en is None:
            raise ValueError(
                f"no verified mainmenu label table for profile {profile.name}"
            )

        actual_labels = mainmenu_eq_labels(mainmenu_sty_path)
        expected_labels = profile.mainmenu_labels_en
        if actual_labels != expected_labels:
            raise AssertionError(
                f"mainmenu EQ labels {actual_labels!r} != model {expected_labels!r}"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mengine_ap", type=Path)
    parser.add_argument("setting_ap", type=Path)
    parser.add_argument(
        "--profile",
        choices=tuple(FIRMWARE_EQ_PROFILES),
        default=FIRMWARE_EQ_CURRENT_PROFILE.name,
    )
    parser.add_argument("--mainmenu-sty", type=Path)
    args = parser.parse_args()
    profile = find_firmware_eq_profile(args.profile)
    verify(args.mengine_ap, args.setting_ap, profile, args.mainmenu_sty)
    print(
        "EQ BINARY PROBE PASS "
        f"profile={profile.name} bands=7 gain_rows=7 "
        f"setting_custom_row={profile.setting_custom_row is not None} "
        f"mainmenu_labels={args.mainmenu_sty is not None}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

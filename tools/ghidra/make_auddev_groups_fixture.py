#!/usr/bin/env python3
"""Create a deterministic 0xf0-byte input for the aud_dev DAE group probe."""
import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--drc", choices=("enabled", "disabled"), default="enabled")
    args = parser.parse_args()

    data = bytearray(0xF0)
    data[0x0C] = int(args.drc == "enabled")
    data[0x07] = data[0x09] = data[0x0A] = data[0x0B] = data[0x0D] = 1
    data[0x90:0x92] = (0x4567).to_bytes(2, "little")
    data[0x92] = 5
    data[0x98:0x9A] = (0x1234).to_bytes(2, "little")
    data[0x9A] = 0x21
    data[0x9C] = 0x2A
    data[0x9D] = 3
    data[0x9E:0xA0] = (0x4444).to_bytes(2, "little")
    data[0xA2:0xBA] = bytes(range(0x31, 0x49))
    data[0xBA:0xBC] = (0x1111).to_bytes(2, "little")
    data[0xBC:0xBE] = (0x2222).to_bytes(2, "little")
    data[0xBE:0xC0] = (0x3333).to_bytes(2, "little")

    for band in range(16):
        start = 0x10 + band * 8
        data[start : start + 8] = bytes((0x10 + band, 0, 0x20, 0, 0, 0, 1, 0))

    args.output.write_bytes(data)


if __name__ == "__main__":
    main()

# M30 DSP member boundary

This note records only byte-level facts about the extracted `.dsp` members.
It does not claim that the descriptor words are decoded, and no DSP payload is
executed by the probe.

## Reproducible check

```sh
PYTHONPATH=tools/emu python3 tools/emu/dsp_binary_probe.py \
  --analysis-root analysis
python3 -m unittest tools/emu/test_dsp_binary_probe.py
```

All 18 DSP members are present in the six local extractions (`stock`,
`community`, `first-pass-verify`, `v1.101.11`, `v1.101.12`, and `v5`). Every
member is byte-identical across those six copies. The probe therefore gives us
a safe regression boundary: a community change did not alter decoder/DSP
members in this corpus.

## Header boundary

Every member starts with the four bytes `79 71 68 78` (`yqhx`) at file offset
`0x00`. The four bytes at offset `0x04` are not another `yqhx` marker. This is
different from the resident driver/AP container parsed by `tools/emu/yqhx.py`,
where the marker is at offset `0x04`; treating a DSP member as a resident YQHX
image would therefore be an unsafe false match.

The first eleven little-endian words of the three highest-priority members are
recorded below exactly as observed. Their meanings remain intentionally
unassigned.

| Member | Size | Header words at `0x00..0x28` |
|---|---:|---|
| `admp3.dsp` | `0x11200` | `0x78687179, 0x20040000, 0x00000400, 0x00000120, 0x00020000, 0x00000000, 0x00000000, 0x00000000, 0x00000600, 0x00006878, 0x00029000` |
| `aewav.dsp` | `0x1600` | `0x78687179, 0x20042800, 0x00000600, 0x00000120, 0x00020000, 0x00000400, 0x000001a8, 0x0002a000, 0x00000800, 0x00000d88, 0x00029000` |
| `audiopp.dsp` | `0x5400` | `0x78687179, 0x20040201, 0x00000600, 0x00002fd8, 0x00034200, 0x00000400, 0x00000148, 0x00037e00, 0x00000000, 0x00000000, 0x00000000` |

These values establish a descriptor shape and version stability, not a load
plan. In particular, the existing resident `YQHX` three-segment parser must
not be reused until a DSP-specific loader/caller proves the field order.

## Codec markers

`aewav.dsp` contains `RIFF`, `WAVE`, `fmt `, and `data` at offsets `0xd0a`,
`0xd20`, `0xd34`, and `0xe02`. `addsd.dsp` contains `PROP`, `DBOD`, and
`CHAN` at `0xdd0`, `0xdfc`, and `0xe02` (and `DSD ` at multiple offsets).
These are strong format-data markers, but they do not prove the runtime call
graph or whether the corresponding member is a decoder, parser, or helper.

## Consequence for emulation

The safe emulator boundary is currently a non-executing DSP asset registry:
identify the member, verify its hash/header, and expose its raw payload to a
future loader model. No DSP instruction emulation or fade/ASRC conclusion is
claimed from these bytes alone. The next evidence target is the caller/loader
that selects these members and supplies the EQ/playback parameter block.

# `dae_cfg` factory-data mapping

Read-only static evidence for the `0xf0`-byte `dae_cfg` structure.

## Inputs

- `config.ap`: SHA-256
  `5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580`
- `config.bin`: SHA-256
  `900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19`
- Ghidra decompile: [`factory.c`](factory.c), SHA-256
  `496641797ac0873479d3b16ca7a6d41b19bb8c8b0e7da9484dc7e6d78c7cb3b8`
- State / mode decompile: [`state.c`](state.c), SHA-256
  `38f6ed4b215d27893ea3f5129c9be9da3326da593882403a8f8569387ee70aa4`.

`factory.c` was generated with Ghidra `LoadAP.java` and `DumpAt.java` for
`FUN_40dd4d60` and `FUN_40dd4e0c`. In `config.bin`, an item index entry is at
`0x10 + 8*item_id` and contains little-endian payload offset and length. The
16 band payloads are 12 bytes each; decompile passes the payload to an
8-byte copy starting at payload offset `+4` and structure offset
`+0x10 + 8*i`.

## Verified config-to-structure mapping

- `FUN_40dd4d60`: first byte of items `0x82` and `0x85` goes to structure
  offsets `+3` and `+7`.
- `FUN_40dd4f50`: config items `0x83` and `0x84` conditionally populate `+4`
  or `+5`; the other field is zeroed. It also clears `+6` and conditionally
  calls `FUN_40dd4e0c`.
- For `i=0..15`, item `0x86+i` payload bytes `+4..+11` are copied to
  structure `+0x10 + 8*i`.
- First bytes of items `0x98` and `0xa5` go to `+0x0c` and `+0x0d`.
- Item `0xa6` payload u16 values at `+4`, `+6`, and `+8` go to `+0xba`,
  `+0xbc`, and `+0xbe`.
- `FUN_40dd4e0c`, when structure byte `+0x0c` is nonzero, selects either
  item family `0x99..0x9c` or `0x9f..0xa2` from its second parameter. It
  copies three 8-byte records to `+0xa2`, `+0xaa`, `+0xb2`, sets each record's
  last u16 to zero, and uses the selected `0x9c`/`0xa2` value for `+0xe2` and
  a signed upper adjustment.

## Items `0x86..0x95`

The eight-byte payload is interpreted as `{frequency u16 LE, Q u16 LE, gain
s16 LE, flag u16 LE}`. Values below are raw decoded fields; units and DSP
meaning are not established.

| Item | Frequency | Q | Gain | Flag |
|---|---:|---:|---:|---:|
| `0x86` | 30 | 7 | -10 | 2 |
| `0x87` | 30 | 7 | -10 | 2 |
| `0x88` | 47 | 7 | 50 | 1 |
| `0x89` | 80 | 7 | 43 | 1 |
| `0x8a` | 125 | 7 | 53 | 1 |
| `0x8b` | 160 | 10 | 0 | 1 |
| `0x8c` | 200 | 5 | 28 | 1 |
| `0x8d` | 315 | 10 | 0 | 1 |
| `0x8e` | 467 | 10 | 40 | 1 |
| `0x8f` | 1000 | 7 | 26 | 1 |
| `0x90` | 1500 | 5 | 15 | 1 |
| `0x91` | 5000 | 7 | 40 | 1 |
| `0x92` | 7000 | 7 | 60 | 1 |
| `0x93` | 8000 | 40 | -80 | 1 |
| `0x94` | 11000 | 7 | 20 | 1 |
| `0x95` | 14000 | 5 | 43 | 1 |

This establishes the config item bytes and the AP's copy layout. It does not
establish that these values are an audible “FullSound” profile, speaker
compensation, or active before an EQ message is sent.

## Enable flag and DSP image choice

The additional decompile in [`state.c`](state.c) shows:

- `FUN_40dd4c00` loads item `0x96` into state `+0x18` and item `0x97` into
  state `+0x19`. The config getter's default values are 1 and 0 respectively;
  item `0x97` has range 0 through 7. Their raw 8-byte records are
  `01 00 02 00 00 00 01 00` and `00 00 00 00 07 00 01 00`.
- `FUN_40dd4f50` uses the `+0x0c` mode byte to choose whether item `0x83`
  (default 60) goes to DAE `+4` or item `0x84` (default 0) goes to `+5`.
- Raw payloads: `0x83 = 3c 00 00 00 78 00 01 00`,
  `0x84 = 00 00 00 00 78 00 01 00`, `0x98 = 00 00 02 00 00 00 01 00`.
- `FUN_40dd4fe8` maps state `+0x18 == 1` to DAE buffer `+2=1,+3=0`, otherwise
  `+2=0,+3=1`, then creates the shared `dae_cfg` buffer.
- The previously inspected `mmm_pp.al` loader chooses `audiopp.dsp` when
  shared byte `+2` is nonzero and `adppext.dsp` when it is zero. Together,
  these code paths show the default config selects `audiopp.dsp` for this
  shared-buffer setup.
- Item `0x98` begins with `0`; `FUN_40dd4d60` places it at DAE `+0x0c`, and
  the MEngine builder returns early when its structure byte `+0x0c` is 1.
  The field's product meaning is not known.

The byte offsets `+0x18/+0x19` in the config state and the MEngine state have
not been shown to be the same RAM object or directly connected. Therefore the
config defaults alone do not prove the MEngine runtime active flag or selector
mirror values at `0x9fc15db4/0x9fc15db5`.

The MEngine overlay has a corresponding initializer at `FUN_60b10f7c`; its
hash-matched decompile independently confirms the same config-item-to-DAE
field stores. A later report says both overlays call that initializer only
when persistent record ID `0xf0000`, magic `0x55b9`, is absent or invalid, so
config.bin values would be reapplied only on cold initialization/reset. The
MEngine initializer body is retained in
[`cross-check evidence`](../auddev-dae-gates-2026-11-24/README.md); its
persistent-wrapper call site was not independently established.

## Persistent `0x38`-byte settings block

`FUN_40dd4c00` also initializes the config overlay's settings structure. The
Ghidra output shows byte stores at these offsets; config records supply default
values through the config getter:

| Block offset | Source / initial value |
|---|---|
| `+2..+6` | items `0xc8..0xcc` |
| `+7` | item `0xcd` |
| `+8` (u16) | constant 600 |
| `+0xa..+0xb` | items `0xdb`, `0xcd` |
| `+0xc..+0x17` | items `0xce`, `0xcf`, `0xd0..0xd3`, `0xd8`, `0xd9`, constant item ID 6, `0xdc..0xde` in the decompiled store order |
| `+0x18..+0x1b` | items `0x96`, `0x97`, `0xdf`, `0xe0` |
| `+0x1c` | constant `0x32`; `+0x1d..+0x1f` zero |
| `+0x21` | derived from `+8` and a constant; `+0x22/+0x23` zero |
| `+0x24..+0x26` | items `0xd4..0xd6` |
| `+0x2c..+0x37` | no stores in this initializer |

`FUN_40dd4f50` subsequently normalizes `+4/+5`: depending on `+3`, `+0x0c`,
and its second argument, it may clear both or replace one with config item
`0x83`/`0x84`; it always clears `+6` on its active path. Treat the table above
as the first initializer's writes, not the final values after this helper.

The config records are u16 sequences. Two observed shapes are numeric
`[default, minimum, maximum, step]` (item `0x83`: `[60,0,120,1]`) and
enumerated `[default, count, values...]` (item `0x01`: `[1,5,0,1,2,3,4]`).
These examples support the schema hypothesis; item labels are absent, and this
is not an independently validated semantic classification of all 121 items.

The host model in `tools/sim/dsp_model.py` also applies the verified
`FUN_40dd4f50(param, 1)` normalization branch and its `FUN_40dd4e0c(param, 0)`
secondary-record path. The latter copies items `0x99..0x9b`, clamps their
first signed words using the selected item `0x9c`, and updates the documented
derived fields. Its selection of item `0x99..0x9c` follows the observed
zero-valued second argument; the alternative `0x9f..0xa2` branch is not
modeled. The retained inputs do not include item `0x9c`'s raw payload, so the
model's use of its first little-endian u16 as the signed adjustment is an
explicit format assumption, not a hash-verified payload result. The initializer
still preserves fields it does not write.

The `+0x2c..+0x32` Custom bytes are not populated from the `setting.ap`
seven-value default table by `FUN_40dd4c00`. The existing cold/invalid-magic
`0x38` block path clears the structure before calling this initializer, so
those seven bytes remain zero on that path. This does not prove the default
table is globally unused or that a device with a valid saved block has zero
Custom gains.

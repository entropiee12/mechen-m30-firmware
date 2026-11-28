# Playlist launch-parameter path

Read-only Ghidra decompiles of the community APs:

- `playlist.ap` SHA-256: `184f2c201537e6657024a2a5a054d12fe4a7f134e06205f0786a8d71d2a29403`
- `manager.ap` SHA-256: `d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a`
- `music.ap` SHA-256, previously retained: `662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3`
- Ghidra 12.2 decompiles: [`playlist-functions.c`](playlist-functions.c), SHA-256
  `81dd28dcdc38aee0c1805a05973415ab738e2dfa3612353941d1dc92db780d8d`, and
  [`manager-functions.c`](manager-functions.c), SHA-256
  `904aed7088c28dd34b315fe7e0d7bcb11ab1f7497fb0eb5a6208c9ae7594bc1e`
- Ghidra logs: [`playlist-ghidra.log`](playlist-ghidra.log) and
  [`manager-ghidra.log`](manager-ghidra.log)
- Reproduce with `bash tools/ghidra/run_playlist_param_probe.sh <community-fw-dir>`.

`music.ap`'s retained return dispatcher sends app ID 10 (`playlist.ap`) with
parameter `0x42`, `0x82`, or `0xc2`. `manager.ap`'s `FUN_7f196200` handles the
`0x0201` message and reads the launch parameter from message byte `+5`; this
establishes the AP-side handoff to its resident app-start service. How the
missing resident ROM loader forwards it to the app entry is not visible in the
AP images.

In `playlist.ap`, `FUN_40054c54` starts its update block only if parameter bit
`0x40` is set and resident service op `9` does not return `-1`. Therefore:

| Parameter | Bit `0x40` | Low five bits | Result in `FUN_40054c54` |
|---:|---:|---:|---|
| `0x42` | set | `2` | Enters the conditional update path |
| `0x82` | clear | `2` | Skips that update path |
| `0xc2` | set | `2` | Enters the conditional update path |
| `0x4e` | set | `14` | Enters the same update gate; low-five-bit mode differs |

For the bit-`0x40` case, the code's shifted-bit loop selects callback-table
index 1. The raw table words at `0x40055204/0x40055208` are
`0x40055195/0x400551d1`, pointing to `FUN_40055194` and `FUN_400551d0`.
The parallel mask table has MUSIC.LIB at index 0 and M3U.LIB at index 1, as
documented in [`library-builder.md`](../../library-builder.md). The bit-
`0x40` row selects the M3U.LIB callback in the parameter-index table. The
complete observed update sequence still reaches both the MUSIC.LIB callback
and the M3U.LIB callback; the bit identifies the table selection, not an
exclusive callback path. `0x42` and `0xc2` also have the same low-five-bit
value, while `0x4e` passes 14 to the function's final mode callback.

## P-code boundary probe

`bash tools/ghidra/run_playlist_param_gate_probe.sh <hash-matching-playlist.ap>`
executes the real `FUN_40054c54` MIPS16 code and changes the synthetic return
from the resident call `(1,0,0,9)`. The retained trace is
[`playlist-gate-pcode.log`](playlist-gate-pcode.log), SHA-256
`fd0684f565cd4d2dd919f489eb133d114136bb7750d25f42767cad85557c8e30`.

Observed call sequences:

- `0x42`, `0xc2`, and `0x4e` with op-9 return `0`: the code reaches the
  `MUSIC.LIB` callback, then the `M3U.LIB` callback.
- `0x82`: op 9 is not called and neither builder callback is reached.
- `0x42` with op-9 return `-1`: the op-9 call occurs, but neither builder
  callback is reached.

This is a controlled caller-contract experiment, not execution of the ROM
method. The probe returns zero for other resident calls, returns one from the
directory-walker stub, and intercepts file-create/finalize and both builder
callbacks with success. It therefore shows which AP paths follow those
synthetic results; it does not show the real op-9 meaning, filesystem effects,
or how the resident launcher forwards an app parameter. No device test was
performed, and the card-insertion cause remains unproven.

There is a cross-firmware semantic candidate: the public ATJ2127
[`actions_flash` RE_NOTES](https://github.com/kilicdev/actions_flash/blob/main/RE_NOTES.md#L250-L259)
labels import 15, `a3=9`, “Close Driver / File.” M30's call is to the matching
kernel import entry and uses `(1,0,0,9)` immediately before its index-update
gate, which is compatible with that label. The external note describes US212D
V1.101.25, not M30; the M30 ROM implementation is absent, so this remains a
cross-firmware hypothesis rather than a verified M30 method meaning.

# Decoder write-callback initialization

This is a static Ghidra finding. It identifies the community fix documented as
`M30-STATIC-002`; it does not execute a decoder or prove audio output on a
device.

## Reproduction

```sh
bash tools/ghidra/run_decoder_callback_probe.sh
```

The probe compares the V1.101.12 `mengine.ap` against the community module and
checks both the decompiled command setup and the raw MIPS16 tail.

## Recovered boundary

The relevant function is `FUN_60150fec`. It sends an undocumented command
`0x2a` to the music engine:

- baseline passes one word from `_UNK_601510c8`, whose value is
  `0xbfc11ab5`;
- community constructs a two-word block, preserving that pointer in the first
  word and adding `_UNK_60151118` in the second word;
- community's `_UNK_60151118` is the odd MIPS16 entry `0x6015110f`.

The community patch replaces the baseline instruction at `0x60151032` with a
branch to `0x60151104`. That short tail loads both pointers, stores them into
the command block, and branches back to the unchanged continuation at
`0x60151036`.

The odd pointer normalizes to `0x6015110e`. Ghidra decompiles that entry as:

```c
undefined4 FUN_6015110e(void)
{
  return 0xffffffff;
}
```

Therefore the previously empty/indeterminate decoder write-callback slot is
initialized to a defined rejecting callback. This matches the community
release description of `M30-STATIC-002` and explains why the callback fix is in
`mengine.ap`, while the `mmm_bp.al` decoder/fade module remains byte-identical
to stock.

## Emulator consequence

The callback is a defensive failure boundary, not a new audio feature. The
host emulator now models it in `tools/sim/decoder_output.py`: an unconfigured
slot and the known community rejecting callback both return hard `-1`, accept
zero bytes, and never turn the payload into PCM. Unknown callback pointers
remain rejected rather than being executed speculatively. The current QEMU
layer remains a static/UI boundary and does not execute this proprietary
decoder path; the probe is consequently still the authoritative binary
regression check.

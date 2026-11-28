# `mmm_mp.al` DSP filename loader boundary

This is a static Ghidra finding. It does not execute an audio decoder and it
does not propose a firmware patch.

## Reproducible check

```sh
bash tools/ghidra/run_mmm_dsp_loader_probe.sh \
  analysis/community/fw/mmm_mp.al
```

The `.al` member is a normal offset-4 YQHX image for the resident/overlay
loader (`mmm_mp.al` resident segment: file `0x400`, size `0x454`, destination
`0xbfc13500`; BSS `0x9fc13b04`, size `0x1e0`; entry `0xbfc13865`). Ghidra's
`LoadAP.java` maps 14 blocks and recovers the selection function at
`0x68312d00`.

## Proven selector and table

`FUN_6815327c` calls the stream parser `FUN_68152d70` with its sub-structure
at `param_1 + 0x80`. The parser writes the format code into the first word of
that sub-structure; the recovered raw/PCM branch explicitly writes `6` to the
same field. Later, `FUN_68312d00` reads the field at `param_1 + 0x80`,
multiplies the value by `0x10`, and uses that stride against a literal table at
`0x68312f60`. The table contains fixed-width 16-byte names. The first entries
are:

```text
adMP3.dsp
adWMA.dsp
adWAVH.dsp
adFLAC.dsp
adAPE.dsp
adSBC.dsp
adAAC.dsp
adDSD.dsp
adAAX.dsp
adOGG.dsp
adALAC.dsp
adWMAL.dsp
adTTA.dsp
audiopp.dsp
adppext.dsp
adppdrc.dsp
```

The names are stored with the mixed-case spelling above; the extracted FAT
members in this repository are lower-case, which is compatible with the
device's case-insensitive FAT lookup but is not silently treated as proven
for every filesystem path.

The same function also selects one of three format-dependent addresses based
on a byte in the loaded state and performs the open/close-style calls through
resident pointers. Those calls establish a loader boundary, not the exact
decoder ABI; the probe therefore checks the table and selector shape without
assigning meanings to the indirect calls.

## Emulator consequence

The emulator now models a safe format-selection step in
`tools/sim/dsp_model.py`: map a verified format index to one of these names,
normalize the extracted lower-case member name, and check the offset-zero
`yqhx` descriptor plus a SHA-256 fingerprint. If the selected member is not in
the local corpus (the table includes entries such as `adSBC.dsp` that are not
among the extracted members), the model returns an unavailable selection. It
never substitutes another codec and never executes an opaque DSP payload.

This closes the immediate “where does the index come from?” gap: it is a
parser-produced format code, not a UI filename index. The remaining high-value
trace is the message/parameter path that supplies the DSP EQ/playback block
after this codec selection.

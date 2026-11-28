# `mmm_bp.al` terminal state and `music.ap` mode-4 advance

## Result

The investigated binaries do **not** establish a path from the `mmm_bp.al`
status-3 terminal branch to `music.ap`'s mode-4 track-index advance. The
strongest current result is a bounded negative one: the two recovered paths
terminate or dispatch through separate state and resident-service boundaries,
and the hash-pinned modules contain no aligned direct pointer/state literals
joining them. This does not exclude computed pointers, runtime-populated
callback tables, or a resident service translating decoder state into a music
event.

## Evidence on each side

In `mmm_bp.al`, `FUN_bfc216a8` reads status indirectly through the pointer at
the decoder context's `+0xd0`. If it reads `3`, it stores state `2` in the
context at `+0x24`, copies two halfwords from the object referenced at `+0x78` to
`+0xdc/+0xde`, and returns. That branch contains no direct call to the
`music.ap` advance callback or to a callback slot. A preceding call to
`FUN_bfc27520` processes the decoder input ring; it is not an output-drain or
track-advance callback. The verified instruction order and callback findings
are in [the decoder fade note](findings-mmm-bp-fade.md).

In `music.ap`, `FUN_400d4edc` loads the EOF/tick callback pointer from literal
`0x400d509c` and calls `FUN_40494e94`. At the final-item boundary, community
mode 4 reaches `FUN_405151a0(0)` and updates the music selection; stock mode 4
takes the end-notification/UI route. The dynamic probe supplies a synthetic
progress value through the shared resident service at `0xbfc164dc`; playback
RAM and that service result are controlled inputs. The entry has five
verified `music.ap` call sites, including a distinct two-argument caller; the
`usound.ap` and `udisk.ap` callers also pass event/result buffers. It is not
established as a dedicated snapshot API. Thus the call into mode 4 is real AP
execution, but the decoder status is not among the probe's inputs. See
[the ASRC/EOF note](findings-asrc-drain.md).

The two contexts are distinct in the recovered image references: `mmm_bp.al`
uses its context/callback slots rooted at `0x9fc21b00/0x9fc21b04`, while the
music/engine path uses its separately recovered playback-state objects. The
status-3 branch does not visibly publish its status into the music AP's
mode/index fields. No direct reference to the decoder entry `0xbfc216a9` or
its context/callback slots was found in `music.ap`, `mengine.ap`, or
`mmm_mp.al`; no direct music/engine state literal was found in `mmm_bp.al`.

## Reproducible bounded check

With the extracted stock/community firmware trees available locally:

```sh
python3 tools/ghidra/check_mmm_bp_music_bridge_literals.py /path/to/analysis
bash tools/ghidra/run_mmm_bp_fade_probe.sh /path/to/analysis/community/fw/mmm_bp.al
M30_REPO_ROOT=/path/to/repository bash tools/ghidra/run_asrc_music_tick_probe.sh
```

The scanner pins SHA-256 for `music.ap`, `mengine.ap`, `mmm_bp.al`, and
`mmm_mp.al` in both variants, then scans aligned little-endian 32-bit words
for the direct code/context/state literals listed above. Both variants pass
with no direct cross-module literal hit. Its scope is deliberately narrow: it
does not disassemble every import table or rule out unaligned/computed values.
The other two commands recheck the decoder terminal code and the real
music-tick-to-mode-4 callback edge; both use only local firmware inputs and
disposable Ghidra projects.

## Missing bridge

The most useful next boundary is the owner that writes the indirect status
pointer at decoder context `+0xd0`, plus the full resident-service ABI behind
`0xbfc164dc`. Call sites across `music.ap`, `usound.ap`, and `udisk.ap` are
mapped in
[the ASRC/EOF note](findings-asrc-drain.md#resident-entry-0xbfc164dc-call-site-boundary),
but its ROM implementation is absent, and callers use both event/result
buffers and a two-argument form. The current p-code probe stubs only the two
calls in the tick/EOF path, and the decoder analysis does not recover the
status pointer's producer. Recovering those endpoints may show a shared engine
event or state update; until then, connecting status 3 to the mode-4 change
would be speculation. The resident call with selector `0x1c` in `mmm_bp` and
the `mmm_mp` command byte `0x1c` are separate interfaces and are not treated as
equivalent based on the numeric value alone.

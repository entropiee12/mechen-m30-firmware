# `mmm_bp.al` fade/buffer boundary

This is a static finding. It is relevant to the user's observation that some
tracks lose their final fade-out, but it is not yet a claim that the firmware
fade algorithm has been reconstructed.

## Reproducible checks

```sh
PYTHONPATH=tools/emu python3 tools/emu/mmm_bp_binary_probe.py \
  --analysis-root analysis
bash tools/ghidra/run_mmm_bp_fade_probe.sh analysis/community/fw/mmm_bp.al
bash tools/ghidra/run_mmm_bp_fade_probe.sh analysis/stock/fw/mmm_bp.al
```

The binary probe finds the same markers at the same offsets in all six local
corpora (`stock`, `community`, `first-pass-verify`, `v1.101.11`, `v1.101.12`,
and `v5`):

| File offset | Marker | Mapped address |
| ---: | --- | ---: |
| `0x500` | `dsp dec error:` | `0xbfc21500` |
| `0x510` | `dsp dec slow!!` | `0xbfc21510` |
| `0x674` | `set_fast step:` | `0xbfc21674` |
| `0x684` | `set_slow step:` | `0xbfc21684` |
| `0x864` | `not enough data fade out!` | `0xbfc21864` |
| `0x880` | `buffer remain:` | `0xbfc21880` |
| `0xb70` | `fade out:` | `0xbfc27470` |
| `0xb7c` | `fade in:` | `0xbfc2747c` |
| `0xd8c` | `frame space:` | `0xbfc2768c` |

The YQHX image has two loaded segments relevant here: file `0x400..0xaf0`
maps to `0xbfc21400`, and file `0xb00..0x1700` maps to `0xbfc27400`.

## Ghidra boundary

`FUN_bfc27400` and adjacent small routines pass the `fade out:`/`fade in:`
strings to an external logging/diagnostic call with mode `0x41`, while updating
state pointers around `param + 0xbc`, `+0xc0`, `+0xc4`, `+0xc8`, and `+0xcc`.
`FUN_68021c8c` initializes decoder state and selects DSP-related strings;
`FUN_680a1ed4` handles buffer/control state, including `0x80` and `0x400`
buffer-size cases.

The actual fade-step path is stronger than the string evidence alone:

- `FUN_bfc21590` branches on state byte `param + 0xba`: state `2` emits the
  `set_slow step:` diagnostic, while state `3` emits `set_fast step:`. Both
  paths advance the step counter at `param + 0x8a` and update a shared
  time/level value using a 100-step phase.
- `FUN_bfc216a8` is the surrounding decoder/buffer state machine. It calls
  `FUN_bfc21530` when the end-state transition is reached, emits
  `not enough data fade out!` and `buffer remain:` at its failure/progress
  branches, and then feeds the buffer loop through `FUN_bfc276ac`.
- `FUN_bfc27400` records the explicit fade-in/fade-out control event and
  updates the shared state pointers used by the step path.
- `FUN_bfc276ac` requests a `0x200`-byte decoder block and advances a ring of
  `0x20`-byte slots while decrementing the available-count state.
  `FUN_bfc274c8` is the corresponding wrap-around reader: it refuses a read
  larger than the available count and splits a read at the ring end.
- `FUN_bfc27520` consumes the ring state and calls the shared callback with
  fade/control modes when the frame/buffer boundary changes. Its final output
  scaling uses the current sample/level fields, so this is the first concrete
  bridge between the fade state machine and the buffered output path.

The callback boundary is now anchored to the literal pool rather than only to
Ghidra's `_UNK_` names: `0xbfc21698`, `0xbfc27488`, `0xbfc274c4`, and
`0xbfc276a0` contain the odd MIPS16 value `0xbfc21a3d`; the buffer state at
`0xbfc2769c` contains `0x9fc21b00`; and the ring helpers use the odd entries
`0xbfc274c9` and `0xbfc2748d` from `0xbfc2772c` and `0xbfc27730`. The latter
two normalize to the already recovered even-entry routines `FUN_bfc274c8` and
`FUN_bfc2748c`. The `0xbfc21a3d` target itself does not decompile as a valid
function in this module: the aligned instruction probe sees `li` followed by
`break 0` and zero padding at `0xbfc21a3c`. Therefore the static value is not
treated as a callback body. It is consistent with a runtime-relocated/filled
slot, but that part is an inference; the slot remains unsupported and is not a
safe patch site.

The whole-module `DumpC` pass now also checks the `+0xd0` status field across
the recovered function set. It requires at least the 30 seeded prologue
functions, fails if any decompilation fails, and finds exactly two explicit
reads, both in `FUN_bfc216a8`, with no direct store through that same context
expression. This narrows the producer outside the currently recovered direct
dataflow; it does not rule out an alias, computed pointer, setup performed by
a caller, or resident/fixed-ROM initialization.

The ownership boundary is also clearer now: `FUN_bfc21530` consumes the
control flag at `param + 0x81`, clears it, and initializes fade mode `2` plus
the step counter at `+0x8a`; `FUN_bfc21590` later changes mode `2` to `3` when
the recovered phase condition is reached. The mapped control routine
`FUN_680a1ed4` explicitly clears `+0x81` during its reset path. The first
external writer that sets `+0x81` is still unresolved, so this is not yet a
safe patch site. A full `LoadAP` + `DumpC` pass over all 30 recovered
functions finds exactly four `+0x81` references: one reset-path clear in
`FUN_680a1ed4`, and the read plus two clears in `FUN_bfc21530`. There is no
mapped assignment that arms the byte, which narrows the missing writer to an
external/indirect boundary rather than another hidden `mmm_bp` function.

This proves a firmware fade state machine and its buffer-facing boundary, but
not yet whether a particular Audacity-rendered tail is lost before this state
machine, during buffer exhaustion, or in the downstream DSP. Remaining targets
are the external callback behind the fade-step calls and the caller that
supplies `param + 0x81`/`+0xba`; then compare a short-tail file against the
`available-count` and `0x200`-byte refill conditions.

## Emulator boundary

`tools/sim/mmm_bp_model.py` now exposes the safe part of this contract:
`plan_ring_read()` rejects reads larger than the available count and returns
the same two-segment wrap plan as the recovered reader; `REFILL_BLOCK_BYTES`
records the observed `0x200` decoder refill; `read_ring_bytes()` applies that
plan to a concrete ring byte source without interpreting samples; and
`advance_fade_step()` models
the proven mode selection, 60-step phase, and ±1000 level arithmetic while
keeping the caller-provided base level opaque. No DSP bytes, callback body, or
guessed attenuation coefficients are executed.

Because all six corpora agree byte-for-byte at these locations, the community
firmware did not change this module. The next step is to trace callers and
the external callbacks that consume the state; only then can we distinguish a
real end-of-stream fade from a diagnostic message or a short-buffer underrun.

## End-state ordering relative to the refill loop

A targeted instruction dump of `FUN_bfc216a8` narrows the end-state sequence.
In its active state-1 path, it calls `FUN_bfc27520` at `0xbfc21794` before
reading the status word through the pointer at context `+0xd0`. The next
instructions compare that status with `3` (`0xbfc217a2`) and branch to
`0xbfc217b4` (`0xbfc217a4`) when equal. The same terminal path is taken when
the unsigned elapsed delta at context `+0xec - +0xf0` exceeds `100`. Either
condition writes state `2` at `0xbfc217ba` and returns at `0xbfc217c8`. The
`0x200`-byte refill helper `FUN_bfc276ac` is called at `0xbfc21712` from the
loop; that loop is reached only when status is not `3` and the elapsed guard
has not fired. Thus the callback pass precedes the terminal test, and a
terminal condition exits before the next refill-loop iteration. The recovered
code does not show a loop that waits for a downstream queue to become empty
before returning.

### Bounded status-3 p-code execution

`tools/ghidra/run_mmm_bp_status3_probe.sh` executes the real
`FUN_bfc216a8` MIPS16 instructions with a synthetic context at `0x9fc21b00`.
The fixture sets context state `+0x24=1`, counter `+0x8a=0`, status pointer
`+0xd0` to a synthetic word containing `3`, and a frame pointer at `+0x78`.
The initial resident call through `0xbfc21a3d` and the real in-module
`FUN_bfc27520` ring-processing entry are intercepted and return synthetic
zero. The probe observes the actual compare instruction at `0xbfc217a2` with
`s1=3`, the actual terminal return at `0xbfc217c8`, state `+0x24=2`, and the
two halfword copies to `+0xdc/+0xde`. The real refill entry `FUN_bfc276ac`
is not reached. The path also performs the observed write `8` to synthetic
MMIO address `0xc0160050`.

The probe passed on stock and community. Both `mmm_bp.al` inputs share SHA-256
`ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b`, so
these are repeated runs of the same code, not independent version evidence.
This establishes the terminal control-flow response to a supplied status
value. Because the status pointer, frame data, resident return, and output
ring helper are synthetic or stubbed, it does not show that real decoder status
is set to `3`, that downstream audio drains or discards queued samples, or that
this explains an audible fade-out cutoff.

Reproduce with:

```sh
bash tools/ghidra/run_mmm_bp_status3_probe.sh /path/to/analysis/community/fw/mmm_bp.al
bash tools/ghidra/run_mmm_bp_status3_probe.sh /path/to/analysis/stock/fw/mmm_bp.al
```

The callback slot used by `FUN_bfc27520` is no longer opaque. The literal pool
at `0x680a2020..0x680a2028` resolves to slot `0x9fc21b04` and two MIPS16
entries, `0xbfc277f1` (`FUN_bfc277f0`) and `0xbfc27939` (`FUN_bfc27938`).
`FUN_680a1ed4`'s operation-11 branch selects and writes one of those entries
into `0x9fc21b04` according to the byte at context `+0x0c`. The probe checks
both slot write references and the decompiled selector branches. `FUN_bfc27520`
calls the selected entry with the decoder context and a mode value; the
decompiled callback bodies dereference the descriptor at context `+0x70` and
read/update its ring positions. This supports an input-ring callback
interpretation, not a downstream ASRC/DAC drain callback. These are static
decompiler findings; the callbacks were not executed.

Therefore the ordering above proves only that this decoder-side input-ring
helper gets a pass before the state-3 exit. It does **not** prove that the
ASRC/DAC queue drains; no device queue level or audible tail was measured.
Nor does this function call the separate `music.ap` track-index advance
callback. The relation between this decoder-side terminal state and the
`music.ap` mode-4 advance path is still unproven. `mmm_bp.al` SHA-256 is
identical in stock V1.101.10 and
community V1.292.00 (`ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b`),
so this ordering is not a community-only change.

The Ghidra runner now pins the instruction addresses, callback slot, and
callback entries above in addition to the decompilation checks. Reproduce
against either extracted member with the two commands above. The code was
statically disassembled/decompiled; these checks do not execute the decoder
callbacks.

The separate search for a path from this status-3 terminal branch to the
`music.ap` mode-4 track-advance callback found no direct cross-module literal
or shared-state reference in the hash-pinned stock/community set. The bounded
result and remaining resident boundary are recorded in
[`findings-mmm-bp-music-bridge.md`](findings-mmm-bp-music-bridge.md).

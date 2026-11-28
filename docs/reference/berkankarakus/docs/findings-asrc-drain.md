# End-of-track ASRC/EOF branch

This is a static comparison of the stock M30 `V1.101.10` `music.ap` against
the archived `V1.101.11` and community V1.292.00 images. It does not measure
the DAC, ASRC queue, or audible output on a device.

## Confirmed binary delta

The three images use the same `music.ap` bank layout. In the bank beginning at
file offset `0x9c00` / runtime address `0x40494c00`, the six bytes at file
offset `0x9f5c` change as follows:

```text
stock:     58 67 01 5a 22 61
V1.101.11: 24 60 04 72 0a 60
community: 24 60 04 72 0a 60
```

The surrounding code belongs to `FUN_40494e94`; the changed area is reached
through the odd MIPS16 address `0x40494f5d`. Ghidra's containing-function
decompile shows the following control-flow difference:

- stock: mode `3` enters the existing `+11000U` end-of-track branch;
- V1.101.11/community: an additional `mode == 4` case falls through to the
  same advance path before that branch is evaluated.

The reproducible guard is:

```sh
bash tools/ghidra/run_asrc_probe.sh
```

It decompiles stock, V1.101.11, and community images, asserts the mode-4
control-flow difference, and checks the raw six-byte replacement.

## Interpretation and limits

This proves a community change to the end-of-list mode-4 control flow: under
the tested synthetic state, it reaches the advance/index-update callback where
stock takes the end-notification/UI path. It is **not yet evidence of an
ASRC/DAC drain correction**. The callback body and decoder meaning of mode 4
were not part of that probe, and the separate `mmm_bp.al` terminal-state path
has not been linked to this `music.ap` branch. The community change remains a
possible fade-out lead, but its relevance to the reported Audacity tail is
unproven. It does not prove that the six-byte change drains any DAC/ASRC sample,
nor that every codec uses the same mode. The host emulator records the narrow
control-flow boundary in
`tools/sim/asrc_model.py`: mode `3` takes the existing advance path in both
variants, while mode `4` does so only with the accepted drain fix. It remains
an audibility-neutral model, not an audible playback pass.

The other V1.101.11 `music.ap` changes at offsets around `0x1150c` and
`0x120e0` are not yet assigned a playback meaning. They remain outside this
probe until their callers and device-facing effects are identified.

## Dinamik probe sınırı

`bash tools/ghidra/run_asrc_mode4_dynamic_probe.sh` executes the real
`FUN_40494e94` instructions from hash-pinned stock and community APs in Ghidra
p-code. The follow-up `bash tools/ghidra/run_asrc_music_tick_probe.sh` starts
at the actual music tick wrapper `FUN_400d4edc`. In that same run it decodes
`0x400d4fda` as `lw v0,0xc4(pc)` and `0x400d4fdc` as `jalrc v0`. The executed
load reads `0x40494e95` from callback literal `0x400d509c`; the following
indirect call enters `FUN_40494e94` once and returns to `0x400d4fdf`. The
probe checks both the actual call operand value and the emulated literal before
and after execution. No AP code or callback literal is rewritten. The
resident service at `0xbfc164dc` is stubbed at the two calls reached in this
tick-to-EOF path: the probe writes `100` to each first-argument output buffer
and returns `0` in `v0`. Resident event/UI/end-notification boundaries are
captured stubs.
Playback state, index, threshold, and the remote UI event byte are synthetic
RAM inputs. The tested direct callees have MIPS16 context, every callback
pointer is MIPS16-tagged, and every intercepted return address is checked for
its odd MIPS16 tag. Only those tested paths are covered by this mode-preserving
assumption. The runners create disposable Ghidra projects under a temporary
directory; synthetic RAM blocks are discarded when each run exits.

At the last item (index 2 of 3) above the threshold, stock mode 2 wraps to
index 0 and calls the advance/index-update callback with argument 0. Stock
mode 4 instead leaves the index at 3 and takes the end-notification/UI path.

### Resident entry `0xbfc164dc` call-site boundary

`bash tools/ghidra/run_resident_service_xref_probe.sh <community-music.ap>
<community-usound.ap> <community-udisk.ap> <stock-music.ap>
<stock-usound.ap> <stock-udisk.ap>` checks computed-call references and
aligned MIPS16 pointer literals in six pinned APs. Each stock/community
`music.ap` contains exactly five direct literal references to `0xbfc164dd`,
with calls from
`FUN_400d4edc`, `FUN_401d51b8`, `FUN_40394f48`, `FUN_40494e94`, and
`FUN_405151a0`. Both `usound.ap` variants have one call at
`FUN_40094ccc` and one literal at `0x40094d24`; both `udisk.ap` variants have
one call at `FUN_40014ccc` and one literal at `0x40014da0`.

Four callers pass a local result buffer as their first argument and consume
its first word as an elapsed/progress-like value. The separate
`FUN_401d51b8` call passes `(param_1 + 4, *param_1)`, so the resident entry
cannot be described from these call sites as a dedicated one-purpose
snapshot getter. The `usound.ap` caller passes an eight-byte local buffer to
the entry, checks for a successful byte result, and forwards that buffer to
another handler. The `udisk.ap` caller uses the same buffer-and-status shape;
it decodes the returned record and sets flags for event `0x2d01`. This is
consistent with a shared event/result service, but does not establish its
resident implementation or full ABI. In the EOF probe, only the two calls
actually reached through `FUN_400d4edc` and `FUN_40494e94` are stubbed; the
probe writes a synthetic word to each first-argument buffer and returns zero.
That establishes caller control/data flow, not the resident service's real
output or side effects.
Community mode 4 wraps to index 0 and calls that same callback with argument
0, matching mode 2. The integrated tick probe confirms the literal-mediated
`lw` → `jalrc` edge enters the real handler in both variants. Stock then emits
the end notification, while community invokes the index/UI callback with
argument 0. Below the
threshold (progress 9 against threshold 10),
community mode 4 makes no advance callback; at equality (10) and above (11) it
takes the advance path. These probes confirm the mode-4 end-of-list
control-flow change and literal-mediated caller edge in actual AP
instructions. They do not
execute the advance/index-update callback body, establish what mode 4 means at
the decoder, or prove that the ASRC/DAC drains the fade-out samples.

## Callback-to-engine message boundary

The mode-4 index callback was followed through the community `music.ap` and
`mengine.ap` dispatchers. `FUN_405151a0` updates the selected-track state and
ends through `FUN_40394eb4(1)`. That helper computes a state flag from
`0x9fc15418` and calls `FUN_40614f30`; its literal-mediated resident call
uses message ID `0x304` (`FUN_40614f30` at `0x40614f30`, resident target
pointer `0xbfc16409`). In `mengine.ap`, `FUN_60090d64` dispatches `0x304`
through pool slot `0x60091024` to `FUN_60110ddc` (`0x60110ddd` MIPS16-tagged
pointer).

The `0x304` handler reads a byte through message `param+4`. The raw word at
`mengine.ap` address `0x60110e94` is the pointer literal `0x9fc15c88`, not the
state object's address. Therefore the effective RAM byte addresses are
`0x9fc15cb3` (`+0x2b`, assigned `input & 0x30`), `0x9fc15cb4` (`+0x2c`,
assigned `input & 0x0f`), and `0x9fc15cb6` (`+0x2e`, assigned `input &
0xc0`). The old value of `0x9fc15cb4` is read before the stores; if the
incoming low nibble is 3 and the old value was not 3, the handler calls an
additional transition helper. It then acknowledges via another indirect
helper. The same handler decompiles identically at the same address in stock
V1.101.10 `mengine.ap` (SHA-256
`1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d`); its
pointer pool also contains `0x9fc15c88` at `0x60110e94` and the same resident
call targets. The field meanings and downstream readers remain unknown. The
decompiled handler does not call the `0x311` PLAY dispatcher. This is static
Ghidra evidence; message payload and resident service effects were not
executed in this trace.

Reproduction: independently map the hash-pinned community `music.ap` and
`mengine.ap` with `tools/ghidra/LoadAP.java`. Decompile `0x405151a0`,
`0x40394eb4`, and `0x40614f30` from `music.ap`, and `0x60090d64` and
`0x60110ddc` from `mengine.ap` using `DumpAt.java`. Inspect `mengine.ap` words
at `0x60091010..0x6009106c` and `0x60110e94..0x60110ea8` with `DumpWords.java`
to verify the message dispatch and the RAM base pointer. Repeat the handler
decompile and `0x60110e94..0x60110ea8` word dump against the stock
`mengine.ap` hash recorded above to reproduce the cross-version comparison.

### Independent `0x304` producer in `setting.ap`

`setting.ap` contains a second producer. At `FUN_40154c88`, the incoming byte
is stored at local `s1+0x18`; the function constructs a message whose first
word is `0x304` and whose next word points to that byte, then calls its
literal-mediated service with destination `0x0d`. The manager app-name table
maps entry 13 to `mengine` (the ID-equals-index interpretation is the
documented assumption). `FUN_40155200` ORs `0x10` into the current byte before
calling this wrapper; `FUN_40154cd4` replaces the low nibble while preserving
the high nibble. This independently confirms that `0x304` carries a packed
one-byte state update into the engine. It still does not identify the
user-facing setting or the three fields' semantic names.

The key instruction evidence is `0x40154c8c..0x40154c9e` (copy input byte,
form the payload pointer, store message ID) and `0x40154caa..0x40154cb4`
(destination `0x0d`, payload pointer, indirect service call). Reproduce with
`LoadAP.java` and `DumpInstructions.java` on the hash-pinned community
`setting.ap`, starting at `0x40154c88`; decompile `0x40154c88`,
`0x40154cd4`, and `0x40155200` with `DumpAt.java`.

### Dynamic `0x304` producer payload

The community `music.ap` selection callback `FUN_40394f48` calls
`FUN_40394e7c(3)`. That helper writes `3` to `0x9fc15719`, combines it with
the upper-bit byte at `0x9fc1571a`, then calls `FUN_40614f30`. The latter
applies an additional `0x40` bit when `0x9fc15418 & 0x4000` is set and sends
message `0x304` through resident pointer `0xbfc16409`.

`bash tools/ghidra/run_mengine_state_producer_probe.sh` executes the real
callback/helper/message-wrapper instructions in p-code and intercepts the
resident message and value-service boundaries. With upper bits `0xa0` and the
flag clear, it captures payload byte `0xa3`. With upper bits `0x10` and the
flag set, it captures `0x53`, confirming that bit `0x40` is added while the
selection's low nibble remains `3`. The flagged callback also reaches the
value-service stub. These stubs return synthetic results: the probe proves
the UI-side payload construction and message request, not delivery to the
engine or the meaning of the visible option.

This dynamic path is separate from the mode-4 track-index callback
`FUN_405151a0`, which does not produce `0x304`. The setting UI has an
independent static producer at `FUN_40155148`; its visible option label is
also unresolved.

### Dynamic `mengine` handling of `0x304`

`bash tools/ghidra/run_mengine_304_handler_probe.sh` executes the real
`mengine.ap` handler `FUN_60110ddc` in p-code. The synthetic message's `param+4`
points directly to the payload byte, and the engine handle at `0x9fc15c0c` is
a controlled test input. The status-0/1 in-binary helper path executes both
real helper functions; only their resident function-pointer calls are stubbed.
In the status-2 path, the `mmm_mp` and acknowledgement resident calls are
stubbed.

With handler status `2`, payload `0xa3`, and prior `+0x2c == 2`, the handler
writes `0x20`, `3`, and `0x80` to state bytes `+0x2b`, `+0x2c`, and `+0x2e`,
requests command `0x1c`, and copies the stubbed snapshot word at output
`+0x0c` into `0x9fc15d8c`. With status `2`, payload `0x53`, and prior
`+0x2c == 3`, it writes `0x10`, `3`, and `0x40` but does not request another
snapshot; the previous `0x9fc15d8c` value is retained.

With status `0` and payload `0xa3`, and again with status `1` and payload
`0x53`, the handler calls the function pointer stored at `0x60110e98`
(`0x7a052dd9`, MIPS16 tagged). This resolves to the in-binary function
`FUN_7a052dd8`, which the probe now executes. For both inputs it writes the
low nibble to `0x9fc15f39`, clears `0x9fc15f21`, sets `0x9fc15f23`, and calls
`FUN_7b1a808c` using synthetic selector value `0x35` from `0x9fc15f26`.
That nested function also executes and requests the resident function pointer
`0xbfc11cc1` twice, with argument tuples
`(0x9fc15f88, 0, 8, 1)` and `(0x9fc15f90, 0, 0x20, 1)`. The resident calls
return synthetic zero; the probe verifies their targets and arguments, then
checks the in-binary helper's return value and state writes.

This verifies status `0`, `1`, and `2` control paths, both in-binary helpers,
the payload masks, the nested resident-call requests, and the `0x1c` transition
guard. It does not execute the resident `0xbfc11cc1` implementation, actual
`mmm_mp` service, or resident acknowledgement implementation. The synthetic
selector and RAM are controlled probe inputs; this is not a full engine run or
proof of the helper's device-time effects.

### Connection to the stock shuffle picker

The status-0/1 path reaches code that this repository independently identifies
as the stock shuffle subsystem: `tools/patch_mengine.py` names
`FUN_7b1a808c` (`0x7b1a808c`) `init_shuffle` and `0x7b1a84b0` the stock picker
entry. In the recovered code, `FUN_7b1a808c` builds a bitmap from its count
argument and `FUN_7b1a8300` tests and clears a selected bit. The higher-level
selection routines `FUN_7b1a8568`/`FUN_7b1a8658` use history first and reach the
bitmap picker on fallback paths. The README identifies the old stock shuffle
as biased and documents the replacement picker; this confirms the code
region's shuffle role without treating the patched picker as stock behavior.

The same engine RAM layout defines the current-folder track count at
`0x9fc15f26` (`COUNT` in `tools/playall/playall.h`). In the dynamic 0x304
probe, this count was deliberately seeded to `0x35` (53) and the status-0/1
path passed it to `init_shuffle`; payloads `0xa3` and `0x53` reached this path
with selector values `0x23` and `0x13`. This proves that the tested synthetic
0x304 cases invoke the shuffle initializer with the seeded count. The resident
clear calls made by that initializer are stubbed, so full device-time shuffle
state reset is not established. The visible setting label and exact
user-facing meaning of the producer's selection value remain unresolved; the
UI option itself is not identified as “Shuffle.”

### Confirmed engine-side consumers of the `0x304` state

The state base pointer `0x9fc15c88` has multiple literal-pool references in
`mengine.ap`. Decompiled consumers establish downstream effects without
assigning speculative names to the bytes:

- `FUN_60050d00` initializes `+0x2c` to `2` and `+0x2b` to `0` when its
  persisted record is absent or invalid. This is a default/reset value, not
  evidence that the byte means a particular playback mode.
- `FUN_60190eec` is the `0x311 PLAY` handler (`_play`). After issuing the
  `mmm_mp` PLAY command (`0x19`), it checks state `+0x2c`; if the value is
  exactly `3`, it issues `mmm_mp` command `0x1c` and then copies a word from
  the supplied data block into another engine state location. The
  `+0x2c == 3` check is outside the success/failure branch for command
  `0x19`, so command `0x1c` is not gated on that PLAY command succeeding. Its
  own return value is also not checked before the word is copied. This is a
  concrete playback-path effect of the `0x304` state. The purpose of command
  `0x1c` and the meaning of the copied word remain unresolved.
- `FUN_601910b0` passes `state[+0x2b] | state[+0x2c]` to an indirect helper
  after its playback-state handling. In `FUN_600d1054`, nonzero state
  `+0x2b` is one of two conditions for an indirect callback; that callback
  receives state byte `+0x31`, not `+0x2b` or `+0x2c`. The helper and callback
  targets are still unnamed.
- `FUN_600d0e1c` returns the OR of `+0x2b` and `+0x2c` through its message
  output pointer. `FUN_600d0ddc` is a neighboring message handler that passes
  an engine data pointer to a helper and completes the message; its relation
  to the state is only the shared pointer-pool neighborhood, not a proven
  semantic link.
- In the complete community `mengine.c` decompile, `+0x2e` appears only in
  the `0x304` handler's store (`input & 0xc0`); no downstream read was found.
  Its effect therefore remains unestablished.

The strongest supported conclusion is that `0x304` updates engine state used
by playback setup and related engine callbacks. It is not merely a UI marker
and its setter has no direct `0x311` dispatch. Whether the `+0x2c == 3` branch
corresponds to a user-facing option, an internal playback variant, or a
transition condition still needs the producer/caller values and the target of
command `0x1c` to be identified. These consumer links are static: the state
pointer is resolved from literal-pool entries. The PLAY handler's relation to
message `0x311` comes from the decompiled dispatch table; the other listed
consumers are linked by their shared state-pointer references and direct
calls where shown. This section does not claim a dynamic execution trace.

The host model in `tools/sim/mengine_state.py` represents only the observed
payload masks at offsets `+0x2b`, `+0x2c`, and `+0x2e`, plus the status-2
condition that requests command `0x1c` when the low nibble changes to `3`.
It leaves the field names and resident-service effects unresolved. The model's
tests cover the two captured payloads and the transition guard; they do not
simulate shuffle effects, playback, or DSP output.

Reproduce the consumers by decompiling `0x60050d00`, `0x600d0e1c`,
`0x600d1054`, `0x60190eec`, and `0x601910b0` from community `mengine.ap`.
`FUN_60190eec` is linked to the PLAY path by the existing `0x311` dispatcher
finding. The persisted-record initialization and consumers are also present
at the same addresses in the archived stock `V1.101.10` decompile; this
finding establishes no community-only change.

### Dynamic p-code confirmation of `mmm_mp` command `0x1c`

The `mmm_mp.al` overlay's `FUN_bfc13864` is the command handler reached with
command byte `0x1c`. Its recovered branch fills a six-word snapshot at
`param_1 + 0xd0`: the words at offsets `+0x08`, `+0x0c`, `+0x10`, and
`+0x14` are refreshed from the context offsets `+0x140`, `+0x104`, `+0x108`,
and `+0x10c`, respectively; the first two words are retained from the
existing `+0xd0/+0xd4` fields. It requests a `0x18`-byte transfer to the
caller-provided output buffer. For a command value at least 2, the handler
also calls the external service with command arguments `0x19` before this
branch and `0x1a` afterward.

`bash tools/ghidra/run_mmm_command_snapshot_probe.sh` executes the real
`FUN_bfc13864` instructions in Ghidra's p-code emulator with synthetic context
RAM. The run observed the service-call order `(handle,0,0,0x19)`,
`(output,context+0xd0,0x18,0)`, `(handle,0,0,0x1a)` and verified all six output
words against unique inputs. The transfer service at pointer literal
`0xbfc13934` is intercepted; the probe models its `(dst,src,size,0)` operation
as a byte copy, and returns `0` from each intercepted service. Thus the real
command handler's requests and data layout are executed and checked along the
stubbed-success path; the external OS service implementation is not. This is
a p-code control/data-flow result, not proof that playback occurs or a full
player/DSP run. The runner pins the input `.al` SHA-256 to
`57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199` (the
archived stock and community copies currently match).

This resolves the structural source of the engine caller's `+0x0c` word:
both the `0x304` transition path and the PLAY setup path store the fourth
snapshot word into engine RAM `0x9fc15d8c`. That fourth word is sourced from
the library context's `+0x104`, not `+0x10c`. `FUN_680d2d00` updates `+0x104`
in 1000-unit steps while advancing playback state, so it is time/position-like;
the exact unit and semantic label are not yet proven. The library context
`+0x10c` is instead snapshot word six (`output +0x14`); it has no direct
reader among the recovered `mmm_mp` functions, and its meaning remains
unresolved. Whether the surrounding `0x19/0x1a` service calls pause, resume,
or otherwise bracket the snapshot also remains unresolved.

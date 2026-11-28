# QEMU boundary for the now-playing marquee

This note records the first decompilation result used to define the hybrid
emulator boundary. It was generated from the extracted `music.ap` bank table
with Ghidra 12.2 and `tools/ghidra/LoadAP.java`/`DumpC.java`. It is static
firmware analysis, not a complete ATJ2127 boot.

## Verified entry point

```c
short FUN_40d54f2c(unsigned short event_or_style, int *scroll_state);
```

The function owns the shared title/artist/album marquee pass. It first clears
the content region through the UI dispatcher, updates the scroll state, emits
style `0x2005` for the active text, and emits style `0x2004` for the measured
text cleanup path. The function returns a `short` status and calls the timer
through an indirect pointer while waiting for the next scroll step.

The audited instruction at `0x40d55078` is the immediate that selects the
active style:

```text
04 f0 05 6a    li v0, 0x2005
```

The isolated patched candidate changes that immediate to `0x2001`; the host
trace accepts both forms and records which one is present.

## Literal-pool targets

These values are read directly from the real `music.ap` bank containing
`0x40d54c00`:

| Literal address | Host-shim role | Target |
|---|---|---:|
| `0x40d5516c` | resident UI/message-submit helper | `0xbfc16405` |
| `0x40d55170` | UI/status read helper | `0x48454841` |
| `0x40d55174` | UI/status state | `0x9fc1592c` |
| `0x40d55178` | backlight/status setter | `0x484547ad` |
| `0x40d5517c` | UI context restore | `0xbfc16401` |
| `0x40d55180` | timer | `0xbfc16bb1` |
| `0x40d55198` | scroll state byte storage | `0x9fc1a294` |
| `0x40d551b0` | scroll state byte storage | `0x9fc1a294` |

The event loop immediately below the renderer also resolves these targets:

| Literal address | Host-shim role | Target |
|---|---|---:|
| `0x40d54f10` | key read begin | `0xbfc16ae9` |
| `0x40d54f14` | key event read/decode | `0x485546bc` |
| `0x40d54f18` | key event decoder | `0x485547ed` |
| `0x40d54f1c` | key fallback | `0x48bd4601` |
| `0x40d54f20` | local scroll event handler | `0x40d54df8` |
| `0x40d54f24` | key poll | `0xbfc16b55` |
| `0x40d54f28` | event callback state | `0x9fc1a28c` |

The next QEMU user-mode harness should map these targets explicitly. If any
pointer changes in a different AP build, the harness must stop with a manifest
error rather than silently running against the wrong dispatcher ABI.

## Repository implementation

`tools/emu/m30_ui.py` now verifies the call-site and these targets before it
renders. `tools/emu/prepare_qemu_banks.py` preserves all 87 AP banks at their
recorded addresses for the future MIPS user-mode ELF. The remaining work is
to implement the `0xbfc16405` dispatcher shim and supply a synthetic scroll
descriptor with the fields that `FUN_40d54f2c` reads.

`tools/emu/scroll_descriptor.py` now records that descriptor boundary. The
proven fields are state `+0x06` (slot count), `+0x07` (active slot), `+0x08`
(previous slot), and state `+0x0c` (pointer to 0x18-byte entries). The context
contains the state pointer at word 0 and the event-loop status byte at word 1.
The recovered event switch maps `0x2901`/`0x2902` to next/previous, `0x2903`
to redraw, `0x2905` to exit, and `0x2906` to cancel.

`tools/emu/ui_dispatcher.py` is the corresponding host boundary for
`0xbfc16405`. It implements only the observed clear/rectangle/text call-site
envelope, records every call, and leaves unknown operations trace-only. This
keeps the framebuffer useful for UI work without inventing undocumented
resource or LCD semantics.

`tools/emu/make_qemu_probe.py` prepares an isolated smoke probe. It does not
modify the AP or generate a flashable HEX: it copies the banks to a temporary
directory, checks the original literal values, redirects 13 audited
function/data pointers to low-address test stubs/RAM, and links the real
`FUN_40d54f2c` entry with a cancel event.

The same real-bank path now executes in Ghidra's MIPS p-code emulator through
`tools/ghidra/RunScrollProbe.java`. With the synthetic RAM/context and audited
stubs, the cancel-event probe reaches the caller's return address in 359 steps
and records dispatcher/state-storage calls. That cancel path exits before the
timer call. This is instruction-level execution of the real MIPS16 bank, but
not a full ATJ2127 boot: the LCD, SD, decoder and SoC peripherals remain
stubbed.

The renderer's wait branch is separately reachable with the `render-next`
mode. The probe stops at the first call through literal `0x40d55180` and
asserts the real caller's ABI is `(a0=1,a1=0,a2=0,a3=0x31)`. The timer stub
returns synthetic zero in this harness; the probe stops before that return can
drive another loop iteration. This confirms the AP reaches a module-`0x0f`
operation-`0x31` boundary while handling a next event. It does not reveal the
resident operation's implementation or prove what timing unit it uses.

The `actions_flash` [ATJ2127 syscall notes](https://github.com/kilicdev/actions_flash/blob/main/RE_NOTES.md#L250-L259)
map import 15 operation 49 (`a3=0x31`) to `sleep_ms(a0)` for US212D V1.101.25.
M30 code provides a useful independent call-pattern match: playlist error and
retry paths call the same import/selector with `a0=0x23` (35), while this
scroll wait path passes `a0=1`. This makes “sleep for the `a0` duration” a
strong cross-firmware hypothesis for M30 operation `0x31`; the M30 resident
implementation and time unit have not been directly observed.

The repeatable entry point is:

```sh
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap
```

The event handler can also be isolated for one transition:

```sh
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap next
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap previous
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap redraw
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap render-next
```

Those modes call `FUN_40d54d80` directly and assert the recovered state
changes: `next` moves active slot `0 -> 1`, while `previous` wraps `0 -> 2`;
both preserve the previous slot and set context status `2`. `redraw` advances
the active entry's measured position from `40` to `50` and sets status `1`.

This gives us a readable, typed boundary for the part of the decompiler output
that matters to the UI work without pretending that the complete generated C
is a maintainable source tree. The raw dump remains available for address
cross-reference; the event/state contract belongs in
`tools/emu/scroll_descriptor.py` and this document.

The corresponding readable reference model is
[`music-scroll-reference.c`](music-scroll-reference.c). It intentionally keeps
unresolved fields and callbacks named as such; it is an analysis aid, not a
claim that the original vendor source has been recovered.

## First real UI/message trace

The full renderer probe records the following calls before returning. The
pointer-valued arguments are stack-local payloads, so their addresses are
expected to change between runs. The resident target was also marked at
`0xbfc16404` (the odd MIPS16 pointer is `0xbfc16405`): it packages the first
three arguments and submits message `0x0d` through the resident system-call
stub. Therefore `a3` below is a stable call-site discriminator, not yet a
proven opcode consumed by the resident wrapper.

| `a0` / payload | `a1` | `a2` | `a3` call-site value | observed role |
|---|---:|---:|---:|---|
| `0x18c3` | `0` | `0` | `0x14` | set background color, then clear next region |
| `0` | `0` | `0` | `0x12` | clear content region |
| `0x39e7` | `0` | `0` | `0x14` | set second background color |
| `0` | `0` | `0` | `0x12` | redraw content region |
| stack payload | `0` | `0` | `0x02` | draw control/icon payload |
| stack payload | `0` | `0` | `0x02` | second control/icon payload |
| text payload | text metadata | `1` | `0x03` | draw active text |
| dynamic payload | dynamic metadata | `6` | `0x06` | measured/dynamic text path |
| text payload | text metadata | `1` | `0x03` | draw cleanup/secondary text |

The public PD196 SDK independently places `UI_CLEARSCREEN` at `0x12` and
`UI_SET_BACKGD_COLOR` at `0x14`; its macros pass a `region_t *` and a
`COLORREF` through the same four-argument `ui_op_entry` envelope. This is a
strong structural cross-check for treating `0x18c3` and `0x39e7` as color
words and the following stack payload as a region. It is not proof of the
proprietary M30 resident implementation, so the host keeps the M30 operation
mapping explicitly evidence-backed and does not claim full LCD pixel ABI.

The next boundary is consequently the message-`0x0d` consumer/resident UI
service, not the low-level LCD driver. The low-level LCD resident code was
also extracted separately; its rectangle primitive at `0xbfc0a578` emits LCD
window commands `0x2a`/`0x2b`/`0x2c`, but no direct call edge from `drv_ui.drv`
has been proven yet.

The payload bytes observed in the same run include control IDs `0x1012` and
`0x1013`, active style `0x2005`, cleanup style `0x2004`, and cleanup
color/length fields `0xffff`/`0x007b`. These are observations only; the
resource and text-pointer ownership still needs a second probe before assigning
more struct names.

It creates a temporary Ghidra project and removes it on exit. Passing a second
argument supplies an already-built raw `stubs.bin` and skips the probe-object
assembly step.

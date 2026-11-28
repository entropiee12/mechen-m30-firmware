# Powered card reinsertion: bounded driver-side result

This follow-up checks whether the recovered SD-controller probes or the
`card.drv` exported function vector contain the missing card-insertion event
callback. It uses only hash-pinned firmware binaries and Ghidra static
analysis. The device and SD card were not accessed.

## Reproduce

```sh
bash tools/ghidra/run_card_trigger_boundary_probe.sh \
  /path/to/community/fw/card.drv /path/to/community/fw/cards.drv
```

The same `card.drv` and `cards.drv` SHA-256 values were confirmed in the
community and stock extractions:

```text
card.drv  fc355c17258fe6fca184e5a616ec4c0b151a566caa99feba9b14981a046063bf
cards.drv 3c7fb89cf2e8e2f8bdb2311b386e7047f17c35b5846e71139a105255b88462c1
```

## `card.drv` exported vector

The final YQHX segment maps file offset `0x1700`, length `0x44`, to
`0x9fc19500`. Its first six words are odd MIPS16 pointers:

| Slot | Pointer | Resolved function | Directly visible behavior |
|---:|---:|---|---|
| 0 | `0xbfc17b1d` | `FUN_bfc17b1c` | Forwards to `FUN_bfc179ec` with mode 0; that path issues the controller's read operation. |
| 1 | `0xbfc17b35` | `FUN_bfc17b34` | Forwards with mode 1; that path issues the controller's write operation. |
| 2 | `0xbfc17b4d` | `FUN_bfc17b4c` | Handles a state-dependent flush/status path and brackets controller calls with operation values `0x33` and `0x34`. |
| 3 | `0xbfc17be9` | `FUN_bfc17be8` | Returns the 32-bit value at the driver state block's `+4`. |
| 4 | `0xbfc17bf5` | `FUN_bfc17bf4` | Returns constant 1. |
| 5 | `0xbfc17bf9` | `FUN_bfc17bf8` | Returns constant 0. |

The first three slots share the block-I/O/control implementation; the final
three expose a state value or constant results. This vector does not contain
an obvious card-insert notification callback. The vector's consumer and exact
slot ABI are outside the available code, so this is a bounded description of
the exported functions, not proof that the kernel has no other registration
path.

## `cards.drv` detect helpers

The YQHX entry field at file offset `0x40` is `0xbfc28d60`. Ghidra finds no
code reference to that entry inside the mapped `cards.drv` segment. It finds
exactly one in-segment call reference to each probe helper:

```text
FUN_bfc28d60 --call at 0xbfc28e08--> FUN_bfc29200
FUN_bfc28d60 --call at 0xbfc28ea0--> FUN_bfc290d8
```

`FUN_bfc28d60` configures the controller, queries geometry/capacity through
`FUN_bfc29200`, and conditionally reaches the scan/tuning routine
`FUN_bfc290d8`. The two helpers have no other code callers in this mapped
driver image. Neither address appears in the six-word `card.drv` vector.
This places these routines on the controller driver's entry/setup path; it
does not show a hotplug event loop or a registered event callback.

## Result and boundary

The examined `card.drv` export entries and `cards.drv` detect-helper call path
do **not** expose the powered-reinsert callback or an edge to the playlist
index starter. This probe does not assert absence of every possible index-flag
writer in the full driver images, and direct code references do not rule out
indirect/runtime registration. The callback dispatcher may live in fixed ROM,
kernel code, or another service that is not represented as a direct reference
in these driver binaries. The physical reinsert observation therefore remains
unexplained.

The next useful investigation is to resolve the outer driver/service dispatch
that consumes the `card.drv` vector and handles card-state changes, then trace
its receiver toward the already-known index starter. Re-decompiling the same
two controller probes is unlikely to narrow that missing edge further.

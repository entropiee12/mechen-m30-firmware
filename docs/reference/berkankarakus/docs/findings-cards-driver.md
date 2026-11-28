# M30 SD-card controller driver: static boundary

`cards.drv` is now covered by a reproducible container check and a Ghidra
MIPS32 decompilation. It is the low-level SD-card controller/transport
member, not the FAT/exFAT filesystem and not the library indexer.

## Reproducible checks

```sh
PYTHONPATH=tools/emu python3 tools/emu/cards_binary_probe.py \
  analysis/community/fw/cards.drv
bash tools/ghidra/run_cards_probe.sh analysis/community/fw/cards.drv
```

The member is byte-identical in the local community, stock, first-pass,
V1.101.11, V1.101.12, and V5 extractions:

```text
3c7fb89cf2e8e2f8bdb2311b386e7047f17c35b5846e71139a105255b88462c1
```

## Container and observed roles

| Field | Value |
|---|---:|
| file offset | `0x200` |
| length | `0x1400` |
| destination | `0xbfc28000` |
| BSS | `0x9fc19544`, `0` bytes |
| entry field | `0xbfc28d60` |
| Ghidra mapping | file byte zero at `0xbfc27e00`, 5,120 bytes, 23 functions |

The recovered functions show:

- controller command/data submission and polling (`FUN_bfc280c0`,
  `FUN_bfc2816c`, `FUN_bfc28430`);
- a 512-byte block transfer path with read/write direction selection
  (`FUN_bfc28564`);
- controller reset/initialization (`FUN_bfc28d20`, `FUN_bfc28f50`);
- card-detect/parameter probing (`FUN_bfc290d8`, `FUN_bfc29200`), including
  the card reset marker `0x55aa` and a capacity/geometry read path.

The embedded diagnostics `rwclk`, `rwerr`, `reset sd:`, and `ack:` reinforce
the SD transport interpretation. The member contains no directory walker,
filename comparison, or library-index sorting logic.

## Consequence

This closes the low-level SD-controller gap needed before a more realistic
QEMU storage model can be attempted. The host emulator now exposes the
controller's evidence-backed 512-byte transfer boundary through
`tools/sim/sd_block_device.py`, including explicit card insertion/removal and
sector bounds. The same model exposes capacity as `sector_count * 512` bytes,
without assigning meaning to the controller's raw geometry registers. It is a
storage boundary, not an emulation of the controller's
register polling, IRQ timing, or automatic-index trigger. It does not explain
the powered card-reinsertion behavior. The event callback, flash-flag setter,
or direct builder-start path has not been located; see
`findings-index-guards.md` and `library-builder.md` for the known startup path.
No flashable patch is proposed from this analysis.

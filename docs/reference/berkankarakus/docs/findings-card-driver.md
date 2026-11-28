# M30 SD-card adapter driver: partial static boundary

`card.drv` has three non-empty YQHX segments in descriptor slots 0, 1, and 3;
slot 2 is empty. Automatic Ghidra prologue discovery does not recover its
functions, so this file is not marked as fully decompiled. The entry call tree
can nevertheless be seeded and checked reproducibly.

## Reproducible checks

```sh
PYTHONPATH=tools/emu python3 tools/emu/card_binary_probe.py \
  analysis/community/fw/card.drv
bash tools/ghidra/run_card_probe.sh analysis/community/fw/card.drv
```

The member is byte-identical in the local community, stock, first-pass,
V1.101.11, V1.101.12, and V5 extractions:

```text
fc355c17258fe6fca184e5a616ec4c0b151a566caa99feba9b14981a046063bf
```

## Container and seeded call tree

| Field | Value |
|---|---:|
| descriptor slot 0 | file `0x200`, `0x9d0` bytes → `0xbfc17400` |
| descriptor slot 1 | file `0xc00`, `0xa10` bytes → `0xbfc28000` |
| descriptor slot 2 | empty |
| descriptor slot 3 | file `0x1700`, `0x44` bytes → `0x9fc19500` |
| BSS | `0x9fc19544`, `0` bytes |
| entry field | `0xbfc28494` |
| Ghidra mode | MIPS16, 8 entry/call-tree functions seeded |

The seeded decompilation shows:

- controller command setup and polling (`FUN_bfc28000`,
  `FUN_bfc28074`);
- card reset/initialization with marker `0x55aa` (`FUN_bfc28454`);
- adapter entry sequencing (`FUN_bfc28494`);
- controller register setup and card-detection scan
  (`FUN_bfc2869c`, `FUN_bfc28824`);
- a card-parameter/capacity read (`FUN_bfc28918`), with a 512-byte shift in
  the decoded geometry path.

The embedded `rwclk` and `rwerr` strings match the SD transport role. The
member contains no filename, directory, or library sorting logic.

## Remaining uncertainty

The first segment and several helper targets are still not automatically
recovered as a complete function set. The next useful step is to identify
the exact ISA/prologue convention or cross-reference the loader's installed
entry table, not to patch this driver. No flashable change is proposed.

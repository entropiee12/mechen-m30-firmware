# M30 `nor_ud.drv`: external dispatch table

`nor_ud.drv` is not an 0x80-byte executable routine. Its only payload is a
small table of pointers into resident/external code, so a normal Ghidra
function scan correctly finds zero local functions.

## Reproducible check

```sh
PYTHONPATH=tools/emu python3 tools/emu/norud_binary_probe.py \
  analysis/community/fw/nor_ud.drv
```

The member is byte-identical in the local community, stock, first-pass,
V1.101.11, V1.101.12, and V5 extractions:

```text
d3627167f360bb99593ce7a0f589b461b12effa670dc863e5b2388ff0fe88caf
```

## Container and table

| Field | Value |
|---|---:|
| file offset | `0x200` |
| length | `0x80` |
| destination | `0xbfc08f80` |
| BSS | `0xbfc18d00`, `0` bytes |
| entry field | `0x100091a8` |
| local executable functions | `0` |

The payload starts with two zero words and then ten non-zero pointers,
including `0x10009001`, `0x1000900d`, `0x10009011`, `0x10009015`,
`0x10009179`, `0x200497c5`, `0x20049801`, and `0x100091a5`. The entry field
also points outside this payload. This is a registration/dispatch table for
resident NOR user-data code, not the code itself.

## Consequence

`nor_ud.drv` is now structurally classified. Recovering the pointed-to
functions belongs to the resident kernel/driver-bank analysis; this member
does not contain an independent filesystem or indexer implementation and
does not need a firmware patch.

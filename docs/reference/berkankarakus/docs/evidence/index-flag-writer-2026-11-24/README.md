# Boot-time automatic-index flag writer

## Inputs and reproduction

- `analysis/community/fw/config.ap`, SHA-256
  `5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580`.
- Ghidra 12.2 `LoadAP.java` plus `FindImmediateAndDecompile.java` on
  `FUN_40054ccc` and `FUN_40054c00`:
  [`config-index-writer-decompile.log`](config-index-writer-decompile.log),
  SHA-256 `b259b8f7bfeb96f5e4f1e114c0950b98b1e8c9ba70a0c9cf874c0bb042fa022b`.
- Raw pool words at file `0x16c0..0x16d0` decode to RAM base
  `0x9fc155d0`, record id `0x2f0000`, and fallback value `0x10000`.
- The config overlay's mainmenu record at file `0x16c0` is distinct from the
  mainmenu overlay's persistent menu record: the config helper uses
  `(0x9fc155d0, 0x2f0000, 4, op)`; mainmenu uses
  `(0x9fc155d0, 0x10000, 0x22, op)`.

## Verified boot path

`FUN_40054ccc` reads the flag word `(RAM=0x9fc155d0, id=0x2f0000,
length=4, op=0x12)`. It also reads the 0x14-byte settings record
`(RAM=0x9fc156ec, id=0x20000, length=0x14, op=0x12)` and validates magic
`0x55ac`. If that record cannot be read or has invalid magic, the function:

1. Stores `0x10000` in the flag word at `0x9fc155d0`.
2. Persists the flag with `(0x9fc155d0, 0x2f0000, 4, 0x13)`.
3. Reinitializes the 0x14-byte record and writes it with magic `0x55ac`.

The existing config startup path tests bit `0x10000` in record `0x2f0000`
and starts `playlist.ap` (application 10, parameter `0x4e`) when set.
`playlist.ap` clears the bit only after a successful update. Together, these
paths establish the boot-time first-index trigger when the `0x20000` settings
record is missing or invalid.

## Remaining boundary

This proves why an invalidated record triggers indexing; it does not show why
an updater would invalidate record `0x20000`. The boot initializer does not
explain indexing after powered card reinsertion. The address
`0x9fc155d0` is reused by mainmenu for a menu-order structure, but different
helper IDs and lengths establish separate persistent records. No card-event
callback or card-triggered flag write is identified here.

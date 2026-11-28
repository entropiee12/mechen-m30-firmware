# MEngine `dae_cfg` initializer cross-check

Read-only Ghidra decompile of community `mengine.ap`:

- Input SHA-256: `b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49`
- Function: `FUN_60b10f7c` at VA `0x60b10f7c`
- Decompile: [`mengine-init.c`](mengine-init.c), SHA-256
  `c46de7352f60face466363d75844d064b86b925b8304950856727660b82c80ae`
- Ghidra run log: [`ghidra.log`](ghidra.log), SHA-256
  `8d213106bb405c508edc59b35ed8099a1d145f2ed346c7695dc7332ecafc6298`

The decompile confirms stores to structure offsets `+3`, `+7`,
`+0x10..+0x8f`, `+0x0c`, `+0x0d`, and `+0xba/+0xbc/+0xbe`, using the same
config item IDs and band loop as the `config.ap` initializer
[`factory.c`](../config-dae-factory-2026-11-24/factory.c). It contains no
stores to `+8..+0xb` or `+0x90..+0x9d`. The function's pool helpers are
unresolved, so this decompile alone does not show the persistent-record
reader/writer or establish that the initializer is called only for an invalid
magic value.

Two bounded negatives are retained: [`mengine-refs.log`](mengine-refs.log)
found no direct code/data references to the initializer in this Ghidra
listing (SHA-256 `bdfacb6b20d3db4ec883378212655817cdb9538b271af8ffcf4bb60f74bca633`),
and [`mengine-f0000-immediate.log`](mengine-f0000-immediate.log) found no
decoded immediate `0xf0000` (SHA-256
`a668b46ab858063fb1e931fdc91a6fc8d188fb4144b2c930dac242779e3bb9f6`). Neither
excludes an indirect call or a record ID loaded from a pool.

No p-code execution, device test, or firmware modification was performed.

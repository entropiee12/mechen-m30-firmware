# Manager key-driver service-stub boundary

This note records the manager-side caller contract and the resident exception
stubs used by the driver-initialization path. The exception handler that
services these stubs is unavailable; the probe does not infer selector
semantics.

## Static references

- Hash-matched `manager.ap` SHA-256:
  `d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a`.
- `config.bin` SHA-256 (community, stock, and V1.101.11; byte-identical):
  `900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19`.
- The string `key.drv` is at file offset `0x1e40`. Its bank maps file
  `0x1e00` to runtime `0x7f056200`, so the string address is `0x7f056240`.
- Ghidra finds one code reference to that address, in `FUN_7f056200`; the
  caller is `FUN_7f056250`.
- `FUN_7f056200` calls in-overlay config getter `FUN_7f0962bc` with IDs
  `0x52`, `0x51`, `0x50` in that order. Decompiled getter code reads an
  indexed config record and returns the first 16-bit field.
- The wrapper then calls the MIPS16 pointer `0xbfc13de1` with
  `(9, packed, 0x7f056240, 10)`. This pointer targets a 4-byte resident
  exception stub at `0xbfc13de0`, not a known ROM function body.
- The `manager.ap` header maps file bytes `0x200..0x2b7` to
  `0xbfc13de0..0xbfc13e97`. The stub at `0xbfc13de0` loads `v1=15`, then
  executes `break`. Neighboring stubs load `v1=16` at `0xbfc13de4`, `9` at
  `0xbfc13de8`, `0` at `0xbfc13dec`, and `64` at `0xbfc13df0`, each followed
  by `break`. The exception handler that services these selectors is not in
  this segment.

## Parent initializer p-code trace

The same probe now enters the real MIPS16 instructions at
`FUN_7f056250` directly and runs until just before the resident event-loop
boundary at `0xbfc13df4`. It intercepts the in-overlay config getter and
returns the first 16-bit value from each hash-pinned `config.bin` record. The
observed getter order is `4, 5, 100, 0x52, 0x51, 0x50`. At the resident
selector stubs, the harness records the import number and four arguments,
then supplies synthetic `v0=0` service returns. Unmapped MMIO is represented
by zeroed RAM-backed memory.

The two import-15 calls with `a3=10` are, in order:

```text
(0, 0, 0x7f0562b4, 10)   # "nor_ud.drv"
(9, 0x01000202, 0x7f056240, 10)  # "key.drv"
```

The whole ordered service trace is asserted against the observed hash-pinned
profile, and the bytes at the runtime string addresses are checked against
the corresponding NUL-terminated names in `manager.ap`. The stop target is
also tied to the image: the manager pointer at file offset `0x1ed8` contains
the MIPS16-tagged address `0xbfc13df5`, and bytes at offset `0x214` map to
`0xbfc13df4`, where Ghidra decodes the expected MIPS16 `save` entry
instruction. The run reaches that function entry under synthetic returns;
it does not execute the loop, prove that boot enters this parent function, or
show that either driver was loaded. The selector-10 “driver open/load”
interpretation remains a caller-pattern hypothesis.

## Selector-10 caller return use

Read-only Ghidra decompilation of the hash-verified `manager.ap`
(`d94d2406…af7a`) shows `FUN_7f056200` calling selector 10 for `key.drv` and
returning without consuming the service's `v0`. Its parent `FUN_7f056250`
similarly ignores the selector-10 result for `nor_ud.drv` and returns constant
zero. The raw output is preserved locally at
`analysis/local-evidence/manager-key-driver-callers.c`; it is ignored by Git
because raw decompiler output stays outside tracked files.

This rules out caller-return-value sweeps as a way to infer selector
semantics. ROM-side effects remain unknown. The Ghidra project was opened with
`-readOnly`; its recorded executable SHA-256 matched the hash above.

## Config payload and p-code check

In the compared stock, community, and V1.101.11 `config.bin` files, the first
16-bit values of items `0x50`, `0x51`, and `0x52` are respectively `1`, `2`,
and `2`. The real manager caller was executed with a synthetic config getter
and synthetic exception-service return. It requested getters in order
`0x52,0x51,0x50` and ran the resident stub's selector load before reaching
`break` with `v1=0x0f` and arguments
`(0x9,0x01000202,0x7f056240,0xa)`. A second profile supplied getter results
`0x11,0x22,0x33` for items `0x50,0x51,0x52` and produced packed value
`0x11002233`. Both profiles returned through the real caller.

Reproduce with:

```sh
bash tools/ghidra/run_manager_key_driver_service_stub_probe.sh <hash-matching-manager.ap> <matching-config.bin>
```

The probe intercepts `FUN_7f0962bc` and the `break` after the resident stubs;
their returns are synthetic. It asserts the resident segment header maps file
`0x200`/size `0xb8` to `0xbfc13de0`, the mapped stub bytes match the
hash-pinned image, execution enters the stub and steps its selector-load
instruction, and the next PC is the `break` with the expected selector. It
also executes the manager caller and parent initializer, asserting config
getter order, full service-call order, runtime driver-name strings, Ghidra's
MIPS16 listing context, tagged MIPS16 return addresses, call arguments,
manager/config hashes, config payload values, and resident selector loads.
`EmulatorHelper` does not expose its active
ISA context through `readRegister()`, so this check is the program's decode
context plus the observed return-address tags; it is not an independent
runtime-mode assertion. Every service return is synthetic and the p-code entry
is directly seeded at the parent function. It does not show what selector 15
does, whether the exception handler installs either driver, or whether it
writes the key driver's BSS table `0x9fc14330..0x9fc14337`.

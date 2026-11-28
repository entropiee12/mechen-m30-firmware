# M30 key driver: static boundary

This note records the first analysis of `key.drv`. It is a Ghidra/static-byte
result, not a physical key trace and not a complete model of the ATJ2127 input
peripheral.

## Reproducible checks

The binary contract is checked by:

```sh
PYTHONPATH=tools/emu python3 tools/emu/key_binary_probe.py \
  analysis/community/fw/key.drv
```

The resident MIPS16 functions are mapped and decompiled by:

```sh
bash tools/ghidra/run_key_probe.sh analysis/community/fw/key.drv
```

The extracted `key.drv` SHA-256 is identical in the local community, stock,
first-pass, V1.101.11, V1.101.12, and V5 directories:

```text
858d56ff19106fccb75e35552c9cecca99dce0579c7daac5dce376d742f67ba1
```

Thus the key driver is not a community-only change in the available corpus.

## Container and mapping

`key.drv` is a compact `yqhx` image:

| Field | Value |
|---|---:|
| resident file offset | `0x200` |
| resident length | `0xa00` |
| resident destination | `0xbfc10000` |
| BSS | `0x9fc14328`, `0xd6` bytes |
| header entry field | `0x290094c4` |

For `FlatLoad.java`, file byte zero is therefore represented by virtual
address `0xbfc0fe00`; file `0x200` maps to `0xbfc10000`. The segment is
MIPS16 and the current static pass discovers nine SAVE-prologue functions.

## Functions and observed roles

| Function | Evidence-backed role |
|---|---|
| `FUN_bfc10008` | initial hardware/configuration writes and a control syscall |
| `FUN_bfc10084` | periodic state/control update; emits `0xf0` or `0xf1` through a driver callback |
| `FUN_bfc10178` | samples a 7-bit hardware value, classifies it against thresholds, and returns a normalized table value |
| `FUN_bfc101f4` | packages a key/event value and submits it through the driver callback |
| `FUN_bfc10220` | translates a normalized value into event messages and repeat events |
| `FUN_bfc102e4` | main scan/debounce state machine; calls the hardware update and classifier |
| `FUN_bfc103cc` | periodic sampling/state update; touches the input-control registers |
| `FUN_bfc10508` | thin wrapper around the indirect driver operation |
| `FUN_bfc10518` | applies two bounded configuration bytes, updates a control register, and resets state |

`FUN_bfc10178` reads the sampled value from a hardware-mapped location, masks
it with `0x7f`, and compares it against the resident threshold table at
`0xbfc103c0` (file offset `0x5c0`):

```text
03 18 2a 3a 4a 5f 6f 00
```

The final zero is the terminator. The function maps the selected range through
a RAM-resident table; that RAM table is initialized outside the `key.drv`
payload, so the physical key names cannot be recovered from this file alone.

`FUN_bfc10220` has three explicit special input values (`0x30`, `0x32`, and
`0x34`) and a repeat path. When a value remains unchanged, the state counter
reaches a first repeat event of `0x1000` and a subsequent event of `0x800`.
The exact system-message meaning is not assigned here because the consumer
and the hardware callback ABI are not yet proven.

## What this does and does not answer

This explains why the device can continue to react to keys while the display
is off: key sampling and event generation are in a resident driver path, not
in the now-playing UI overlay. It also gives us a safe boundary for a host
model of debounce/repeat behavior.

The host emulator also exposes the proven ADC classifier in
`tools/sim/key_driver.py`: it masks samples to seven bits, applies the seven
resident thresholds, returns `0xff` when no threshold matches, and keeps the
RAM-supplied normalized values explicit. The override path likewise requires
its external table value. `KeyDriverRepeatState` now carries the proven event
boundary into the application model: a new normalized value emits `0x2000`,
the fifth identical sample emits `0x1000`, and subsequent identical samples
emit `0x800`. The explicit `0x30` release, `0x32` to normalized key `0x0c`,
and `0x34` to normalized key `0x0d` paths are represented as well. The
external callback side effect, physical button names, and MMIO register
meanings remain unmodeled.

The key driver's banked export slot 3 at `0x29009584` was exhaustively run
for all 256 byte values with the real MIPS16 instructions and a RAM-backed
sample register. It returns 1 exactly when `(sample & 0x3f)` is in
`0x33..0x39` (28 input bytes including upper-bit aliases), otherwise 0; the
probe observed no MMIO writes. This is a verified range predicate for this
export, not a physical-key mapping or proof that the exported function is
called by the ADC classifier. Reproduce with:

```sh
bash tools/ghidra/run_key_export3_range_probe.sh <hash-matching-key.drv>
```

Two more banked paths from the pulled probes were executed against the
hash-matched driver. `FUN_bfc10064` stores the low byte of `a0` at
`0x9fc1433a`, returns pointer `0x9fc14334`, and ORs bit 31 into the
RAM-backed word at `0xc009005c` only when the low input byte is zero. This
records code behavior, not the MMIO register's hardware meaning. Separately,
the real `FUN_bfc10220` event formatter was run with synthetic normalized-key
sequences: input `0x02` emits payload `00020020` on sample 1, no payload on
samples 2–4, `00020010` on sample 5, then `00020008` on samples 6 and 7.
Special inputs `0x30`, `0x32`, and `0x34` emitted `00020004`, `000c0020`, and
`000d0020`, respectively. Regular inputs `0x31`, `0x33`, and `0x35` also
emitted `00310020`, `00330020`, and `00350020`; the tested `0xff` input emitted
no operation-18 payload. The probe now asserts both the positive payloads and
that negative case. Resident operations are stubbed, normalized inputs are
supplied by the harness, and sample counts do not establish elapsed time or
physical button identity. Reproduce with:

```sh
bash tools/ghidra/run_key_export7_effect_probe.sh <hash-matching-key.drv>
bash tools/ghidra/run_key_export8_abi_probe.sh <hash-matching-key.drv>
```

`FUN_bfc10518` was run through its bounded configuration-export path in two
synthetic states. With device-status word zero and input bytes `(9,9)`, it
stored `(9,9)`, left status byte zero, and changed control word
`a5a5f3ff` to `a5a573ff`. With status word `0x80` and input `(0,0)`, it
retained seeded config bytes `(2,3)`, set status byte 4, and changed
`1234abcd` to `123451ad`. Both cases reset the same four driver state bytes
to `(0,0,0,1)`. This demonstrates the two tested caller-side branches only;
MMIO is RAM-backed and device-status meaning is unknown. Reproduce with:

```sh
bash tools/ghidra/run_key_config_export_probe.sh <hash-matching-key.drv>
```

The existing status-path probe was rerun against this same driver. Real
`FUN_bfc103cc` instructions classify the ten-sample average into the seven
observed levels: `0..80→0`, `81..88→1`, `89..93→2`, `94..95→3`,
`96..103→4`, `104..107→5`, and `108..127→6`; a `0xd5` sample is masked to
`0x55` before classification. Its ten-sample batch transition also passed a
100-invocation sequence with status outputs `1,0`. Synthetic device-status
bits `0x080` and `0x100` each produced status 1, while `0x100` plus selector
bit `0x08` produced status 2; zero and `0x200` produced status 0. These are
verified caller-side inputs and state transitions with RAM-backed registers,
not interpretations such as battery/charging or real-time cadence. Reproduce
with:

```sh
bash tools/ghidra/run_key_status_probe.sh <hash-matching-key.drv>
```

`tools/ghidra/run_key_classifier_matrix.sh` executes the real MIPS16
`FUN_bfc10178` against all 256 input bytes with the override MMIO bit both
clear and set. The code and threshold table remain from the extracted
`key.drv`; synthetic MMIO is mapped at the literal addresses `0xc0020028` and
`0xc0020054`, and the driver's BSS is mapped at its real address
`0x9fc14328`. Seven distinct caller-supplied values occupy B+9 through B+15,
and the override value occupies B+8. All 512 results match
`classify_adc_sample()`, including high-bit aliases, threshold boundaries, and
the override branch. This verifies the classifier routine and table indexing;
it does not populate the unknown runtime normalization table or model
electrical ADC sampling.

The normalized-value table initializer remains unresolved. Scanning 1,311
files across `analysis/{community,first-pass-verify,local-evidence,stock,
v1.101.11,v1.101.12,v5}` and the 12 dated extracted corpora, including
`afi/FWIMAGE.FW`, `dec.bin`, and `fw/*`, found pointer words into the key BSS
only in `key.drv` and exact embedded copies of that driver in the containers.
The corpus reduced to 251 unique files by SHA-256. The scan checked
halfword-aligned words in the `0x9fc14300..0x9fc143ff`,
`0xbfc14300..0xbfc143ff`, and `0x1fc14300..0x1fc143ff` windows. All 14
`key.drv` copies share SHA-256
`858d56ff19106fccb75e35552c9cecca99dce0579c7daac5dce376d742f67ba1`.

This is negative evidence against a direct literal-pointer initializer in
those files, not proof that no computed or indirect initializer exists. An
earlier attribution to a “module 5, selector 0x18” call from
`FUN_bfc10008` was incorrect. In the key driver's syscall envelope, module
`0x0f` is selected by the immediate `li v1, 0x0f` before `break 0`; values
such as `5` and `6` are arguments. `FUN_bfc10084` sends its stack-backed
`0xf0`/`0xf1` event word with arguments `(5, buffer, 0, 0x18)`, while
`FUN_bfc10008` contains `(6, 0, 0, 0x1a)` and `(6, buffer, 0, 0x18)` calls.
The SDK maps module `0x0f` operations `0x18`, `0x19`, and `0x1a` to
`MQ_SEND`, `MQ_RECEIVE`, and `MQ_FLUSH`; the M30 call shapes structurally
match that mapping. These queue calls do not identify the key normalization
table initializer.

The real `FUN_bfc10008` caller was also run in Ghidra p-code up to its two
fixed-ROM exception boundaries with synthetic returns and zeroed synthetic
MMIO. At both boundaries, the raw `v1` register was `0xbfc10005`; the tagged
MIPS16 return addresses were `0xbfc1002d` and `0xbfc10041`. The meaning of
`v1` at this exception point is unresolved and it must not be labeled as a
module ID. Each call received return values `1`, `0xffffffff`, and
`0xdeadbeef` in isolation. Across these six profiles, the caller-visible
registers, driver BSS, mapped MMIO, stack, call arguments, and payload matched
the zero-return baseline. This is return-insensitivity of this caller under
the stated synthetic environment, not proof of the fixed-ROM syscall's
service or hardware effects.

Reproduce with:

```sh
bash tools/ghidra/run_key_init_syscall_boundary_probe.sh <hash-matching-key.drv>
```

Three adjacent fixed-ROM boundaries were separately exercised. The Export-6
message path receives `a3=9` and `a3=24` cases with tagged MIPS16 return
addresses; its `v1` value is a RAM pointer in the observed run, not an
established module number. Under synthetic status/MMIO values, status 0
reaches operation 9 and then operation 24 with message `0x00b0`; status 1
suppresses the message. In the module-`0x0f` operation-9 path, flag `0x82`
reaches operation 24 when operation 9 returns zero or `0x100`; returns 1 and
`-1` suppress it. The branch therefore distinguishes the observed values by
more than simple zero/nonzero truthiness. The operation-24 wrapper returns 1 for
synthetic service return 0 and 0 for returns 1, 2, `-1`, and `0xdeadbeef`.
These are caller-side branch effects under stubbed services and synthetic
memory, not descriptions of the unavailable resident implementation.

Reproduce with:

```sh
bash tools/ghidra/run_key_export6_message_probe.sh <hash-matching-key.drv>
bash tools/ghidra/run_key_module0f_op9_message_probe.sh <hash-matching-key.drv>
bash tools/ghidra/run_key_module0f_op24_return_probe.sh <hash-matching-key.drv>
```

The SDK's key path provides a separate lead: `key_rcode_scan.c` indexes
`key_map[i + 1]` for ADC ranges and `key_map[0]` for override, matching the
M30 driver's B+9+i and B+8 reads. SDK configuration code obtains the table
address from the installed key driver and copies 16 bytes from `keyID` into
it. This is verified SDK behavior; identifying the corresponding M30
configuration path is still a hypothesis. `manager.ap` contains the
`key.drv` name, but the config record layout has not been recovered. No
writer to B+8..B+15 has been found in `key.drv`; runtime values remain
unobserved.

The manager-side call boundary is now more specific. In the hash-matched
`manager.ap`, the `key.drv` string at file offset `0x1e40` maps to
`0x7f056240`; its only code reference is in `FUN_7f056200`, called from
`FUN_7f056250`. The wrapper calls the in-overlay config getter
`FUN_7f0962bc` with IDs `0x52`, `0x51`, and `0x50`, then calls the resident
MIPS16 stub at `0xbfc13de0` with `(a0=9, a1=packed, a2=0x7f056240, a3=10)`.
The stub loads `v1=15` and executes `break`; the exception handler that
services selector 15 is not present in this overlay. The
getter's decompiled code reads an indexed config record and returns its first
16-bit field. In the compared stock, community, and V1.101.11 config images,
the first words for items `0x50/0x51/0x52` are `1/2/2`; executing the real
manager caller with those getter results reaches the break boundary with
selector 15 and `a1=0x01000202`. A second
synthetic input `(0x11,0x22,0x33)` produces `0x11002233`, confirming the
caller's bit packing. The config-getter and exception-service returns are
synthetic in this probe. This narrows which config values manager submits,
but does not prove selector 15's meaning or that it initializes
the driver's B+8..B+15 normalization table.

### Selector-10 driver-open/load hypothesis

The parent `FUN_7f056250` has now been executed from its entry through the
last setup call, stopping before `0xbfc13df4`. With the hash-pinned manager and
config, the p-code trace requests config items `4, 5, 100, 0x52, 0x51, 0x50`
and reaches the resident import-15 stub twice with `a3=10`: first
`(0, 0, 0x7f0562b4, 10)` for `nor_ud.drv`, then
`(9, 0x01000202, 0x7f056240, 10)` for `key.drv`. The full ordered service
trace and runtime string bytes are now asserted by the probe. This strengthens
the caller-side evidence for a driver-open/load hypothesis and shows that the
key config word is passed in the same call. It still does not identify the
service implementation or prove that the config is applied.

The stop target is cross-checked against the same image: manager data at file
offset `0x1ed8` holds tagged MIPS16 pointer `0xbfc13df5`; the resident bytes
at file offset `0x214` decode at `0xbfc13df4` as the function's `save`
prologue. This identifies the observed stop as the event-loop function entry,
without executing the loop.

The probe starts with PC set directly to `FUN_7f056250`, uses zeroed synthetic
MMIO and synthetic zero returns at every resident service stub, and stops
before executing the event loop. Thus it does not prove the device's boot
path reaches this function or that the calls succeed. The selected ExcCode 9
target `0xbfc06f18` is in the unavailable fixed-ROM region; the extracted
kernel members do not contain its body. A public ATJ2127 note for the
different US212D firmware documents import-15 selector 8 as mount and
selector 9 as close, but does not identify selector 10
([reference notes](https://github.com/kilicdev/actions_flash/blob/main/RE_NOTES.md#L241-L259)).
Without the M30 ROM handler or another trace source, selector 10's semantics
remain unresolved.

### Selector-10 return values are unused by the manager callers

A read-only Ghidra decompile of the hash-verified `manager.ap`
(`d94d2406…af7a`) confirms that both selector-10 call results are ignored.
`FUN_7f056200` reads config items `0x52`, `0x51`, and `0x50`, packs them, and
calls the resident stub with `(9, packed, "key.drv", 10)` before returning
without reading `v0`. Its parent `FUN_7f056250` calls selector 10 for
`"nor_ud.drv"`, invokes `FUN_7f216200` and `FUN_7f056200`, then ultimately
returns constant `0`; it does not branch on either call result.

Therefore, sweeping synthetic selector-10 return values cannot distinguish
caller-side behavior. This does not reveal selector 10's meaning or any
out-of-band effect inside the unavailable ROM handler. The raw decompile is
kept in the ignored `analysis/local-evidence/manager-key-driver-callers.c`;
the checked-in evidence summary and JSONL fact capture the interpretation.

Reproduce with:

```sh
bash tools/ghidra/run_manager_key_driver_service_stub_probe.sh <hash-matching-manager.ap> <matching-config.bin>
```

The evidence record is in
[`key-driver-service-stub-2026-11-25`](evidence/key-driver-service-stub-2026-11-25/README.md).

## Kernel exception-vector table (separate from the key table)

The kernel exception table is now statically traced, and must not be confused
with the key driver's BSS normalization table. `ker_init.bin` has SHA-256
`f4c62848fb06ccbe6fba4323c4e29e46791c1abb25af648f0518eb0747492ec8` and
maps at `0xbfc32000`. Its entry flow calls the table constructor at
`0xbfc32bc8`; the constructor calls the MIPS16 setter at `0xbfc330a0`, which
stores `table[a0] = a1` at table base `0x9fc1a9a4`. The base pointer is the
literal at file offset `0x10dc` / VA `0xbfc330dc`. The setter accepts indices
0 through 13 and rejects a zero handler.

The constructor writes index 9 as `0xbfc06f18` (setter call at file offset
`0xc54` / VA `0xbfc32c54`). `kernel.drv` has SHA-256
`58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6`; its
MIPS32 exception vector at file offset `0x380` / VA `0xbfc08180` loads the
handler from `0x9fc1a9a4 + (Cause & 0x7c)`. ExcCode 9 therefore reads entry
`0x9fc1a9c8`, whose statically installed value is `0xbfc06f18`. The earlier
expected value `0xbfc34075` is disproved by this init-time write.

This establishes the extracted kernel's static table setup and vector lookup,
not runtime immutability or the handler's behavior. The target `0xbfc06f18`
is in the unavailable fixed ROM. No static link has been established from
that ROM handler to `ker_xsys.bin` entry `0xbfc34074`; that possible handoff
remains a hypothesis. The M30 key normalization table at B+8..B+15 is a
different table and remains unresolved.

The extracted `ker_init.bin` constructor at `0xbfc32bc8` has now been
executed in Ghidra p-code with its real MIPS16 setter at `0xbfc330a0` and a
sentinel-backed synthetic RAM table. `tools/ghidra/run_kernel_exception_table_init_probe.sh`
stops at `0xbfc32c5c`, immediately after the setter calls and before later
global initialization or fixed-ROM calls. The run verified all 14 accepted
slots: indices 0, 9, and 10 contain their constructor overrides, while the
other 11 contain `0xbfc19a75`; all 18 out-of-range slots 14..31 retain their
sentinels. This executes the extracted constructor logic, not the device boot
sequence; it does not establish that a particular device actually invoked
this constructor or reveal fixed-ROM behavior.

The public firmware changelog supplies a second, application-level distinction:
2022-08-16 says that the scroll wheel and all buttons are disabled while
locked, while 2022-08-05 and 2024-02-28 explicitly retain play/pause,
previous/next, and volume controls. `tools/sim/key_filter.py` now represents
these two observed policies as `WAKE_ONLY` and `MEDIA_KEYS_PASS`. The existing
`patched=True` argument remains a compatibility alias for the latter; this
does not assign physical names to normalized key values or claim a debounce
timing.

`tools/sim/key_input.py` composes these proven boundaries into one sample
session: caller-supplied ADC normalization values and override value pass
through classification, repeat-state event generation, and the selected
application key-filter policy. Each sample trace retains the classification,
optional driver event, and optional filter decision. Repeat counts remain
sample counts; the model assigns no physical key labels or real-time cadence.

It does **not** yet prove:

- which physical button corresponds to each normalized value;
- the meaning of the hardware registers or the callback at the header entry
  field `0x290094c4`;
- whether any key event forces the LCD/backlight on;
- application-specific consumers beyond the shared key filter;
- timing values in real milliseconds.

No flashable patch is proposed from this analysis. Further progress on this
path needs evidence for the RAM normalization table, physical ADC mapping, or
real sampling cadence; these are left as caller-provided or unresolved.

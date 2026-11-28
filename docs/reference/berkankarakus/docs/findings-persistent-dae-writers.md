# Persistent settings and DAE writers (A3)

## Reproduction and scope

Run `bash tools/ghidra/run_persistent_dae_writers_probe.sh`. The wrapper checks
SHA-256 before mapping five community overlays with `LoadAP.java`, decompiles
named persistence functions, then scans every mapped prologue function for
decompiled `+0x18/+0x19` stores and `0xf0`-byte op-`0x13` calls. It writes the
full logs and decompilations to a temporary output directory printed on the
first line. The completed scan covered `config` 342, `mengine` 218, `music`
819, `setting` 390, and `mainmenu` 531 functions, with zero decompile failures
and a 1200-function cap per overlay. The probe is static; **no synthetic
services** are used. It does not execute a device, ROM service, or AP lifecycle.

Input SHA-256 values verified with `shasum -a 256 analysis/community/fw/{config,mengine,music,setting,mainmenu}.ap analysis/community/fw/config.bin`:

| Input | SHA-256 |
| --- | --- |
| `config.ap` | `5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580` |
| `mengine.ap` | `b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49` |
| `music.ap` | `662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3` |
| `setting.ap` | `ad46a92fc0b12f58fdad3a7885502ef5e7481c7b2913fd7e28d46216d22a9ed7` |
| `mainmenu.ap` | `1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105` |
| `config.bin` | `900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19` |

## Target byte stores

**VERIFIED:** `config.ap` `FUN_40dd4c00` seeds settings `+0x18` from item
`0x96` and `+0x19` from `0x97`. The same initializer code is mapped in other
overlays. This is the default path, not evidence of a later UI write.

**VERIFIED:** The post-initialization direct stores found for settings `+0x19`
are:

| Overlay | Function(s) | Store and pool-resolved base |
| --- | --- | --- |
| `music.ap` | `FUN_403d4cec`, `FUN_403d4d18`, `FUN_403d4d44`, `FUN_403d4d70`, `FUN_403d4d9c`, `FUN_403d4dc8`, `FUN_403d4df4` | Write selector `0..6` respectively to `0x9fc156f4+0x19`. |
| `mainmenu.ap` | `FUN_40154cec`, `FUN_40154f44` | Restore a saved selector or write `7` to `0x9fc155f4+0x19`. |
| `mengine.ap` | `FUN_601d0d58` | Mirror the message selector from its `+0x41` state byte to the loaded settings copy `0x9fc15d9c+0x19`; its builder reads copy `+0x18` as a gate. |

The output records each pool word used to resolve these bases. **INFERENCE:**
music and mainmenu may later persist their changed selector through their
separate id-0 save paths (`FUN_40054ce8` and `FUN_40014fac`). This scan did
not execute the callback-to-save lifecycle. `setting.ap` has an id-0 save path
but no identified direct target-block `+0x18/+0x19` post-initialization store.

**UNKNOWN:** No target-block post-initialization `+0x18` store was identified
within the mapped prologue functions of these five overlays. This eliminates
direct decompiled offset stores in that bounded set only. Non-prologue code,
indirect pointer writes, whole-block copies, and other overlays or resident
code remain possible. The scan's other `+0x18/+0x19` candidates use generic
parameters or pool-resolved bases outside the target settings blocks, so they
are not attributed to the persistent settings bytes.

## Record id `0xf0000`

**VERIFIED call sites and literal pool words:**

| Overlay and function | Branch | Service call |
| --- | --- | --- |
| `config.ap` `FUN_40054ccc` | Read failure or magic other than `0x55b9` during initialization | `(0x9fc15700, 0xf0000, 0xf0, 0x13)` through pool service `0xbfc16585` |
| `config.ap` `FUN_40054f38` | Normal save path, called by `FUN_40054f84` | `(0x9fc15700, 0xf0000, 0xf0, 0x13)` through `0xbfc16585`, after an id-0 `0x38`-byte call |
| `mengine.ap` `FUN_60050d00` | Read failure or magic other than `0x55b9` during initialization | `(0x9fc15dd4, 0xf0000, 0xf0, 0x13)` through pool service `0xbfc11cc9` |

**INFERENCE:** `0x13` is the persistent write operation by the established
service ABI and the existing synthetic service probe. This static run verifies
the calls and operands; it does not verify physical flash writes or ordering
across overlays. **UNKNOWN:** No other `0xf0`-byte op-`0x13` call appeared in
the 2300 mapped functions scanned across these five overlays. Calls with
different lengths, computed operands, or code outside this mapping are not
eliminated.

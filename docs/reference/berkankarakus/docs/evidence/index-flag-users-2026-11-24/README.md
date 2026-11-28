# Automatic-index flag: AP readers and writer

## Inputs and reproduced decompiles

The following community AP hashes were checked:

- `config.ap`: `5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580`
- `mainmenu.ap`: `1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105`
- `playlist.ap`: `184f2c201537e6657024a2a5a054d12fe4a7f134e06205f0786a8d71d2a29403`
- `music.ap`: `662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3`
- `usound.ap`: `e67af2588eea49bf1f81a588ac252636e3678a756d39013e46605cc16afa96cf`
- `udisk.ap`: `a7c5a1fe9b73601bfad849fc71cff7f5c919b39f740e92d5e1864435f0c1cae4`
- `setting.ap` (raw-hit control):
  `ad46a92fc0b12f58fdad3a7885502ef5e7481c7b2913fd7e28d46216d22a9ed7`
- Driver scan inputs: `kernel.drv`
  `58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6`,
  `card.drv`
  `fc355c17258fe6fca184e5a616ec4c0b151a566caa99feba9b14981a046063bf`,
  and `cards.drv`
  `3c7fb89cf2e8e2f8bdb2311b386e7047f17c35b5846e71139a105255b88462c1`.

Ghidra 12.2 `LoadAP.java` and `FindImmediateAndDecompile.java` were run on
the reported functions. Logs:

- `config-index-writer-decompile.log` in the sibling
  [`index-flag-writer evidence`](../index-flag-writer-2026-11-24/README.md)
- [`playlist.log`](playlist.log), SHA-256
  `4c6b307e55f6886d69e751ed850f9dda7c52fb165a969c997d2dc81f32e092e7`
- [`playlist-finalizer.log`](playlist-finalizer.log), SHA-256 recorded in
  `11745cb51560af003ba0fe6e79b081be475ab81d2d12f53a315dc94a128a8ae1`
- [`playlist-finalizer-functions.log`](playlist-finalizer-functions.log),
  SHA-256 `480cae05d729a4a83bac23ab43bc3d6782f012ce2b052b41fa67147d8392659b`
- [`usound.log`](usound.log), SHA-256
  `9ef0aeb3a28e48ffaf15c81da8e84e1589e5c5f06d46ae167aa9abe7a4b1e993`
- [`udisk.log`](udisk.log), SHA-256
  `e56d0a1835183917f2624cbb009e6ce219c48f0531a8d1c903a4d17121762212`
- [`setting-flag-immediate.log`](setting-flag-immediate.log), SHA-256
  `ee52cfa9fb0064f6f464a1c751494954bd04452e72a00cc42d9605a973e3ca06`

The flag record is ID `0x2f0000`, length 4. The number is a persistence
record ID, not a physical flash address.

## AP behavior

- `config.ap` `FUN_40054ccc` reads the flag. If its separate settings record
  `(ID=0x20000, length=0x14)` is missing or has invalid magic `0x55ac`, it
  writes `0x10000` to the flag record and reconstructs that settings record.
- `playlist.ap` `FUN_48094400` reads the flag and clears bit `0x10000`, then
  writes the four-byte record. Its caller `FUN_48094658` runs this clear in
  the library-update finalization path, before its final status-message call.
- `usound.ap` `FUN_40054de0` reads the flag with mask `0x10000`. When the
  relevant state is set, it prepares message `0x0201` with payload flags
  including `0x4000 | 0x0f0a`, sends it, and returns `0x861c`.
- `udisk.ap` `FUN_40054fd0` reads the flag and, when its callback state byte
  is set, prepares the same message/payload path and returns `0x861c`.
  These two callback functions do not issue an op-`0x13` write to the flag.

Raw pool words were independently checked for the `usound.ap` and `udisk.ap`
callbacks:

| AP | File offset | Value |
|---|---:|---:|
| `usound.ap` | `0xbf0` | `0x2f0000` |
| `usound.ap` | `0xbfc` | `0x00010000` |
| `usound.ap` | `0xc00` | `0x9fc155c9` |
| `udisk.ap` | `0x19e0` | `0x2f0000` |
| `udisk.ap` | `0x19ec` | `0x00010000` |
| `udisk.ap` | `0x19f0` | `0x9fc155c7` |

The reported corpus-wide literal scan also found the ID in `music.ap` and in
filesystem/kernel drivers. The `music.ap` occurrence has no identified
decompiled user. Driver occurrences are adjacent to a flash-layout table and
do not by themselves prove a flag read or write. No driver-side setter is
claimed here. A raw byte scan also finds the byte sequence beginning at
`setting.ap+0x6d82`, but it crosses adjacent 16-bit values; Ghidra's scalar
immediate scan finds no `0x2f0000` instruction operand in `setting.ap`. This
raw match is not evidence that setting.ap accesses the record.

## What this closes, and what remains

At the AP level, the boot initializer is the only identified setter, and the
playlist finalization path is the identified clearer. The `usound.ap` and
`udisk.ap` callbacks consume the flag to choose a status/exit path; they do not
write the persistent record. This supports the USB-removal/update path
reported for `udisk.ap`, while the exact event-to-callback and callback-to-
builder call chain remains to be established from the dispatcher.

## `0xb0` AP escalation and driver-side search

Ghidra decompilation of `udisk.ap` `FUN_40015150` shows a call through
resident entry `0xbfc16a59` with arguments `(0, 0xb0, 0xbfc169a1, 8)`. Its
main loop calls `FUN_40014ccc`, which asks resident entry `0xbfc16549` for an
event code; codes `0xb0` and `0xb1` set separate local state bytes. This is an
AP-side registration/polling path for `0xb0`. The resident service and actual
producer are outside the AP body, so the card meaning remains an inference.

The shared `FUN_48bd4600` handler returns `0x861c` for message `0xb0`. The
music AP return dispatcher `FUN_40614fc0` maps return `0x861c` to app ID 10,
with parameter `0x42`, `0x82`, or `0xc2` according to a state byte, and
clears a separate 0xa8-byte record. This proves an AP route from the return
code to app 10; it does not prove those parameters are equivalent to the
known boot index parameter `0x4e` or that every caller reaches this music
dispatcher.

### State-dependent app parameter selection

The hash-pinned decompile now identifies the selector and its observed
writers. `FUN_400552a0` passes the result from `FUN_40054ff4` to
`FUN_40614fc0`. For return `0x861c`, the dispatcher prepares message `0x0201`
for app ID 10 and reads the byte at `0x9fc15588` (pool pointer
`0x40615148 = 0x9fc15584`). Its mapping is:

| State byte at `0x9fc15588` | App parameter |
|---:|---:|
| `0x10` | `0x42` |
| `0x07` | `0x82` |
| any other value | `0xc2` |

`FUN_40054d88` initializes that byte to `0x10`; conditional state restore may
replace it from a saved 0xa4-byte record. In `FUN_40054ff4`, an inner return
of `0x861e` writes `0x07` to the selector byte before returning. The
dispatcher and pool values were reproduced with
[`music-return-state.log`](music-return-state.log), SHA-256
`878cd5b0289470cea0b73212171692812bdd79049de03f5d15b3918f0cae631e`; trailing
Ghidra log padding was trimmed for the retained text. Run
`bash tools/ghidra/run_music_index_return_probe.sh <hash-matching-music.ap>`
to repeat. These are control-state values, not decoded product labels. Their
playlist-side behavior is traced in
[`playlist parameter evidence`](../playlist-param-index-2026-11-24/README.md).
P-code with a synthetic op-9 return of 0 reaches both MUSIC.LIB and M3U.LIB
callbacks for `0x42`, `0xc2`, and boot parameter `0x4e`; `0x82` reaches neither.
For `0x42`, changing op 9's synthetic return to `-1` suppresses both callbacks.
This narrows the caller's control contract, but the resident op-9 semantics,
ROM-to-AP launch forwarding, and connection to card insertion remain unknown.

## Application ID table

The `manager.ap` application-name table at file offset `0x2634` uses 12-byte
slots. The local community file hash was checked as
`d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a`; a raw
byte dump confirms IDs 0–18 map in order to `music`, `browser`, `record`,
`radio`, `setting`, `user1`, `user2`, `linein`, `manager`, `mainmenu`,
`playlist`, `config`, `udisk`, `mengine`, `fmengine`, `alarm`, `mtp`,
`upgrade`, and `usound` (each is the corresponding `.ap` filename). The
extracted corpus lacks `record`, `radio`, `user1`, `user2`, `linein`,
`fmengine`, `alarm`, and `mtp`.

This confirms that app ID 10 in the `0x0201` launch messages names
`playlist.ap`, while ID 9 names `mainmenu.ap`, ID 4 `setting.ap`, ID 3
`radio.ap`, and ID 1 `browser.ap`. The table identifies application names;
it does not define launch-parameter semantics. In particular, the music
dispatcher parameters `0x42`, `0x82`, and `0xc2` are not yet shown equivalent
to the verified boot index-update parameter `0x4e`.

A seven-AP Ghidra immediate scan (`config`, `mainmenu`, `setting`, `music`,
`udisk`, `usound`, `playlist`) found one instruction loading literal `0xb0`:
`udisk.ap` `FUN_40015150` (`li a1,0xb0`). Other hits were PC-relative loads
using displacement `0xb0`, address arithmetic, or the shared handler's
comparison. In the decoded portions of `kernel.drv`, `card.drv`, and
`cards.drv`, hits were loads with displacement `0xb0` or an unrelated unsigned
comparison; no event-message construction was identified. The existing
bounded card-driver probe still finds only the controller setup path in
`cards.drv`.

Retained outputs:

- [`music-dispatch.log`](music-dispatch.log), SHA-256
  `a57955d4b8d31a61e096c8c05606e4b39b50fc4da7c75d6f4c4f6a0462cc19aa`
- [`udisk-b0-producer.log`](udisk-b0-producer.log), SHA-256
  `6a8d6a40d55ae932797ea96fecd364eb2e5b5d853f6bfa112912394b1985c3b2`
- [`playlist-entry-params.log`](playlist-entry-params.log), SHA-256
  `dfad9842118564c559a782458d6b98433ddcf40bd1f19128c072daab32aba7ab`
- Seven `*-b0-immediate.log` AP scans; only `udisk.ap` loads `0xb0` as an
  immediate argument.
- [`kernel-b0-candidates.c`](kernel-b0-candidates.c) and
  [`cards-b0-candidates.c`](cards-b0-candidates.c), plus their Ghidra logs,
  retain the bounded candidate-function decompiles.

Thus the AP path is narrowed to an event delivered through a resident
service, then the shared `0xb0` handler and `0x861c` return route. The source
of that event, its relationship to physical card insertion, and whether app
10 parameters `0x42/0x82/0xc2` invoke the index builder remain unresolved.

## Later report: extra consumers and 12-driver scan (not reproduced here)

A subsequent report names `config.ap` `FUN_40054f84` and the resident event
resolver in `mainmenu.ap` as additional `0xb0` consumers. It describes the
UDisk call as a subscription and reports a scan of 12 drivers with no aligned
message-construction immediate. No raw logs, hashes, or exact 12-file input
list accompanied that report, so these are kept separate from the reproduced
Ghidra findings above. The producer and the event's relation to card
insertion remain unknown.

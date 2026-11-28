# Automatic-index guard: stock versus community

This is an offline Ghidra comparison of stock V1.101.10 and community
V1.292.00. It does not prove behavior on every M30 unit, but it resolves what
the two one-byte community changes do in the surrounding control flow.

## Reproduction

```sh
bash tools/ghidra/run_index_guard_probe.sh \
  analysis/stock/fw analysis/community/fw
```

The probe decompiles the same entry locations from both AP sets and checks the
raw bytes at the patch offsets.

## What the comparison shows

### `config.ap + 0x1e0c..0x1e0d`

The community patch changes bytes `0d 22` to `0d 10`. The decompiler's
`in_v0 == 0` description is an artifact and should not be used as the semantic
condition. The automatic-index startup decision is a separate four-byte
persistent record with ID `0x2f0000`: `config.ap` tests bit `0x10000` and,
when set, starts `playlist.ap` (application 10, parameter `0x4e`). Its
boot-time writer is now identified below. The powered-card event path remains
unresolved.

### `udisk.ap + 0x1908..0x1909`

The community patch changes a conditional branch to an unconditional branch.
This removes that particular dirty-state path; it does not prevent another
component from setting or consuming the `0x2f0000` flag, or from starting the
playlist builder directly.

`playlist.ap` clears bit `0x10000` in its library-update finalization path.
These two community changes affect only their specific guard paths. They do
not establish a general automatic-index bypass or identify what produces the
powered-card event.

### Dated official-corpus control check

The two instruction bytes were checked in the nine newly extracted official
corpora. Every dated official file retained `0d 22` at `config.ap+0x1e0c` and
`08 22` at `udisk.ap+0x1908`; the community baseline has `0d 10` and `08 10`,
respectively. This supports the hypothesis that these byte changes are
community-specific rather than a general property of the M30 firmware line.
It still does not identify the separate powered-card event path observed on
the device.

## Interpretation of the recent device test

The reported powered-card test used a V1.292.00-based image with the two
community guard changes. Its exact device image hash was not recorded in the
evidence supplied here, so record the firmware version separately from a
byte-verified image identity.

## Physical result on this M30

User-reported test on 2026-11-15: with the two guard changes present in the
V1.292.00-based image, removing and reinserting the card while powered still
started indexing.

This is a device observation, not a static inference. It shows that those two
guard changes do not prevent indexing after powered card reinsertion on the
tested unit. The music AP's `0x861c` dispatcher may send app ID 10 with
`0x42`, `0x82`, or `0xc2`. In a controlled p-code probe, `0x42` and `0xc2`
reach both MUSIC.LIB and M3U.LIB callbacks when synthetic resident op 9 returns
0; `0x82` reaches neither. Returning `-1` from op 9 suppresses both for `0x42`.
This makes the AP route a stronger candidate for indexing, but the device
observation does not prove that the resident event launches this route or that
it accounts for the observed card-insertion behavior.

The host model keeps these outcomes separate: the static guard contract uses
`CardChangePolicy.MANUAL_UPDATE_REQUIRED`, while the tested powered-card
removal-and-reinsertion observation is represented explicitly by
`CardChangePolicy.AUTOMATIC_REBUILD`. The latter requires a file count and
enters the normal bounded update state; it does not claim that every card or
startup trigger behaves this way.

## Next discriminating observation

If a read-only device flash read is available, compare the four-byte word at
`0x2f0000` before and after powered card reinsertion. A newly set `0x10000`
bit would direct the search toward its setter; an unchanged word would favor a
direct app launch or another trigger. A static candidate is the
`0x2f0000` reference in `kernel.drv` at file `0x33b8`; `card.drv` and
`cards.drv` contain
no known index call or flag literal. A main-menu event path involving message
`0xf2` carries the value `0x10000`, but no link from that event to card
detection, the flash-word setter, or playlist launch has been established.
Treat it only as a candidate for the powered-card path. A boot-time setter
has been verified. `udisk.ap` registers a callback-style resident request for
message `0xb0` and polls resident event codes `0xb0`/`0xb1`; the resident event
producer and its relationship to card insertion remain unknown.

### Boot-time flag writer and main-menu address reuse

Ghidra decompilation of `config.ap` `FUN_40054ccc` shows it reads the flag
using `(0x9fc155d0, 0x2f0000, 4, 0x12)`. It also reads a separate 0x14-byte
record `(0x9fc156ec, 0x20000, 0x14, 0x12)` and checks magic `0x55ac`. If that
record is unreadable or its magic is invalid, it sets the flag word at
`0x9fc155d0` to `0x10000`, persists it with
`(0x9fc155d0, 0x2f0000, 4, 0x13)`, then rebuilds and writes the 0x20000
record. The startup check launches `playlist.ap`; `playlist.ap` clears the
bit only after successful indexing. This proves the boot trigger after the
0x20000 record becomes invalid, but does not show why an updater would
invalidate that record.

The same numeric RAM address in `mainmenu.ap` is a different persistent
record: `(0x9fc155d0, 0x10000, 0x22)`, magic `0x55ab`, used for menu order.
Different IDs and lengths establish distinct stored records. RAM address
reuse alone does not imply shared data between overlays.

### Other AP readers

Focused decompiles of community `usound.ap` and `udisk.ap` show callback
functions `FUN_40054de0` and `FUN_40054fd0` reading the four-byte record with
ID `0x2f0000`. The usound callback checks mask `0x10000`; when the callback
state is set, both functions prepare message `0x0201` with payload flags
including `0x4000 | 0x0f0a`, send it, and return status `0x861c`. Neither
callback writes the persistent flag. This is consistent with the
USB-removal/update path in `udisk.ap`, but the event-dispatcher-to-callback
and callback-to-builder edges still need proof.

`playlist.ap` `FUN_48094400` reads the same record, clears bit `0x10000`,
and writes it back. Its caller `FUN_48094658` places the clear in the
library-update finalization path. `config.ap` remains the only identified AP
setter: it sets the bit when the separate ID-`0x20000` settings record is
missing or invalid. A corpus-wide literal scan also reported occurrences in
`music.ap` and filesystem/kernel drivers; the music occurrence has no known
decompiled user, and driver literals alone do not prove access to this
record. A raw byte hit in `setting.ap` at file `0x6d82` crosses adjacent
16-bit values and is not a decoded `0x2f0000` immediate; the focused Ghidra
scan found no such instruction operand there. Hashes and focused decompile
logs are in
[`index-flag-users evidence`](evidence/index-flag-users-2026-11-24/README.md).

`udisk.ap` `FUN_40015150` passes event code `0xb0`, callback pointer
`0xbfc169a1`, and length 8 to resident entry `0xbfc16a59`. Its event loop
queries resident entry `0xbfc16549` and branches on codes `0xb0`/`0xb1`.
The shared AP handler maps message `0xb0` to return `0x861c`; the music AP
return dispatcher passes that code to app ID 10 with a parameter selected
from state byte `0x9fc15588`: state `0x10` → `0x42`, state `0x07` → `0x82`,
other values → `0xc2`. `FUN_40054d88` defaults that byte to `0x10`; its saved
state path can replace it, and `FUN_40054ff4` sets it to `0x07` after inner
return `0x861e`. `manager.ap` confirms app ID 10 is `playlist.ap`. This
identifies how the three parameters are selected, but not what they mean or
whether any starts indexing; the known boot index-update parameter remains
`0x4e`. The resident event producer is outside the corpus, and whether its
event means card insertion is still unknown. Reproduce the return mapping
with `bash tools/ghidra/run_music_index_return_probe.sh <hash-matching-music.ap>`;
the retained outputs are in the linked evidence directory.

### Additional reported `0xb0` consumers and producer search

A later static-analysis report identifies two more consumers: `config.ap`
`FUN_40054f84` has an `aiStack[0] == 0xb0` branch, and the resident event
resolver in `mainmenu.ap` calls the shared handler for `0xb0` before another
resident routine. The report also says `udisk.ap` subscribes through
`kernel_call(0, 0xb0, callback, 8)` and waits for `0xb0`/`0xb1`. These details
were not accompanied by raw logs or input hashes in the report, so they are
recorded as reported findings, not independently reproduced evidence.

The same report says a scan of 12 drivers found no aligned `li ...,0xb0`
message construction. Unaligned byte hits in `card.drv`, `uhost.drv`,
`key.drv`, and `drv_ui.drv` were not identified as instructions or data.
Therefore the producer remains unknown; deriving the event from hardware in
ROM/kernel is plausible but not established. In particular, neither the
event's meaning nor its connection to card insertion has been proven.

The `manager.ap` table also clarifies the application IDs in the `0x0201`
launch messages: ID 10 is `playlist.ap`, ID 9 is `mainmenu.ap`, ID 4 is
`setting.ap`, ID 3 is `radio.ap`, and ID 1 is `browser.ap`. The table does not
define the launch parameters. The complete 19-entry mapping and verified
`manager.ap` hash are recorded in the evidence README.

The currently decoded MIPS16 import calls in `mainmenu.ap` contain no
`a3` import selector `0x4e` (59 calls scanned). This does not test for an
application-launch argument `0x4e`; the import selector and app parameter
are different call fields. No conclusion about a main-menu app launch can
be drawn from this scan. The card-insertion-to-indexing path remains
unresolved beyond the AP/resident route above. Several other raw pointer hits
overlap decoded instruction addresses, so the bytewise pointer scan is not a
call-reference list; the remaining hits need instruction-level classification.

The driver-side follow-up is bounded in
[`findings-card-reinsert-trigger.md`](findings-card-reinsert-trigger.md): the
exported `card.drv` vector contains block-I/O/control functions, while the
`cards.drv` probe helpers are only called from that driver's entry/setup
function. The outer event dispatcher and powered-reinsert route remain open.

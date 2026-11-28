# Related MECHEN M30 projects

Read-only review of the public repositories below, checked 2026-11-20. These
notes separate reusable evidence from claims that have not been demonstrated
on the M30. No repository was cloned and no device operation was performed.

## Repositories at a glance

| Repository | What it provides | Relevance here |
|---|---|---|
| [janhellion/m30sync](https://github.com/janhellion/m30sync) | Host-side music transfer, tag normalization, playlist copying, and single-file album concatenation | Useful as a lead for clean metadata and a file-level gap workaround; its current `--dry-run` mutates source tags, so do not treat it as safe preview behavior |
| [vichj99/mechen-m30-rockbox](https://github.com/vichj99/mechen-m30-rockbox) | M30 register-analysis notes, a Rockbox target scaffold, and an `actions_flash` compatibility patch | Useful cross-check for register hypotheses and USB transport diagnosis; not an integrated or hardware-validated Rockbox port |
| [dev-zetta/mechen-m30-community-firmware](https://github.com/dev-zetta/mechen-m30-community-firmware) | Release images, hashes, and a detailed release changelog | Useful as a versioned binary/provenance reference; source reconstruction and build/test tools are not included in that repository |
| [averagenative/mechen-m30-firmware](https://github.com/averagenative/mechen-m30-firmware) | M30 firmware patches, reverse-engineering notes, build/repack tools, and emulator probes | The active research and implementation tree for this project |

## `m30sync`

The tool keeps a small tag set and can transfer playlists. Its filenames use
track number plus artist/title, which can be useful when experimenting with
filename-driven order. Its `--gapless` path concatenates an album into one
FLAC with CUE markers; this is a file-preparation workaround, not native
multi-file gapless playback.

The following observations are scoped to the repository's inspected `main`
snapshot (`9e649512`), not to future revisions. There are two material
safety/correctness issues in that code:

1. The [`--dry-run` path](https://github.com/janhellion/m30sync/blob/9e649512/m30sync)
   calls `strip_tags()`. Both the FLAC and MP3 versions rewrite tags in the
   source file before the preview returns. The ordinary transfer path also
   normalizes the source before copying it. The separate
   [`m30_tag_fix.py`](https://github.com/janhellion/m30sync/blob/9e649512/m30_tag_fix.py)
   helper likewise edits files in place.
2. The album merge forces 44.1 kHz, 16-bit, stereo output. FLAC encoding is
   lossless for that resulting PCM, but converting higher-resolution, higher
   bit-depth, or non-stereo input is not bit-perfect to the source. The tool
   also offers to delete `MUSIC.LIB`, `ALBUM.PIC`, and `M3U.LIB` to trigger a
   rebuild.

Therefore, do not run the current preview or cleanup commands against the
user's source files or M30 card without changing the code and making a backup.
The transferable idea is a separately generated sanitized copy; preserve the
original tags and artwork, and verify actual M30 indexing before relying on the
chosen tag subset.

## `mechen-m30-rockbox`

The repository identifies the M30 as an Actions GL5110/ATJ2127 MIPS32+MIPS16
device and labels its register facts with `[SPEC]`, `[TRACED]`, `[SRC]`,
`[INFER]`, and `[ASSUME]`. That confidence labeling and its error-correction
record are useful. Its notes can help cross-check register names and peripheral
roles, but claims based on the included GL5110 specification header or leaked
SDK are external evidence; they still need to agree with the M30 binary and
observed call path before entering our emulator model.

The project itself calls the Rockbox target a scaffold and says it has not
been integrated into a Rockbox build or run on hardware. Its index/status documents
also disagree about the scaffold directory name (`rockbox-port-scaffold/`
versus the repository's `rockbox-port/`). Do not treat the advertised
register-map audit as proof that a bootable Rockbox port exists.

### ADFU/USB lead, not a working ROM-read path

In the inspected [`main` snapshot](https://github.com/vichj99/mechen-m30-rockbox/blob/cb3a007e/tools/actions_dump-m30.patch),
the `actions_dump` USB-response check increments the SCSI tag but no longer
rejects a response with a mismatching tag. The patch's note says the M30
returns tag zero. The repository author reports that their `adfu_reboot`
second-step attempt (`0xcb 0x21`) returned `ACTIONSUSBD` instead of
`0xff 0x00` and left the device in FlashDisk mode; the note also says boot-key
ADFU entry had an unreliable first USB send.

This is a concrete lead for interpreting USB-connect failures, but the repo
does not demonstrate a successful transition to ADFU or a ROM/RAM read on an
M30. Do not treat `adfu_reboot` as a proven entry procedure. Keep the existing
readback path gated on independently observed ADFU identity and do not send
the reboot command or an execution payload as part of passive analysis.

## `dev-zetta/mechen-m30-community-firmware`

This repository provides the V1.292.00 release image, its SHA-256, and a
changelog with detailed hardware-acceptance claims. The repository contains
release artifacts rather than the patch source and test harnesses; its README
points to a separate research project, but GitHub does not identify it as a
fork. Treat the release hash as useful identity evidence and the hardware
results as reported test evidence, not as a substitute for reproducing the
checks against the source and our device.

One especially useful history item is the V1.202 sequence: successful build
checks and a visible 100% indexing progress bar did not guarantee a usable
`MUSIC.LIB`; later record/header comparison found an empty logical track
count. This is a good reminder to validate database consumers and contents,
not just the scan progress indicator.

## HiFi WALKER H2 firmware and ATJ2167 documentation

The H2 is a useful *workflow and feature reference*, but its current Rockbox
and stock-firmware projects are not M30 code references. The Rockbox build
targets `erosqnative`; the H2 platform is identified by its project as an
Ingenic X1000E Linux player, while the M30 analysis here targets Actions
ATJ2127 firmware. The H2-specific stock patch is also built for a particular
USB-C H2 revision and uses MIPS application/UBIFS patching. Its scrolling,
metadata fallback, folder track counts, and screen-off control fixes can
suggest test cases, but its functions, addresses, binary format, and drivers
cannot be carried over. See the [H2 Rockbox project](https://github.com/michoo/rockbox-h2)
and [H2 stock-firmware patch notes](https://www.reddit.com/r/DigitalAudioPlayer/comments/1we7ioe/hifi_walker_h2_custom_firmware_screen_off_controls/).

An ATJ2167 datasheet would be a closer hardware-family reference than H2
firmware, but it is still a different SoC from the M30's ATJ2127. The public
material located in this review was an ATJ2157 datasheet that includes a
high-level ATJ2167 comparison table (CPU/DSP, codec, display, storage, USB,
and interfaces), plus Actions' corporate filing grouping ATJ2167 with the
ATJ212X/ATJ215X portable-player family. This is enough to form peripheral
and architecture hypotheses; it is not a verified ATJ2127 register map or
programming manual. Treat shared blocks/registers as leads only, and promote
them to emulator facts only after matching the M30 binary's accesses or a
reproducible trace. A full ATJ2127 programming manual would have much higher
value for exact MMIO, interrupt, DMA, LCD, storage, and audio behavior.
References: [ATJ2157 datasheet with model comparison](https://www.digchip.com/datasheets/download_datasheet.php?id=1540586&part-number=ATJ2157&type=pn2),
[Actions Technology filing](https://static.sse.com.cn/stock/disclosure/announcement/c/202110/000833_20211019_I9Z3.pdf).

## RUIZU X02 ATJ2127 reverse-engineering reference

The [RUIZU X02 custom firmware kit](https://gitlab.com/reverse2682701/ruizu-x02-rev)
is the strongest related-device implementation lead found in this Reddit
review. The author reports reverse-engineering the Windows `RDiskUpdate`
flasher, extracting its decrypted SQLite firmware database in memory, building
MIPS16 patches with `mipsel-linux-gnu` binutils, identifying usable application
banks, and injecting a small LCD/UI patch. The public README describes a
firmware-database `FileTable` editing utility, RGB565 image converters, and
hooks that substitute a patched database into the updater. It explicitly says
the project adds code to the existing firmware rather than replacing the OS.
These are useful techniques to compare with our M30 patch/repack workflow.

The X02 evidence is a close architectural reference, not an M30 port. Its
Reddit post identifies MIPS32r2 plus MIPS16E; independent X02 teardown/model
references identify ATJ2127. This supports comparing instruction sequences,
toolchain behavior, and patch organization. It does not establish identical
memory-bank placement, driver ABI, board wiring, update protocol, or safe code
caves. The repository README still lists an LCD emulator as future work, so it
does not provide a ready-made full emulator. Its README also warns that its
flashing procedure can damage hardware; none of its updater or flash steps
were run for this review.

The [r/embedded ATJ2127 build discussion](https://www.reddit.com/r/embedded/comments/1h9ik3s/help_building_firmware_for_atj2127/)
adds an important limit: users report that the leaked US212A SDK source is
missing proprietary `SimKernel.lib`, `SimBase.lib`, and `comlib.lib`, blocking
the documented Visual C++ 6.0 full build. That makes a clean-room rebuild of
the complete vendor firmware an unproven route; the known M30 container and
binary-patching workflow remains the concrete path. The same thread links the
US212A application-design guide that is already indexed elsewhere in this
repository.

The targeted Reddit scan across `r/embedded` and `r/hardwarehacking` did not
turn up an M30 ROM dump, board-level debug trace, verified M30 ADFU session, or
working M30 custom OS. The surfaced `MECHEN_D50` and `ATJ2237` discussions are
different hardware and do not fill those gaps. For M30 behavior reports,
playlist and metadata complaints recur, but they are user reports rather than
binary evidence; see the dated community notes in
[`findings-rom-dump-availability.md`](findings-rom-dump-availability.md).

### How this changes the next steps

- Compare the X02 patch's MIPS16 encoding and app-bank patch layout against our
  existing M30 Ghidra segments; borrow only general mechanics after validating
  them against M30 bytes and free-space analysis.
- Do not spend effort trying to recreate the complete vendor SDK build until
  the missing libraries or a reproducible substitute are found.
- Treat the kit as a patching-method reference, not as a source of M30 addresses
  or permission to use its flasher.

## How to use these findings

- Cross-check technical claims against exact M30 binaries, call paths, and
  reproducible probes before adding them to `docs/knowledge/facts.jsonl`.
- Reuse the `m30sync` concept only as an offline copy-generation workflow;
  never rely on its preview or its destructive database cleanup as written.
- Keep Rockbox porting separate from the current patched-stock-firmware and
  hybrid-emulation work. Its source scaffold has a much larger boot and
  peripheral bring-up scope.
- Treat the USB response-tag behavior as a hypothesis to test against a
  captured protocol exchange. It does not justify issuing ADFU commands to
  the connected player.

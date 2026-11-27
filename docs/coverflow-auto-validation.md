# Automatic Cover Flow repair — 2026-11-27

The automatic path resolves ordinary M3U entries or indexed MUSIC.LIB ranks
using the already mounted artwork card context, then restores its saved folder
and file selector. It keeps three visible tiles and two preload
slots. Stock artwork decoding creates NEIGH.PIC, while ALBUM.PIC remains the
current cover. The side decoder requests 32 by 32 pixels; this change does not
increase the native center artwork resolution.

## Defects repaired

- The library helper was linked at 0x40294c00 but installed at 0x40295000.
  patch.py now checks each ELF text address and size against its installation.
- Median-cut sorting destroyed the spatial sample order. The encoder now
  reloads spatial samples after calculating the palette, without another 2 KiB
  image on the foreground stack.
- A block-local resolved location escaped its lifetime. Invalid library-result
  bits were ignored. Both paths now reject unavailable track locations.
- The sentinel index bound included sectors preceding the index table.
- The center input sector and output row overlapped LCD output. They now occupy
  separate scratch space between the output buffer and tile slots.
- Native row read errors lost the open handle. A separate failure flag keeps
  cleanup possible.
- The stock filename was misspelled ABLUM.PIC and its shared replacement was
  initially empty. The correct ALBUM.PIC string is initialized in key.drv's
  existing reserved resident area before the first artwork refresh.
- The decoder's third and fourth arguments are rectangles, not callbacks.
  Stock 0x48054720–0x4805473e reads four input halfwords; 0x48054876–0x48054884
  writes output dimensions. The old arguments 0x4015536c/0x40155374 intersect
  the patched repaint trampoline at 0x40155370. Stack-owned rectangles now
  replace those arguments, and failed decoding cannot reuse stale NEIGH.PIC.
- Foreground scene preservation now includes the metadata area up to the
  selector, not just its first 0x1a0 bytes. Playlist cache identity includes
  the complete stored path/cursor and disk, rather than two cursor bytes.
- Decode attempts consume a bounded budget even on lookup failure. Drawing
  checks that the active position, count and LCD buffer have not changed.
- Native shuffle prediction finds the current track inside the current epoch
  before predicting forward or backward. It does not reseed or mutate playback.
- The device showed three diagnostic marks, confirming that a second card mount
  failed before artwork loading. The loader now borrows the stock artwork
  context and restores its directory and selector after reading neighbors.

## Verification and limits

`build.sh --test --sd-theme --coverflow` checks stock bytes, free bank capacity,
ELF placement, repacked modules and container checksums. The packed QEMU test
runs the installed automatic loader, queue predictor, library resolver, cache,
quantizer, native center reader and renderer. It checks native/M3U dispatch,
five-cover preloading, asymmetric image quadrants, center pixels, source changes,
lookup/decode/read failures, invalid record IDs, shuffle history and cleanup.
The existing M3U harness separately executes the real ordinary-playlist resolver.

Stock metadata/JPEG services and the card/LCD services are fixtures in this
QEMU test. Hardware behavior, decoder resource usage and firmware bank loading
are not emulated. Passing these tests is not an on-device confirmation.

The earlier review's claim that sharing the 0x71 bank family necessarily evicts
and breaks its caller was too strong. These are tagged on-demand bank addresses
(see architecture.md), not ordinary flat physical addresses. The actual
link/install mismatch is checked and repaired; physical loader behavior still
requires hardware validation. This repair does not claim to fix settings.cfg
persistence or implement FullSound.

## Temporary on-device fallback marker

Set `FLOW_DIAG=1` during a Cover Flow build to mark the fallback at the
upper-left of the artwork area. Each magenta dot is one stage number:

1. Display scratch capacity or pointer is unusable.
2. Indexed queue location is unavailable.
3. Existing artwork card context could not be borrowed or snapshotted.
4. MUSIC.LIB could not be opened or sized.
5. The central tile could not be prepared (including failed lookup/decode).
6. The song or display buffer changed before drawing, or drawing failed.
7. Reserved for a later draw-specific result.
8. A neighboring visible tile was missing after loading.

No dots with one cover means the prepared-pack path, or a path that never
called the automatic loader. The marker is omitted from ordinary builds.
This diagnostic identifies a branch; it does not replace hardware verification.

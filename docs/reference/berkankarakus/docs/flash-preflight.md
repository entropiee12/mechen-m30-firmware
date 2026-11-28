# M30 firmware flash preflight

This checklist applies only to the MECHEN M30 `V1.101.10` / 2025-06-26
firmware. The repository currently has no flashable candidate artifact.

## Required files

Keep the stock rollback source outside the player:

| Purpose | SHA-256 |
|---|---|
| Stock `MECHEN_M30.HEX` | `6c7e27c8c90c4942664d66a0d6c9f9e48f8d3bc7d2d24223568255e233551258` |

Do not flash an extracted-member tree. A future candidate must first have a
full HEX hash, container/checksum round-trip verification, exact member delta,
and an explicit physical-test status recorded in
[`release-candidate.md`](release-candidate.md).

## Before flashing

- Confirm the player still reports `V1.101.10`.
- Charge the player fully and use a stable USB cable/connection.
- Keep the original stock file outside the player for rollback.
- Remove any older `MECHEN_M30.HEX` from the device root.
- Do not disconnect power or USB during the update.
- Do not proceed if the player is freezing, repeatedly rebooting, or showing a
  different firmware version.

## Official update path

The M30 manual instructs copying the HEX file to the device root, then using
`Settings -> Auto upgrade -> Yes`; the player should power off when the update
finishes. See the [firmware update instructions](https://manuals.plus/ae/1005006075793786#firmware-update).

## First boot checks

Do not rebuild the library immediately. Verify, in this order:

1. Power on and confirm the main menu opens.
2. Play one short MP3 and check play/pause, next/previous, and volume.
3. Turn the screen off and repeat next/previous and volume changes.
4. Check the headphone output at a low volume first.
5. Confirm the SD card contents are visible.
6. Only then test `Settings -> Update Playlist` manually.

If any check fails, stop using the modified firmware and preserve the exact
screen state/error message before attempting a rollback.

## Scope caveat

Host/Ghidra harnesses validate selected patch contracts, but they do not make
an untested candidate physically safe. No candidate should be described as
device-accepted without the checks above.

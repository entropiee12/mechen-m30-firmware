# Browser row-name to marquee bridge

## Executed real-code path

`tools/ghidra/run_browser_refresh_row_provider_probe.sh` executes the actual
`browser.ap` refresh function `FUN_40cd5218`, its row-display callback
`FUN_48714a20`, and its in-AP 20-byte name provider `FUN_48594510` in one
Ghidra p-code session. The wrapper accepts only the recorded stock V1.101.10
or community V1.292.00 `browser.ap` hashes. Reproduce it by passing a
hash-matching AP file:

```sh
bash tools/ghidra/run_browser_refresh_row_provider_probe.sh /path/to/browser.ap
```

Observed on the available stock-hash binary:

```text
BROWSER REFRESH ROW PROVIDER PASS steps=969 refresh-callbacks=7 provider-seeks=7 provider-reads=7 conversions=7 rows=0..6 last-seek=0x78 record-length=0x14 read-mode=0x11 name-slots=0x32 output=Folder0..Folder6
```

The seven refresh callback invocations use row indices `0..6`. Each enters the
real callback, seeks to `index * 0x14`, reads a 20-byte record with mode
`0x11`, and reaches the callback's converter with the existing `0x1c` bound
and the row's `0x32`-byte name slot. The probe checks that the converter's
synthetic `Folder0` through `Folder6` output was written to the expected slots
and NUL-terminated. The AP bytes are read-only; only callback pointers and
state in the Ghidra program's temporary emulated memory are redirected.

## What this does and does not connect

This closes the earlier gap between the refresh callback and the in-AP name
provider: they are one executable path, rather than two isolated probes. It
does not yet connect that result to the folder marquee state updater or the
LCD drawing callback. The filesystem read and text converter are still
synthetic service boundaries, so the injected record and output strings are
not evidence of the device's real directory names or conversion behavior.

The highest-value next bridge is to capture the framework/list paint callback
that consumes the populated row slot, then follow its active-string descriptor
into `drv_ui.drv`'s `FUN_30009070`/`FUN_30009674` path. The current extracted
browser AP has no direct call edge to the B3 marquee entry `0x260e4ff4`, and
the B16 renderer target remains outside the extracted driver. Until that
framework callback or runtime pointer is identified, the probe cannot explain
why some folder names fail to scroll or why a red rectangle appears.

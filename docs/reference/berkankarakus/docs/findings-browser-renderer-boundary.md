# Browser list-to-renderer bridge boundary

## Verified AP-side edges

The pinned community `browser.ap` (`10b0e5d8…fed34b3`) has this executed or
decompiled data path:

```text
FUN_40cd4e00
  -> FUN_48754574                 list setup; writes eight 0x32-byte name slots
     -> FUN_780db200               mode-indexed file/list operation dispatch
        -> 0x713125dc / 0x714525ac / 0x713925f4
  -> FUN_487546d8                 builds the list-state row descriptors
  -> FUN_40cd5218                 refresh loop
     -> FUN_48714a20               conditional 20-byte row-record formatter
     -> FUN_780db22c               fallback file-selection operation dispatch
        -> FUN_71352820             directory-row formatter for fsel type 0
```

The pointer literals and table entries are checked by
`tools/ghidra/run_browser_paint_dispatcher_boundary_probe.sh`. Ghidra
references identify `FUN_487546d8` as called from `FUN_40cd4e00`, and
`FUN_48714a20` as called from `FUN_40cd5218`. The latter callback's output is
already covered by the separate refresh-to-row-provider probe; this note does
not repeat that execution.

`FUN_487546d8` copies the 50-byte name-slot pointer into each 12-byte row
descriptor, sets each descriptor's length to `0x32`, and fills encoding,
selection, and list-state fields. `FUN_780db200` is not a paint callback: its
mode byte is at `0x9fc15734`, and its table at `0x780db5d4` points to the
directory, playlist, and third operation handlers above. The parallel
`FUN_780db22c` is also an operation dispatcher, selecting its handler from
`0x780db260` / table `0x780db25c`; it reaches `FUN_71352820` for the directory
operation.

## Why the bridge is still open

Neither the Ghidra-mapped blocks nor a raw byte scan of the entire hash-pinned
`browser.ap` file contains a direct pointer literal to the extracted `drv_ui`
B3 marquee entry `0x260e4ff5`, its B16-qualified target `0x26409401`, or the
A17 candidate `0x164490a1`. The file scan checks every byte offset, including
the portions outside Ghidra's initialized memory blocks. The list setup and row-state callbacks lead to
operation dispatch and descriptor preparation, not to the paint loop. The
generic listbox/framework painter and its runtime callback registration are
outside the extracted AP and driver code examined here. The AP-side emulation
cannot execute that missing implementation, so joining the AP row slot to B3
by manually copying descriptors would only prove an assumed ABI.

Consequently this trace does not explain folder marquee activation, clipping,
or the moving red rectangle. Existing B3 p-code runs still prove only B3's
behavior under injected descriptors, width results, and synthetic services;
they do not prove the browser invokes B3. A real bridge needs either the
framework/list painter implementation or a runtime callback trace that shows
the row descriptor being consumed and the driver call being made.

No firmware, SD card, or device data was modified. The AP member is read-only;
all Ghidra work uses disposable host projects and synthetic emulator RAM.

# M30 EQ preset trace and safe validation

Read-only firmware analysis. The probes below execute extracted code in
Ghidra's p-code emulator with synthetic RAM and explicit service stubs. They
do not patch or produce a flashable image, and they do not prove audible DSP
or codec behavior.

## Proven selection and coefficient path

1. **UI producer (`music.ap`).** Built-in wrappers around
   `FUN_403d4e20` emit `{type, selector}`: Off is `{0,0}`, built-ins are
   `{7,1..6}`, and `FUN_403d4ed0` is the Custom producer. It copies seven bytes
   from the pointer literal at VA `0x403d4ef8` (file offset `0x86f8`) and then
   calls `FUN_403d4e20(7,7)`. `FUN_40614f08` sends message `0x303`.
   `run_eq_producer_probe.sh` verifies this static path; it does not execute
   the UI scene.
2. **EQ editor implementation (`mainmenu.ap`).** Community `mainmenu.ap`
   (`1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105`)
   contains selector `FUN_40154cec` and Custom editor `FUN_40154f44`.
   The editor loads a working copy of the seven gains from persistent block
   `0x9fc155f4 + 0x2c`; an edit copies the data back and invokes
   `FUN_40154c78(7)`. That producer copies seven bytes to `0x9fc1a19c` and
   sends `{7,7}` through message `0x303` when its readiness check succeeds.
   A Ghidra p-code probe confirmed the increment path and message under
   synthetic event/service stubs. A later sweep injected decrement event
   `0x2904` at gains `-3..+3`. Raw MIPS16 bytes show a signed extended `SLTI`
   comparing against `-2`, followed by a one-step decrement; this implements
   a `-3` floor. Ghidra p-code treated that immediate as unsigned and produced
   the erroneous `+1 → -3`; an isolated condition-corrected run produced
   `-3,-3,-2,-1,0,1,2`. The event-decoder ABI is inferred, so the physical
   decrement-key mapping remains untested. On state-1 selection event
   `0x2c03`, resident
   `FUN_bfc165e0` returns `0x8505` when the selected menu code is 3; this
   enters state 3, `FUN_40154cec`, the EQ selector. State 4 is
   `FUN_40154f44`, the Custom editor. Config item `0xe2` gives the default
   order `0,4,5,2,3,1`, placing code 3 fifth; its localized label remains
   unknown. `FUN_40014fac` writes the mainmenu settings block only when the AP
   exits to state 0. Confirm/cancel does not itself persist; cancel restores
   RAM but sends no compensating producer update, so stale engine preview is
   possible. The earlier `music.ap` row `0x3301` is a volume-limit setting,
   not EQ Custom.
3. **Message receiver and builder (`mengine.ap`).** `FUN_60090d64`
   dispatches `0x303` through pool word `0x60091014` to `FUN_601d0f30`.
   That handler calls the MIPS16 entry `0x601d0d59` of
   `FUN_601d0d58` via pool word `0x601d0f74`. The builder reads the selector
   at `0x9fc15c88 + 0x41`; selector 7 selects the seven Custom bytes at
   `0x9fc1a19c`. For each of seven bands it sign-extends the int8 gain,
   multiplies it by 10, and writes an s16 at structure offset
   `0x10 + 8*i`, alongside frequency, Q, enable, and reserved fields.
   Interpreting the scaled value as tenths of a dB is not proven.
4. **Next service boundary.** The builder submits the `0xf0`-byte structure
   at `0x9fc15dd4` through module `0x10`, then obtains shared memory via module
   `0x0c` and sends module `0x42`, command `0x1f`. The actual fixed-ROM
   recipient and its DSP meaning are unknown.
5. **Candidate audio-device consumer.** A statically identified `aud_dev.drv`
   bank-5 routine at candidate MIPS16 address `0x9400` reads 16 eight-byte
   records and issues 16 module-`0x10` copy-shaped calls. Its bank mapping,
   module service semantics, and relation to live playback are not established.
   The p-code probe supplies a synthetic memcpy implementation; verified
   copies are not proof of DSP execution or sound output. No WM8965 register
   application has been tied to this EQ message path.

## Why Custom may have sounded like Off

The fixed candidate curve `(6,5,0,-2,0,4,3)` is not band-identical to Off
if it reaches the builder: the builder writes scaled band gains
`(60,50,0,-20,0,40,30)`, while Off writes seven zeros. So “the custom values
are too small” is not supported by the known builder inputs. The scale's
physical unit and acoustic response are still unknown. A later reported
`aud_dev.drv` decompile says nonzero selectors also enable a separate output-
gain group, so even zero-valued Custom is not proven identical to Off at the
consumer. That group and its audible effect have not been independently
verified; see [aud_dev findings](findings-auddev-driver.md).

There are stronger unresolved explanations for the near-identical listening
result:

- The tested device's exact `music.ap` was not identified. Its producer pointer
  at file offset `0x86f8` was not read back, so it is unknown whether the fixed
  candidate curve was active.
- The state-1 selection path into EQ is established: menu code 3 returns
  `0x8505`. Its localized label and relationship to the separate
  `setting.ap` EQ list remain unresolved. The decoded `music.ap` `0x3301`
  callback is for volume limit and is unrelated.
- The current producer p-code trace starts at `FUN_403d4ed0`; it proves that
  function's copy/send behavior, not the physical UI caller path.
- The builder-to-audio-device path stops at synthetic service shims and a
  candidate driver-bank mapping. The actual DSP/codec consumer is not proven.
- The provided photo showed all seven Custom sliders at 0 dB. That is
  compatible with the fixed-curve pointer redirect because the screen and
  producer use separate data paths; it is not evidence that the fixed curve
  was or was not sent.

## Offline coefficient check added here

`RunEqBuilderProbe.java` now runs the exact experimental curve through the
real `FUN_601d0d58` instructions, alongside Off and the existing Hip-hop row.
The non-flashable output is emitted as `custom-candidate.bin`. The
`run_eq_auddev_chain_probe.sh` also sends that structure through the candidate
bank routine under the explicit synthetic memcpy shim.

Expected active band gains after the verified `×10` transform:

| Selection | Seven builder gain fields |
| --- | --- |
| Off | `0, 0, 0, 0, 0, 0, 0` |
| Hip-hop | `0, 0, 10, 20, 0, 10, 10` |
| Custom candidate | `60, 50, 0, -20, 0, 40, 30` |

Run against extracted, hash-matching inputs:

```sh
bash tools/ghidra/run_eq_builder_probe.sh \
  "$M30_COMMUNITY_FW/mengine.ap" "$M30_STOCK_FW/mengine.ap"
bash tools/ghidra/run_eq_auddev_chain_probe.sh \
  "$M30_COMMUNITY_FW/mengine.ap" "$M30_STOCK_FW/mengine.ap" \
  "$M30_COMMUNITY_FW/aud_dev.drv"
```

The second command checks record transfer, not live sound. Extracted firmware
corpora are local analysis inputs and are not included in a clean Git checkout.

## Minimal safe validation plan

1. **No device writes:** run the two commands above. Confirm the Custom
   candidate output has the expected seven distinct gains and that Off stays
   zero. The stock/community builder digest should match.
2. **Identify the tested firmware read-only:** from a local extracted copy of
   the exact firmware used on-device, inspect `music.ap` at `0x86f8`. Stock
   source is `20 57 c1 9f` (`0x9fc15720`); the experimental redirect is
   `00 4f 3d 40` (`0x403d4f00`), with curve bytes at file `0x8700`. Do not
   infer candidate activation from the EQ screen or filename alone.
3. **Only if the candidate is already active:** at low volume, use the same
   track segment, headphones, and volume for Off → Custom → Off. Separately
   compare a built-in preset with a visibly non-flat known table (for example
   Techno) against Off as a control. Record the on-screen selection and
   whether the sound returns to baseline on the second Off pass. This is a
   listening discriminator, not a coefficient or codec proof; no new flash is
   required for this step.
4. **Next reverse-engineering target:** resolve the localized label for
   state-1 menu code 3 and the style-label mismatch, then resolve module
   `0x42` command `0x1f` and the real module-`0x10` consumer. AP-exit
   persistence is identified statically; its power-loss behavior and device
   execution remain untested.

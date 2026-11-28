# M30 firmware-version corpus comparison

This is a read-only comparison of the install images available in the local
community-firmware archive. Each image was decrypted and extracted with the
Rockbox `atjboottool` before the comparison. The temporary extraction root is
not part of this repository.

The reproducible public download URLs, dated Drive folder IDs, and verified
HEX hashes are indexed in [`corpus-downloads.md`](corpus-downloads.md).

## Corpus

The tested versions are:

```text
V1.101.11  V1.101.12  V1.200.00  V1.200.01  V1.201.00  V1.202.00
V1.202.01  V1.202.02  V1.202.03  V1.203.01  V1.287.00  V1.292.00
```

The extracted `drv_ui.drv` SHA-256 is identical for all twelve versions:

```text
011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a
```

The extracted `kernel.drv` SHA-256 is also identical for all twelve:

```text
58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6
```

The fixed AFI-side support members are identical as well. Their common
SHA-256 values in the same corpus are:

```text
CARDMBRC.BIN  f63a2ccd5fcf14e8329cc1c52faac75145733f331c8aede5872499ffe5f3c1b6
CARDPRDU.BIN  27f50ff41cbe45334334dd171bae30d5a566c70f2c59b932e5c3df39d539b1b5
NORBREC.BIN   8b11f25209e6a79474559b4b48d3a9d7f29d5f07633d234fe0eada9b8cd4e93d
NORFWSC.BIN   5b72dee96b608139baea2a8d187e8c8bc4644600c0c085382093f9f2a95fb920
NORHWSC.BIN   249df3875d5d25b070feba2941cbf8cfb8dde67e19c4a22d7a0735be1de49ecb
NORMBREC.BIN  dacb549733c6af1727ed2dd8efe091ff6386ef3a111329f5407475ee620c5ace
NOR_ID.BIN    5d70d9c4e90de212a542c416adddeba811ca439c83f9e4883103330035fa635d
```

This does not dump the mask ROM; it only shows that the update images do not
carry a version-specific NOR-side implementation of the missing UI provider.

The application overlays do change between release lines, as expected; this
comparison therefore does not use an application hash as evidence about the
resident UI provider.

## Independent 2023 firmware check

An additional public M30 update was downloaded from the linked Drive folder:

```text
MECHEN_M30.HEX
source: https://drive.google.com/drive/folders/1VuQF4jk-yfDUBSTfQorGSsXtDUdvYcEW
sha256: ee164011fb1fe30f552348613f832c5cb0f2aee271834f891aad22be6674e6da
release date in AFI: 20/02/2023
```

Rockbox `atjboottool` decrypted the HEX, unpacked the AFI, and unpacked
`FWIMAGE.FW` successfully. The AFI and all 99 extracted firmware members
passed their embedded checksums. The extracted firmware was kept outside the
repository; no binary was added to Git.

The following stable members are byte-identical to the existing stock and
community corpus:

```text
kernel.drv  58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6
card.drv    fc355c17258fe6fca184e5a616ec4c0b151a566caa99feba9b14981a046063bf
cards.drv   3c7fb89cf2e8e2f8bdb2311b386e7047f17c35b5846e71139a105255b88462c1
fat_32n.drv bed63d62aff4df62254b758050abd4ee72f3a829dc39ba374485e64875c75b6b
exfatn.drv  6c6b724a86f371cf4cbca7ab5f1b0c970d27ecebd502d3c7edb1e5e895d3c0b5
```

The existing host probes all passed against this independent extraction:
key, LCD, DAC, audio-device, USB-host, USB-audio, SD-card, filesystem,
configuration, UI/EQ, and library-mask probes. The provider comparison also
passed with zero observed provider occurrences outside the known `drv_ui.drv`
consumer. A full scan still finds the same six pointer values inside that
consumer, which is expected and does not identify a new provider.

The 2023 overlay hashes differ from the existing application overlays, while
the stable driver hashes do not. This makes the new corpus useful as a
cross-version behavioral confirmation, but not as evidence that the missing
resident UI provider exists in flash.

## Complete public Drive inventory

The dated M30 folders under the public [M30 Drive root](https://drive.google.com/drive/folders/1Y9v8c6FLR5lbxLY-PhAv-BQOeQa2BNev) contain twelve HEX images. Their downloaded SHA-256
values and the accompanying modification notes are recorded here so that a
future analysis does not need to rediscover the folder contents:

| Date | HEX SHA-256 | Modification note or filename hint |
|---|---|---|
| 2022-08-05 | `ee47440fdc5fbcb8bef85119d4ce39108ad92b961bb100cfa3c785bf858c14ac` | Title-based sorting; lock-screen playback/volume controls |
| 2022-08-16 | `aa07468df20f8f0ca3116c1e0bb1305b1ab6eecbfdf32bb7ef70c7dcde0b2a6e` | Disable scroll wheel and buttons in lock screen |
| 2022-09-14 | `6aa4e532307b5147d3da982439e0bfb82aca051853c3c911668fc651d909a640` | Wheel direction: next/previous track |
| 2022-11-17 | `aa54f80afb13bb6d6c405854643a2d06f8ef768b5b958d3aac9f711a65224e72` | Add volume-limit function |
| 2022-12-16 | `0e56c50e9720da7e95910582fbd1c293f77c1f1346c755a02ca2e08b5753184e` | Fix boot volume zero bug |
| 2023-02-20 | `ee164011fb1fe30f552348613f832c5cb0f2aee271834f891aad22be6674e6da` | Make sleep timer persistent |
| 2023-07-26 | `07a8917d0d71c11894f9ecbb394b7013e04f2513d0ad5181ad9a3cb5e49c9bd5` | Any key activates the device |
| 2024-02-28 | `ac761a406b99366804c64df3d353fb73f11d91e4bea386202c22a4cd70d33739` | Lock-screen playback/volume controls |
| 2024-03-19 | `6103567b8ef71b635ad7ff1715070982e4135bb0fe1864d8e1d7b95aa5463752` | English and Russian versions integrated |
| 2024-04-24 | `8355edd60c4b964ef85d5510ed634cc50591b35f6a8e95f95d4a40b09ef99289` | Repeat-1 stop mode |
| 2025-02-17 | `7ec4f3e1f528d7368a27a18db4fb51253823cf42a9e1760f4381b4233eb9974e` | `.fw` filename says main-screen rotation and lock-screen buttons ineffective; no note file found |
| 2025-06-26 | `6c7e27c8c90c4942664d66a0d6c9f9e48f8d3bc7d2d24223568255e233551258` | Fix custom-EQ reboot volume-off issue |

The 2022-11 note is named `modefications.txt` in the source folder and the
2023-02 note is singular `Modification.txt`; both were included. The 2025-02
behavior is preserved from the descriptive `.fw` filename because that folder
has no separate note file.

All twelve images decrypt and unpack successfully. Eleven extracted
`FWIMAGE.FW` images contain 97 members; 2025-06 contains 99 because it adds
the `1257l.tbl` and `v1257.tbl` language-table members. The kernel and the
storage/audio boundary drivers remain byte-identical across the full set.

## Modification correlation results

Adjacent-image byte diffs localize the documented changes without treating
every changed byte as a new contract:

- 2022-08-16 to 2022-09-14 has four repeated one-byte changes in each AP's
  corresponding table area, consistent with the wheel-direction note.
- 2022-09-14 to 2022-11-17 adds one `setting.mcg` node (70 to 71); the new
  node has ID `182`, while the accompanying note says "Add volume limit
  function". Node `182` remains present in every later extracted release. The
  host model therefore implements only the evidenced UI-level 0..100 clamp and
  does not infer DAC gain or persistence semantics.
- The same 2022-09-14 to 2022-11-17 transition changes five `setting.ap`
  banks: `0x40214c00` (3 bytes), `0x402d4c00` (3 bytes), `0x48a54600`
  (1252 bytes), `0x48a94600` (496 bytes), and `0x48bd4600` (1229 bytes).
  Ghidra shows the changed `FUN_48a94798` at `0x48a94600` changing from a
  two-byte volume conversion helper to a pointer-based control helper that
  initializes a local upper bound of `100` and clamps `*param_1` to `*param_2`.
  This strengthens the UI/control-level volume-limit contract while leaving
  the downstream DAC scale unresolved.
- A twelve-release scan of the same function window (`0x48a94798` plus
  `0x100` bytes) shows three phases: the first three releases share legacy
  conversion-window SHA-256 prefix `f0b343d14ed3c101`; 2022-11-17 has the
  new clamp-window prefix `281d194e656c34e1`; and 2022-12-16 through
  2025-06-26 share `7521b6811f9dc424`. The only 2022-11-17 → 2022-12-16
  byte inside that window is virtual address `0x48a9486e`, consistent with
  the separate boot-volume-zero fix. The 100-level clamp is therefore not a
  one-release anomaly.
- 2022-12-16 to 2023-02-20 changes only `config.ap` among the extracted
  members, consistent with the sleep-timer note. All 338 byte differences are
  inside the `config.ap` bank at file `0x1400` / virtual `0x40054c00`; the
  changed virtual span is `0x40054f98..0x40055383`. This is the next focused
  decompile boundary for persistence semantics. The host model records only
  the verified distinction between a one-shot and reboot-persistent selected
  value; it intentionally leaves timer units, selectable values, countdown,
  and storage layout unresolved.
- Ghidra 12.2 decompilation of that boundary identifies the handler
  `FUN_40054f84` in both releases. After normalizing address shifts, its
  control flow is the same except that the 2022-12-16 handler clears one
  state byte (`state + 0x0b`) before writing the setting records; the
  2023-02-20 handler no longer performs that clear. This is direct binary
  evidence for the one-shot-to-persistent setting change, but it does not
  identify the timer unit or countdown implementation. The single stored
  decompile artifact has SHA-256
  `a7fe6a37b3f37dfd09fefafd0bcd7e9127e5cfdc1af2683241ea860ac21e0368`.
  The reusable `tools/emu/sleep_timer_delta_probe.py` checks the 338-byte
  file span and its mapped virtual range without requiring Ghidra.
  The stored volume-handler decompile artifact has SHA-256
  `fa9d1666587a2827a191a2285430c9ddad1ef59133c85f4a39cccf379f0ef177`.
- 2024-02-28 to 2024-03-19 changes `drv_ui.drv` and `setting.mcg` in addition
  to the application overlays, matching the language-integration note.
- 2024-04-24 has a unique first EQ frequency of 50 Hz in `mengine.ap`; all
  other ten checked releases use 80 Hz and the 2025 releases return to 80 Hz.
  This is recorded as a version-specific observation, not a global emulator
  rule.

The 17 existing binary probes passed for 11 of 12 releases (203/204 probe
runs). The only failure is the intentional 2024-04-24 EQ-frequency mismatch
above. This is evidence that the host contracts are broadly cross-version
stable while still exposing isolated release-specific exceptions.

The adjacent member-delta matrix is:

| Transition | Changed member scope | Note correlation |
|---|---|---|
| 2022-08-05 → 2022-08-16 | Nine application overlays: browser/config/mainmenu/music/playlist/setting/udisk/upgrade/usound | Lock-screen wheel and button policy |
| 2022-08-16 → 2022-09-14 | The same nine application overlays, with four repeated byte changes per overlay | Wheel direction |
| 2022-09-14 → 2022-11-17 | Seven application overlays plus `common.sty` and `setting.mcg` | Volume-limit feature; node `182` appears |
| 2022-11-17 → 2022-12-16 | Seven application overlays | Boot-volume-zero fix |
| 2022-12-16 → 2023-02-20 | `config.ap` only | Persistent sleep-timer change |
| 2023-02-20 → 2023-07-26 | Nine application overlays plus `mengine.ap` | Any-key activation |
| 2023-07-26 → 2024-02-28 | Nine application overlays | Lock-screen controls |
| 2024-02-28 → 2024-03-19 | Nine application overlays, `drv_ui.drv`, and `setting.mcg` | English/Russian integration |
| 2024-03-19 → 2024-04-24 | Application overlays plus `mainmenu.sty`, `music.mcg`, `music.sty`, `setting.mcg`, and `setting.sty` | Repeat-1 stop mode and resource updates |
| 2024-04-24 → 2025-02-17 | Application overlays plus `mainmenu.sty`, `music.mcg`, `music.sty`, `setting.mcg`, and `setting.sty` | Filename-described UI/lock-screen changes |
| 2025-02-17 → 2025-06-26 | Application overlays, `drv_ui.drv`, UI resources, and new `1257l.tbl`/`v1257.tbl` members | Custom-EQ reboot-volume fix |

The matrix is intentionally a member-level result, not a claim that every
changed byte implements the corresponding feature. It identifies the next
decompile/probe boundary and keeps unrelated resource churn out of the host
contract.

## Provider result

The observed UI pointer set was scanned in every extracted `fw/` directory,
excluding the known consumer `drv_ui.drv`:

```text
0x164490a1  A17 + 0x0a1
0x164c9001  A19 + 0x001
0x164c90ed  A19 + 0x0ed
0x26089471  B02 + 0x471
0x260894e9  B02 + 0x4e9
0x26409401  B16 + 0x401
```

All six counts are zero in all twelve versions. The same values do appear in
the outer `FWIMAGE.FW` archive, but those are container copies of the member
bytes, not an additional executable provider. The provider scan must therefore
operate on the extracted member directory, not on the archive container.

The comparison is reproducible with:

```sh
PYTHONPATH=tools/emu python3 tools/emu/compare_firmware_versions.py \
  /path/to/extracted-version-root --require-zero
```

## Consequence

Searching another archived M30 release is unlikely to reveal the missing A17,
A19, B2, or B16 implementation: the resident driver, UI driver, and fixed
AFI-side support members are stable across the available corpus, and no
extracted member provides a second copy. The remaining evidence must come from
the mask-ROM/resident dispatch boundary or from a runtime observation. This
keeps the unknown provider out of the flashable patch and out of the host
emulator's executable dispatch path.

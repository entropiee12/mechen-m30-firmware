# M30 corpus download manifest

This is the reproducible download index for the firmware corpus used by the
read-only comparisons in [`findings-version-corpus.md`](findings-version-corpus.md).
The files are not stored in Git. A new analysis should download the original
`MECHEN_M30.HEX` from the dated public Drive folder, save it under a unique
date-named path, and verify the SHA-256 before decrypting or extracting it.

## Canonical sources

| Source | URL | Contents |
|---|---|---|
| M30 firmware root | <https://drive.google.com/drive/folders/1Y9v8c6FLR5lbxLY-PhAv-BQOeQa2BNev> | Dated firmware folders listed below |
| 2025-06-26 direct HEX | <https://drive.google.com/file/d/1lTyyP05toQHbcnmmiK3R9FFToj9RZfxC/view> | `MECHEN_M30.HEX`, stock V1.101.10 |
| Independent 2023-02-20 folder | <https://drive.google.com/drive/folders/1VuQF4jk-yfDUBSTfQorGSsXtDUdvYcEW> | `MECHEN_M30.HEX` and `Modification.txt` |
| Independent `MUSIC.LIB` sample | <https://raw.githubusercontent.com/xleph/MP3.LIB/main/MUSIC.LIB> | Public comparison library; not proven to be M30-generated |
| Independent `M3U.LIB` sample | <https://raw.githubusercontent.com/xleph/MP3.LIB/main/M3U.LIB> | Public comparison file; 1,485,312 bytes; payload record meaning not inferred |
| Independent disk-location signatures | <https://github.com/xleph/MP3.LIB/blob/main/literalLocations> | 302 packed 8-byte signatures, SHA-256 `63e909421a6e32fe545ae4ddd965e720e6eba8de3ab6028512b22c9c5b15627e`; tested as described in [`findings-device-sd-library.md`](findings-device-sd-library.md), no exact matches |
| US212A application-design manual | <https://tchumim.com/assets/uploads/files/1622479367338-%D7%9E%D7%93%D7%A8%D7%99%D7%9A-%D7%9C%D7%AA%D7%9B%D7%A0%D7%95%D7%9F-%D7%90%D7%A4%D7%9C%D7%99%D7%A7%D7%A6%D7%99%D7%95%D7%AA-us212a.pdf> | Publicly mirrored SDK reference for `plist_f_info_t`, LIB section headers, and M3U list construction |
| Community firmware source | <https://github.com/dev-zetta/mechen-m30-community-firmware> | V1.292.00 source/release corpus |
| Actions unpacker | <https://github.com/Rockbox/rockbox/tree/master/utils/atj2137/atjboottool> | `atjboottool` build source |

The Drive root exposes the dated folders. The table records each folder URL,
so another analyst can navigate to `MECHEN_M30.HEX` even when Google changes
the individual file URL. Only the 2025-06-26 individual file ID is preserved
in the current evidence; no direct file ID is guessed for the other releases.

## Dated HEX files

Every dated folder contains a file named `MECHEN_M30.HEX`. Download that file,
then compare its complete SHA-256 with this table before using it. The notes
column is the accompanying modification description used by the analysis.

| Date | Folder URL | SHA-256 | Modification note |
|---|---|---|---|
| 2022-08-05 | <https://drive.google.com/drive/folders/1tckT1luFSrfXn5H1iCRvVwfNqC_iY4py> | `ee47440fdc5fbcb8bef85119d4ce39108ad92b961bb100cfa3c785bf858c14ac` | Title sorting; lock-screen playback/volume controls |
| 2022-08-16 | <https://drive.google.com/drive/folders/17wbTH105rnSaTFOeza2ih8Iwz7lWJlGt> | `aa07468df20f8f0ca3116c1e0bb1305b1ab6eecbfdf32bb7ef70c7dcde0b2a6e` | Disable wheel/buttons in lock screen |
| 2022-09-14 | <https://drive.google.com/drive/folders/1E9enHNePSCDB-HOVBNnyqfXaobRGHvh8> | `6aa4e532307b5147d3da982439e0bfb82aca051853c3c911668fc651d909a640` | Wheel direction: next/previous track |
| 2022-11-17 | <https://drive.google.com/drive/folders/19CJ4FG10OQh6FbZ-BO98R36RgTYR1PPR> | `aa54f80afb13bb6d6c405854643a2d06f8ef768b5b958d3aac9f711a65224e72` | Add volume-limit function; `modefications.txt` |
| 2022-12-16 | <https://drive.google.com/drive/folders/1bEdfi4fHNQzieijt1CjNMzLVjsh098zx> | `0e56c50e9720da7e95910582fbd1c293f77c1f1346c755a02ca2e08b5753184e` | Fix boot volume zero bug |
| 2023-02-20 | <https://drive.google.com/drive/folders/1VuQF4jk-yfDUBSTfQorGSsXtDUdvYcEW> | `ee164011fb1fe30f552348613f832c5cb0f2aee271834f891aad22be6674e6da` | Make sleep timer persistent; `Modification.txt` |
| 2023-07-26 | <https://drive.google.com/drive/folders/1Wk7uw9FxE-jVf4qIY12Fx-Agrq0enZRv> | `07a8917d0d71c11894f9ecbb394b7013e04f2513d0ad5181ad9a3cb5e49c9bd5` | Any key activates the device |
| 2024-02-28 | <https://drive.google.com/drive/folders/1T22Xc9buWp-bJLH_CUn298A-vMbfNBeP> | `ac761a406b99366804c64df3d353fb73f11d91e4bea386202c22a4cd70d33739` | Lock-screen playback/volume controls |
| 2024-03-19 | <https://drive.google.com/drive/folders/1l6uaXoZoD3yNJuk7wfJe8_9y8XB5ELIH> | `6103567b8ef71b635ad7ff1715070982e4135bb0fe1864d8e1d7b95aa5463752` | English and Russian versions integrated |
| 2024-04-24 | <https://drive.google.com/drive/folders/1wH7JtI31Yx1eoCgixPHHTq_CqwIMAeyj> | `8355edd60c4b964ef85d5510ed634cc50591b35f6a8e95f95d4a40b09ef99289` | Repeat-1 stop mode |
| 2025-02-17 | <https://drive.google.com/drive/folders/1KaSNsT-HOtyisZLx97XC4fzQW35ZJmyd> | `7ec4f3e1f528d7368a27a18db4fb51253823cf42a9e1760f4381b4233eb9974e` | `.fw` filename describes main-screen rotation/lock-screen buttons |
| 2025-06-26 | <https://drive.google.com/drive/folders/1cHhh5JISi_dwgFxokZb4wETnC6Tx7Ueh> | `6c7e27c8c90c4942664d66a0d6c9f9e48f8d3bc7d2d24223568255e233551258` | Fix custom-EQ reboot volume-off issue |

## Download and verify

The only stable command-line URL currently recorded is the direct 2025-06-26
file URL:

```sh
curl -L -o MECHEN_M30.HEX \
  'https://drive.google.com/uc?export=download&id=1lTyyP05toQHbcnmmiK3R9FFToj9RZfxC'
shasum -a 256 MECHEN_M30.HEX
```

For another date, open its folder URL, download the file named
`MECHEN_M30.HEX`, and verify the matching row above. Do not rename different
releases to one shared path before hashing; the date is part of the provenance.

After verification, the expected offline layout is:

```text
m30-inputs/
  stock/2025-06-26/MECHEN_M30.HEX
  drive/2022-08-05/MECHEN_M30.HEX
  drive/2022-08-16/MECHEN_M30.HEX
  ...
  drive/2025-06-26/MECHEN_M30.HEX
```

The unpacked layout used by the probes is one directory per date with
`afi/`, `fw/`, and the original extraction logs. The repository's static
suite never downloads files and never writes a flashable HEX; it consumes
already verified files from outside Git.

## Independent MUSIC.LIB sample

The raw comparison file at
<https://raw.githubusercontent.com/xleph/MP3.LIB/main/MUSIC.LIB> was
downloaded and stored outside the repository at:

```text
<persistent-evidence-root>/MUSIC.LIB
```

Its verified SHA-256 is
`cc3f476c8edb50ea8493b726d46ac82b139a6e87a86cc04c2cdbc30fd3fb8afe`.
It is 1,536,000 bytes and contains 251 records. It is a layout/comparison
sample only, not an M30 device dump; keep that provenance label when using it.
Verify it through the same SD and index interpretation path with:

```sh
PYTHONPATH=tools/sim:tools/emu \
  python3 tools/emu/musiclib_evidence_probe.py \
  <persistent-evidence-root>/MUSIC.LIB
```

For a newly supplied device image, pass its expected hash as the second
argument, or use `-` only when the hash is being established for the first
time:

```sh
PYTHONPATH=tools/sim:tools/emu \
  python3 tools/emu/musiclib_evidence_probe.py \
  <persistent-evidence-root>/device-sd/MUSIC.LIB \
  e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88
```

The same supplied SD card also contained `M3U.LIB` (1,550,848 bytes,
SHA-256 `ad4deb5b599eb97309e49a1d907cab445e3785d04f0ee103e6126f8ab0703a01`)
and `ALBUM.PIC` (128,000 bytes, SHA-256
`65bad6f0a143f3990f1d0e958699513159dc3b06f7bcec0452ee318217c8e671`). Their
read-only findings are recorded in
[`findings-device-sd-library.md`](findings-device-sd-library.md).

The public comparison `M3U.LIB` was also downloaded; its SHA-256 is
`d40259567735f11f5969fa0e56e9ff7f05340d7da706caa4271cbd900111ab71`.
Both files have the same 512-byte `M3U`/`ALLFILE` header family. The device
file's remaining bytes are a stale MP3 stream, so raw payload block comparison
does not reveal playlist record semantics. The public sample's payload has not
been established as a valid M30 playlist-record example either.

The US212A manual was downloaded outside Git into the persistent evidence
root. Its SHA-256 is
`c6795e5aaef95af5b9bd16d469086e4f69603db87409791f039315705d7d0b05`.

## Interpretation boundary

The HEX is an Actions FWU v3 container. The analysis flow is:

1. Verify the complete HEX hash from this manifest.
2. Decrypt/unpack with `atjboottool` and retain the AFI/FWIMAGE checksum logs.
3. Hash each extracted member before comparing releases.
4. Treat changed members as evidence only after correlating them with the
   dated modification note and, where needed, a Ghidra decompile.
5. Feed only the confirmed byte boundary into a host model; keep unresolved
   bytes as raw/opaque data.

For the current index work, the important extracted member is `MUSIC.LIB`
from a player-created card image, not a generic public sample. The emulator
therefore models the confirmed `0x200` header, 256-byte records, section
directories, 512-byte sector references, raw order/inverse/node-index tables,
and decoded 16-byte nodes. The reader's eight-byte location-key lookup is also
modeled; the primary location blob, record links, and M30-specific UI grouping
semantics remain unresolved.

## 2026-11-16 download audit

The dated public folders were fetched read-only into the external evidence
path `/Users/berkankarakus/Projects/m30-inputs/hex/`. The following hashes were
calculated locally after download. Eleven files were found from the folder
listing, and the previously unavailable 2022-11-17 folder was recovered via
an alternate Drive URL. The files are not copied into Git and are not flashed.

| Date | Downloaded SHA-256 | Manifest status |
|---|---|---|
| 2022-08-05 | `1bccada9c943cf29495968a9f58c7c5fb949e47f1d7bb5a4134dc8c7f1803315` | differs from recorded hash; unresolved |
| 2022-08-16 | `43ff251f90a5d80cf6fb8269d51be859d4c29a39a21ec7e19ddd32b542eb7b49` | differs from recorded hash; unresolved |
| 2022-09-14 | `6aa4e532307b5147d3da982439e0bfb82aca051853c3c911668fc651d909a640` | matches |
| 2022-11-17 | `aa54f80afb13bb6d6c405854643a2d06f8ef768b5b958d3aac9f711a65224e72` | matches |
| 2022-12-16 | `5191d95f878f13cd480df3a07735235715ad52efe421fdd07eeb4a1965797e1c` | differs from recorded hash; unresolved |
| 2023-02-20 | `ee164011fb1fe30f552348613f832c5cb0f2aee271834f891aad22be6674e6da` | matches |
| 2023-07-26 | `07a8917d0d71c11894f9ecbb394b7013e04f2513d0ad5181ad9a3cb5e49c9bd5` | matches |
| 2024-02-28 | `ac761a406b99366804c64df3d353fb73f11d91e4bea386202c22a4cd70d33739` | matches |
| 2024-03-19 | `6103567b8ef71b635ad7ff1715070982e4135bb0fe1864d8e1d7b95aa5463752` | matches |
| 2024-04-24 | `8355edd60c4b964ef85d5510ed634cc50591b35f6a8e95f95d4a40b09ef99289` | matches |
| 2025-02-17 | `7ec4f3e1f528d7368a27a18db4fb51253823cf42a9e1760f4381b4233eb9974e` | matches |
| 2025-06-26 | `6c7e27c8c90c4942664d66a0d6c9f9e48f8d3bc7d2d24223568255e233551258` | matches |

The three differing 2022 hashes are preserved as unresolved provenance
discrepancies, not silently substituted into the manifest. They must not be
used as cross-version evidence until their source/version relationship is
resolved. `atjboottool` extraction of these newly downloaded HEX files is a
separate step; the existing six extracted corpora remain the authoritative
inputs for the current static suite.

# M30 SD themes

The experimental `--sd-theme` build themes the Now Playing track icon. Settings → Theme discovers theme folders on the card. Choose Next theme to cycle through folder names alphabetically, then select the displayed name to apply it. Stock is always available. The music application reads the saved choice when its file selector mounts the card after startup. A full library scan can also refresh the same icon cache.

The player shares one UTF-8 settings file with desktop editors. On the SD card, edit `/m30/settings.cfg`. For example:

```ini
theme=ember
shuffle=on
```

Use `theme=stock` for the built-in icon and `shuffle=off` for sequential playback. Each menu updates its own key. A missing shuffle key keeps the native preference. The firmware reads up to 512 bytes with optional UTF-8 BOM and LF or CRLF endings. Device writes pad the file with whitespace. When the shared file is absent, a valid `/themes/active.cfg` is imported on first settings load with a mounted card. Per-theme manifests remain in their theme folders. Eject after editing, then restart the player.

```sh
./build.sh --sd-theme /path/to/stock/MECHEN_M30.HEX build/sd-theme
python3 tools/theme/test_native_loader.py build/sd-theme/work/stock/fw
```

## Card format v1

```text
m30/
  settings.cfg
themes/
  sky/
    theme.cfg
    common.sty
    music.sty
```

The folder name is the theme ID and menu label. Use 1–8 lowercase ASCII characters, starting with a letter, followed by letters, digits or underscores. Each pack replaces only the resources it lists. Authors supply existing PPM slot replacements; the installer builds complete native style files for those resources. Other screens inherit stock. A style's geometry, strings and widget records must match the stock file in v1.

The manifest is ASCII. Its first line is `M30THEME 1`. Each subsequent line holds a stock resource filename, the SHA-256 of that exact stock resource, and the eight-digit hexadecimal CRC32 of the themed file. Compatibility is per overridden resource. An update to unrelated firmware code can keep the selection active. An update that changes an overridden base resource causes the whole pack to fall back to stock until rebuilt. The card keeps the user's selection on fallback.

## Try it in a scratch card directory

```sh
mkdir -p build/test-card
python3 tools/theme/sdtheme.py build/work/stock/fw build/test-card install skytrack tools/theme/examples/skytrack
python3 tools/theme/sdtheme.py build/work/stock/fw build/test-card list
python3 tools/theme/sdtheme.py build/work/stock/fw build/test-card select skytrack
python3 tools/theme/sdtheme.py build/work/stock/fw build/test-card status
```

The host selector validates the pack before replacing settings.cfg through a temporary file. A missing card, malformed selection, missing selected pack, damaged resource or incompatible resource selects stock on the next lookup. Selecting stock explicitly writes `theme=stock` to settings.cfg. Installation refuses to overwrite an existing pack.

## Firmware findings and integration boundary

* Stock setting.ap contains the setting.sty filename table at file offset 0x1b0c and the common.sty table at 0x1b50. Both include four alternate filenames. The PD196 reference implementation in case/ap/ap_setting/menu_callback_display_setting.c has a switch_to_new_theme routine that closes UI_AP and UI_COM and reopens both. It uses a fixed choice table.
* In case/drv/ui/bank_a_ui_res_open.c, the reference driver keeps two resource handles, checks RU3 magic and seeks to bitmap, language and style tables. Other renderer banks seek and read those handles while drawing. psp_rel/include/sdfs.h defines an 11-byte flat filename, a directory at 0x200, 32-byte entries and a handle with start/end addresses and a cursor. These directory characteristics also appear in the stock FWIMAGE reader in tools/repack_fw.py. This is evidence against treating sys_sd_fopen as a FAT path opener.
* The removable card uses a distinct filesystem API. tools/fw/m30fs.h documents a module-specific stub and context. The target experiment is to open /themes/skytrack/music.sty from the UI-driver context after mount, read the RU3 header, seek to music slot 37 and compare pixels with the host fixture. App-specific stub addresses cannot establish the driver ABI.
* Keep boot and recovery on the internal resources until the card mounts. Then validate the selected pack, keep a small resource routing table and stream the selected file on demand. The Python tool reads entire resources for host validation. A firmware implementation should validate in chunks or use build-generated stock fingerprints and bounded scratch memory.
* The Settings picker must enumerate compatible folders, persist a successful selection and reopen the current scene. Mid-draw card removal needs a read-error path that closes themed handles and reloads both resources from stock at a scene boundary. A PC rename test cannot establish FAT power-loss behavior on the player.
* On-player hooks, runtime card removal, SD latency and persistence through a real firmware update remain unverified.

The skytrack fixture replaces the music note beside the first Now Playing metadata row with a sky-blue star. PictureBox 7 has one frame, slot 37, at (160, 40). The 20 × 20 bitmap retains the stock corner background color. The host fixture passed a FAT32 round trip with changes confined to this bitmap.

## Executed stock icon draw

Run `python3 tools/theme/test_sd_draw.py build/work/stock/fw` with the GNU MIPS cross-compiler and qemu-mipsel-static installed. The test pins the driver and style hashes before extracting instructions.

| Stock drv_ui.drv file offset | Observed job |
|---|---|
| 0x2684 | Resource open; the two branches call kernel op 13, followed by op 17 to read the header and a comparison against RU3 at 0x2748. Identified by disassembly. |
| 0xde00 | PictureBox draw; seeks to the 32-byte widget record, resolves its frame, and calls the routine at 0xbfc0a9ac. |
| 0x3ac | Looks up the 16-byte bitmap entry. Calls through the pointer at 0x9fc0bf9c with the resource handle, pixel offset and destination rectangle. |
| 0x418 | Transfer routine; checks display bounds, reads pixels into the display buffer in chunks and submits each chunk. |

The isolated 24Kc test runs the last three instruction ranges, rebinding external pointers to test services. It supplies an initialized resource context. Widget and bitmap descriptors always come from stock music.sty. Only the transfer for app resource offset 0x191cc (800 bytes) can use the themed file. The captured RGB565 submissions match the stock note, the SD star, and the stock note again if opening the selected file fails.

QEMU user-mode supplies Linux file handles. The test substitutes the LCD callbacks, selects the pack on the host and explicitly binds the stock transfer routine. It does not establish the live value of the callback on the player, a native removable-card ABI in driver context, or recovery from a read failure after drawing has started.

### Nightbay

Select `nightbay` in Settings → Theme for a crescent icon and a mountain wallpaper on shared list backgrounds. The selected row uses the accent and distant-ridge colors. The wallpaper also fills the Now Playing background and replaces the flat background on the home dial artwork. Album covers draw above the wallpaper. Edit the four RGB colors and six ridge heights in `tools/theme/examples/nightbay/wall.json` and rebuild the pack with the SD theme installer to customize it. The compiled `wall.bin` occupies 16 bytes. The firmware checks its size and signature and uses the stock background when it is missing.

The bundled card installer migrates a valid legacy theme ID if the shared config is absent, preserves existing UTF-8 settings, reserves a 512-byte record and verifies the copied files. Changing a theme on-device now reopens the shared file and verifies the sector after writing. The legacy file can remain on the card for older firmware.

### Cover Flow in Now Playing

Build with `--sd-theme --coverflow`. Prepare an M3U playlist on the desktop with Python, Pillow and Mutagen. Pass the playlist, its SD card root (or a local copy with the same music paths), and an output directory.

```sh
python3 tools/coverflow/prepare.py "/path/to/card/Playlists/mix.m3u8" "/path/to/card" ./flow-card
```

Copy the generated playlist and its `.CF7` file to the card root. Open the generated playlist through Folders and play a song. To change the order or artwork, rerun the tool on the source playlist. Each artwork record occupies 1 KiB. The player checks the five artwork slots on each track refresh and loads missing tiles. One neighbour appears on either side of the playing track in the full-screen 320×240 view. One additional cover on each side is preloaded when available. At sequence boundaries only available tracks appear. Cover Flow shows neighbors in playlist order until the engine has a shuffle cursor. It then uses the engine’s current permutation. Future shuffle seeds are chosen by playback.

The packer uses embedded covers or album-folder artwork and draws a music note for missing art. Ordinary playback and unavailable packs use stock artwork. The packed-code test runs the firmware refresh call in QEMU with substitute filesystem and LCD services. For a PNG of the actual draw output from a prepared queue of at least eight tracks, run:

```sh
python3 tools/coverflow/test_packed.py build/coverflow/work --pack flow-card/Fxxxxxxx.CF7 --preview flow.png
```

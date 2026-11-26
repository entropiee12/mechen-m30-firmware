#!/usr/bin/env bash
# Build patched MECHEN M30 firmware from the official 2025-06-26 MECHEN_M30.HEX.
#
# usage: ./build.sh [--test] [--no-playall] [--no-keys] [--no-library] [--theme DIR]
#                   STOCK_MECHEN_M30.HEX [OUTDIR]
#   --test        also run the harnesses (shuffle, shuffle all, scroll, keys, library, m3u vis/hook, version)
#   --coverflow   enable full-screen Cover Flow for prepared M3U playlists
#   --sd-theme    load the selected Now Playing icon during the library scan
#   --theme DIR   repaint UI bitmaps from the theme in DIR (tools/theme/sty.py), a folder of
#                 <sty>_<bitmap-index>.ppm images
#   --no-playall  leave out shuffle-all mode
#   --no-keys     leave out the screen-off media keys
#   --no-m3uhook  leave out the M3U/M3U8 selection hook in the file browser
#   --no-m3uvis   leave out the M3U/M3U8 visibility slots (AIF/MP2/POS/AA ext slots
#                 repurposed in fat_32n.drv, exfatn.drv and the browser.ap tables)
#   --no-m3uplay  leave out the M3U play-on-click hook (clicking a .m3u row plays
#                 its tracks with the stock engine instead of showing a dialog)
#   --probe-m3uplay  diagnostic build of the play-on-click hook: any failure after
#                 the record fetch forces the engine launch (class 0) instead of
#                 the stock "file format not supported" dialog, so the on-device
#                 outcome shows whether the hook chain ran at all
#   --no-version  leave the Settings > Information version strings stock
#   --no-library  leave out the whole-card library (keep the stock 4,000-song limit)
#   --library-bench  diagnostic: scan 100 files, then time the file operations (tools/library/bench.c)
#   --library-probe  diagnostic: scan 100 files, run the real passes, leave MUSIC.TMP and the
#                    passes' globals on the card for comparison with the harness
#   --library-iotest diagnostic: scan 100 files, then test the kernel's reads and writes on a
#                    4 MB MUSIC.TMP and the library (tools/library/iotest.c)
#   --library-replay diagnostic: scan 100 files, then replay the file operations from
#                    IOTEST.OPS in the card root with self-describing data (tools/library/replay.c)
# Writes OUTDIR/MECHEN_M30.HEX (default OUTDIR: build). Tools, overridable from the environment:
#   CROSS=mips64-linux-gnu-   ATJBOOTTOOL=atjboottool   QEMU=qemu-mipsel-static   PYTHON=python3
#   BUILD_CHANNEL=dev|nightly|release  version shown on Settings > Information (default dev;
#                                      see docs/versioning.md; metadata lands in OUTDIR/version.json)
set -euo pipefail

COVERFLOW=0
SD_THEME=0
TEST=0 PLAYALL=1 KEYS=1 LIBRARY=1 M3UHOOK=1 M3UVIS=1 M3UPLAY=1 VERSION_PATCH=1 THEME= LIB_MAX=24000 LIB_SRC=index.c LIB_PREALLOC= LIB_DEFS= PROBE=0
while [ $# -gt 0 ]; do
  case $1 in
    --test) TEST=1; shift ;;
    --coverflow) COVERFLOW=1; shift ;;
    --sd-theme) SD_THEME=1; shift ;;
    --theme) THEME=$2; shift 2 ;;
    --no-playall) PLAYALL=0; shift ;;
    --no-keys) KEYS=0; shift ;;
    --no-m3uhook) M3UHOOK=0; shift ;;
    --no-m3uvis) M3UVIS=0; shift ;;
    --no-m3uplay) M3UPLAY=0; shift ;;
    --probe-m3uplay) PROBE=1; shift ;;
    --no-version) VERSION_PATCH=0; shift ;;
    --no-library) LIBRARY=0; shift ;;
    --library-bench) LIB_SRC=bench.c; LIB_PREALLOC=$LIB_MAX; LIB_MAX=100; shift ;;
    --library-probe) LIB_DEFS=-DPROBE; LIB_PREALLOC=$LIB_MAX; LIB_MAX=100; shift ;;
    --library-iotest) LIB_SRC=iotest.c; LIB_PREALLOC=$LIB_MAX; LIB_MAX=100; shift ;;
    --library-replay) LIB_SRC=replay.c; LIB_PREALLOC=$LIB_MAX; LIB_MAX=100; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) break ;;
  esac
done
[ $# -ge 1 ] || { sed -n '2,13p' "$0"; exit 2; }
KEY_APPS="browser music mainmenu playlist setting"

ROOT=$(cd "$(dirname "$0")" && pwd); T=$ROOT/tools
STOCK=$(realpath "$1"); OUT=$(realpath -m "${2:-build}"); W=$OUT/work
CROSS=${CROSS:-mips64-linux-gnu-}; ATJ=${ATJBOOTTOOL:-atjboottool}; QEMU=${QEMU:-qemu-mipsel-static}
PY=${PYTHON:-python3}
STOCK_SHA=6c7e27c8c90c4942664d66a0d6c9f9e48f8d3bc7d2d24223568255e233551258
CFLAGS="-EL -mabi=32 -march=mips32 -mips16 -Os -G0 -mno-abicalls -fno-pic -ffreestanding -fno-builtin
 -nostdlib -fno-toplevel-reorder -fno-jump-tables -fno-asynchronous-unwind-tables -falign-functions=4 -Wall -Wextra"
HFLAGS="-EL -mabi=32 -march=mips32 -mno-mips16 -O1 -G0 -mno-abicalls -fno-pic -ffreestanding -fno-builtin -nostdlib"
PROBE_DEF=; if [ $PROBE = 1 ]; then PROBE_DEF=-DM3UPLAY_PROBE; fi

for t in "${CROSS}gcc" "$ATJ" "$PY"; do command -v "$t" >/dev/null || { echo "missing tool: $t (see README)"; exit 1; }; done
[ $TEST = 0 ] || command -v "$QEMU" >/dev/null || { echo "missing tool: $QEMU (needed for --test)"; exit 1; }
[ "$(sha256sum "$STOCK" | cut -d' ' -f1)" = $STOCK_SHA ] || {
  echo "$STOCK is not the 2025-06-26 MECHEN_M30.HEX (sha256 $STOCK_SHA)."
  echo "The patches use fixed addresses from that release; other versions will fail the stock-byte checks."; exit 1; }

rm -rf "$W"; mkdir -p "$W"/stock/afi "$W"/stock/fw "$W"/verify/afi "$W"/verify/fw
cd "$W"

unpack() {  # HEX DIR: decrypt the FWU, then unpack AFI and FWIMAGE.FW; prints "AFI ok/bad FW ok/bad"
  "$ATJ" -c --fwu -o "$2/dec.bin" "$1" > "$2/fwu.log" 2>&1
  "$ATJ" -c -o "$2/afi/" "$2/dec.bin" > "$2/afi.log" 2>&1
  "$ATJ" -c --fw -o "$2/fw/" "$2/afi/FWIMAGE.FW" > "$2/fw.log" 2>&1
  echo "AFI $(grep -c ' Ok' "$2/afi.log")/$(grep -ci mismatch "$2/afi.log" || true) FW $(grep -c ' Ok' "$2/fw.log")/$(grep -ci mismatch "$2/fw.log" || true)"
}
blob() {  # NAME SOURCE ADDR [CFLAGS...]: compile one MIPS16e unit linked at ADDR -> NAME.bin
  local n=$1 src=$2 at=$3; shift 3
  "${CROSS}gcc" $CFLAGS "$@" -c -o "$n.raw.o" "$src"
  "${CROSS}objcopy" --set-section-alignment .text=4 "$n.raw.o" "$n.o"
  # string literals and const tables (.rodata) go right after the code: the blob is only what
  # the linker puts in .text, and the firmware has nowhere else to load from
  # Keep input alignment padding inside the section copied into firmware.
  printf 'SECTIONS { .text %s : { *(.text .text.*) *(.rodata .rodata.*) }\n' "$at" > "$n.ld"
  printf ' ASSERT(ADDR(.text) == %s, "blob load address mismatch")\n' "$at" >> "$n.ld"
  printf ' /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) } }\n' >> "$n.ld"
  "${CROSS}ld" -EL -m elf32ltsmip -T "$n.ld" -e "$at" -o "$n.elf" "$n.o"
  [ -z "$("${CROSS}size" -A "$n.elf" | awk '$1 ~ /^\.(s?data|s?bss)/ && $2 > 0')" ] || { echo "$n: has writable data, which a blob can't carry"; exit 1; }
  "${CROSS}objcopy" -O binary -j .text "$n.elf" "$n.bin"
}
sym() { "${CROSS}nm" "$1" | awk -v s="$2" '$3==s{print $1}'; }

echo "== unpack stock firmware"
echo "   $(unpack "$STOCK" "$W/stock") (ok/mismatch)"
F=$W/stock/fw

echo "== mengine.ap: shuffle fix$([ $PLAYALL = 1 ] && echo ' + whole-card shuffle')"
blob shuffle "$T/shuffle/shuffle.c" 0x7b1a808c
PA=()
if [ $PLAYALL = 1 ]; then
  blob playall_a "$T/playall/playall_a.c" 0x7b068484
  blob playall_b "$T/playall/playall_b.c" 0x7b1a82ac
  blob playall_c "$T/playall/playall_c.c" 0x7b1a84b4
  blob playall_d "$T/playall/playall_d.c" 0x7b1a8754
  blob playall_e "$T/playall/playall_e.c" 0x7b0a8458
  # the units call each other at the fixed addresses in playall.h: check them against the links
  for c in "a walk WALK" "b mix FOLDER_MIX" "b folder_reseed FOLDER_RESEED" "b folder_next FOLDER_NEXT" \
           "d folder_prev FOLDER_PREV" "d folder_song FOLDER_SONG"; do
    set -- $c
    want=$(awk -v n="$3" '$1 == "#define" && $2 == n { sub(/u$/, "", $3); print $3; exit }' "$T/playall/playall.h")
    got=$(sym playall_$1.elf $2)
    [ $((0x$got + 1)) = $((want)) ] || { echo "playall.h has $3 $want but $2 links at 0x$got"; exit 1; }
  done
  PA=(playall_a.bin playall_b.bin playall_c.bin playall_d.bin playall_e.bin)
fi
"$PY" -I "$T/patch_mengine.py" "$F/mengine.ap" mengine.ap shuffle.bin "${PA[@]}" | sed 's/^/   /'

echo "== browser.ap, music.ap: scroll acceleration"
"${CROSS}gcc" $CFLAGS -c -o tramp.raw.o "$T/scroll/tramp.S"
"${CROSS}objcopy" --set-section-alignment .text=4 tramp.raw.o tramp.o
for spec in "browser 0xbfc16a25u" "music 0xbfc16bb1u" "test"; do
  set -- $spec; n=$1
  if [ $n = test ]; then def="-DTEST -DTEST_TIMER=0x00700000u -DTEST_RAM=0x10000100u"; else def="-DTIMER_STUB=$2"; fi
  "${CROSS}gcc" $CFLAGS $def -c -o accel_$n.raw.o "$T/scroll/accel.c"
  "${CROSS}objcopy" --set-section-alignment .text=4 accel_$n.raw.o accel_$n.o
  "${CROSS}ld" -EL -m elf32ltsmip -Ttext=0x48714a80 -e stock_next -o accel_$n.elf tramp.o accel_$n.o
  "${CROSS}objcopy" -O binary -j .text accel_$n.elf accel_$n.bin
done
NX=$(sym accel_browser.elf accel_next); PV=$(sym accel_browser.elf accel_prev)
for n in music test; do
  [ "$(sym accel_$n.elf accel_next)" = "$NX" ] && [ "$(sym accel_$n.elf accel_prev)" = "$PV" ] || { echo "scroll blob layout differs for $n"; exit 1; }
done
"$PY" -I "$T/scroll/patch_scroll.py" "$F/browser.ap" 0x18900 accel_browser.bin browser.ap "$NX" "$PV"
"$PY" -I "$T/scroll/patch_scroll.py" "$F/music.ap" 0x2d200 accel_music.bin music.ap "$NX" "$PV"
echo "   blob $(stat -c %s accel_browser.bin) bytes at 0x48714a80, accel_next 0x$NX, accel_prev 0x$PV"

if [ $M3UVIS = 1 ]; then
  echo "== fat_32n.drv + exfatn.drv + browser.ap: M3U/M3U8 visibility slots"
  # repurpose the AIF/MP2/POS/AA extension-table slots (mask bits 5/10/8/12, all
  # inside the browser's music bitmap 0x7e190ea0) for M3U/M3U8/m3u/m3u8 in BOTH
  # fs drivers and the browser tables; the M30 card is exFAT, so exfatn.drv
  # (table at file 0x7f8) is the driver that actually runs there. The patch tool
  # asserts the stock slot bytes before writing
  cp "$F/fat_32n.drv" fat_32n.drv
  cp "$F/exfatn.drv" exfatn.drv
  "$PY" -I "$T/m3u/patch_m3uvis.py" fat_32n.drv fat_32n.drv exfatn.drv exfatn.drv browser.ap browser.ap | sed 's/^/   /'
fi

if [ $M3UHOOK = 1 ]; then
  echo "== browser.ap: M3U/M3U8 selection hook"
  "${CROSS}gcc" $CFLAGS -x assembler-with-cpp -c -o m3uhook.raw.o "$T/m3u/m3uhook.S"
  "${CROSS}objcopy" --set-section-alignment .text=4 m3uhook.raw.o m3uhook.o
  "${CROSS}ld" -EL -m elf32ltsmip -Ttext=0x40d1534c -e m30u_hook -o m3uhook.elf m3uhook.o
  "${CROSS}objcopy" -O binary -j .text m3uhook.elf m3uhook.bin
  HOOK_ADDR=$(sym m3uhook.elf m30u_hook); SLOT_ADDR=$(sym m3uhook.elf m30u_branch_slot)
  case "$HOOK_ADDR" in 40d15[0-6]*) ;; *) echo "   m3u hook entry outside the bank tail: $HOOK_ADDR"; exit 1;; esac
  case "$SLOT_ADDR" in 40d15[0-6]*) ;; *) echo "   m3u hook slot outside the bank tail: $SLOT_ADDR"; exit 1;; esac
  # applies on top of the scroll-patched browser.ap; patch_m3uhook.py asserts
  # the stock wrapper bytes and the zero bank tail before writing
  "$PY" -I "$T/m3u/patch_m3uhook.py" browser.ap browser.ap m3uhook.bin 0x$HOOK_ADDR 0x$SLOT_ADDR
fi

if [ $M3UPLAY = 1 ]; then
  "$PY" -I "$T/m3u/check_state_region.py" "$F"
  echo "== browser.ap + mengine.ap: virtual-folder M3U browser + track stepper"
  "${CROSS}gcc" $CFLAGS -I"$T/m3u" -DM3U_SYM_CLICK=0x71252501 -x assembler-with-cpp -c -o m3uplay_tramp.raw.o "$T/m3u/m3uplay_tramp.S"
  "${CROSS}objcopy" --set-section-alignment .text=4 m3uplay_tramp.raw.o m3uplay_tramp.o
  FLOW_DEF=
  if [ "$COVERFLOW" = 1 ]; then FLOW_DEF=-DM30_COVERFLOW; fi
  "${CROSS}gcc" $CFLAGS $FLOW_DEF -I"$T/m3u" -c -o m3u_brow.raw.o "$T/m3u/m3u_brow.c"
  "${CROSS}objcopy" --set-section-alignment .text=4 m3u_brow.raw.o m3u_brow.o
  printf 'SECTIONS { . = 0x485946C0; .text : { *(.text .text.*) *(.rodata .rodata.*) }\n' > m3u_brow.ld
  printf ' /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) } }\n' >> m3u_brow.ld
  "${CROSS}ld" -EL -m elf32ltsmip -T m3u_brow.ld -e m3uplay_tramp -o m3u_brow.elf m3uplay_tramp.o m3u_brow.o
  [ -z "$("${CROSS}size" -A m3u_brow.elf | awk '$1 ~ /^\.(s?data|s?bss)/ && $2 > 0')" ] || { echo "m3u_brow: has writable data"; exit 1; }
  "${CROSS}objcopy" -O binary -j .text m3u_brow.elf m3u_brow.bin
  [ "$(sym m3u_brow.elf m3uplay_tramp)" = "485946c0" ] || { echo "m3uplay_tramp is not at 0x485946C0: $(sym m3u_brow.elf m3uplay_tramp)"; exit 1; }
  [ "$(stat -c %s m3u_brow.bin)" -le $((0x540)) ] || { echo "m3u_brow blob too big: $(stat -c %s m3u_brow.bin) > 0x540"; exit 1; }
  SYM_OPEN=$(( 0x$(sym m3u_brow.elf m3u_vdir_open) | 1 ))
  SYM_FMT=$(( 0x$(sym m3u_brow.elf m3u_format_row) | 1 ))
  SYM_BSET=$(( 0x$(sym m3u_brow.elf m3u_vdir_setloc) | 1 ))
  SYM_BDIR=$(( 0x$(sym m3u_brow.elf m3u_vdir_dir_op) | 1 ))

  blob m3uplay "$T/m3u/m3uplay.c" 0x71252500 $FLOW_DEF $PROBE_DEF -I"$T/m3u" -DM3U_SYM_OPEN=$SYM_OPEN -DM3U_SYM_FMT=$SYM_FMT
  [ "$(sym m3uplay.elf m3u_file_click)" = "71252500" ] || { echo "m3u_file_click is not at 0x71252500: $(sym m3uplay.elf m3u_file_click)"; exit 1; }
  [ "$(stat -c %s m3uplay.bin)" -le $((0x800)) ] || { echo "m3uplay blob too big: $(stat -c %s m3uplay.bin) > 0x800"; exit 1; }
  SYM_BITM=$(( 0x$(sym m3uplay.elf m3u_vdir_get_items) | 1 ))

  "${CROSS}gcc" $CFLAGS -I"$T/m3u" -DM3U_SYM_SETLOC=$SYM_BSET -DM3U_SYM_ITEMS=$SYM_BITM -DM3U_SYM_DIROP=$SYM_BDIR \
    -x assembler-with-cpp -c -o m3u_brow_tramp.raw.o "$T/m3u/m3u_brow_tramp.S"
  "${CROSS}objcopy" --set-section-alignment .text=4 m3u_brow_tramp.raw.o m3u_brow_tramp.o
  printf 'SECTIONS { . = 0x780DB7E0; .text : { *(.text .text.*) *(.rodata .rodata.*) }\n' > m3u_brow_tramp.ld
  printf ' /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) } }\n' >> m3u_brow_tramp.ld
  "${CROSS}ld" -EL -m elf32ltsmip -T m3u_brow_tramp.ld -e m3u_b_setloc -o m3u_brow_tramp.elf m3u_brow_tramp.o
  [ -z "$("${CROSS}size" -A m3u_brow_tramp.elf | awk '$1 ~ /^\.(s?data|s?bss)/ && $2 > 0')" ] || { echo "m3u_brow_tramp: has writable data"; exit 1; }
  "${CROSS}objcopy" -O binary -j .text m3u_brow_tramp.elf m3u_brow_tramp.bin
  [ "$(stat -c %s m3u_brow_tramp.bin)" -le $((0x220)) ] || { echo "m3u_brow_tramp too big: $(stat -c %s m3u_brow_tramp.bin) > 0x220"; exit 1; }

  "${CROSS}gcc" $CFLAGS -I"$T/m3u" -x assembler-with-cpp -c -o m3u_meng_tramp.raw.o "$T/m3u/m3u_meng_tramp.S"
  "${CROSS}objcopy" --set-section-alignment .text=4 m3u_meng_tramp.raw.o m3u_meng_tramp.o
  printf 'SECTIONS { . = 0x7A053338; .text : { *(.text .text.*) *(.rodata .rodata.*) }\n' > m3u_meng_tramp.ld
  printf ' /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) } }\n' >> m3u_meng_tramp.ld
  "${CROSS}ld" -EL -m elf32ltsmip -T m3u_meng_tramp.ld -e m3u_setloc_0 -o m3u_meng_tramp.elf m3u_meng_tramp.o
  [ -z "$("${CROSS}size" -A m3u_meng_tramp.elf | awk '$1 ~ /^\.(s?data|s?bss)/ && $2 > 0')" ] || { echo "m3u_meng_tramp: has writable data"; exit 1; }
  "${CROSS}objcopy" -O binary -j .text m3u_meng_tramp.elf m3u_meng_tramp.bin
  [ "$(stat -c %s m3u_meng_tramp.bin)" -le $((0xC8)) ] || { echo "m3u_meng_tramp too big: $(stat -c %s m3u_meng_tramp.bin) > 0xC8"; exit 1; }

  blob m3u_meng "$T/m3u/m3u_meng.c" 0x7b268000 -I"$T/m3u"
  [ "$(sym m3u_meng.elf m3u_meng_step)" = "7b268000" ] || { echo "m3u_meng_step is not at 0x7B268000: $(sym m3u_meng.elf m3u_meng_step)"; exit 1; }
  [ "$(stat -c %s m3u_meng.bin)" -le $((0x600)) ] || { echo "m3u_meng blob too big: $(stat -c %s m3u_meng.bin) > 0x600"; exit 1; }

  "$PY" -I "$T/m3u/patch_m3uplay.py" browser.ap browser.ap m3uplay.bin 0x71252500 \
    m3u_brow.bin 0x485946c0 m3u_brow_tramp.bin \
    0x$(sym m3u_brow_tramp.elf m3u_b_setloc) 0x$(sym m3u_brow_tramp.elf m3u_b_del) \
    0x$(sym m3u_brow_tramp.elf m3u_b_items) 0x$(sym m3u_brow_tramp.elf m3u_b_dirop) \
    mengine.ap mengine.ap m3u_meng_tramp.bin m3u_meng.bin \
    0x$(sym m3u_meng_tramp.elf m3u_setloc_0) 0x$(sym m3u_meng_tramp.elf m3u_byno_0) \
    0x$(sym m3u_meng_tramp.elf m3u_prev_0) 0x$(sym m3u_meng_tramp.elf m3u_next_0) | sed 's/^/   /'
fi
PATCHED="mengine.ap browser.ap music.ap"
[ $M3UVIS = 1 ] && PATCHED="$PATCHED fat_32n.drv exfatn.drv"

if [ $VERSION_PATCH = 1 ]; then
  CHANNEL=${BUILD_CHANNEL:-dev}
  # the config string is folded into the dev build ID, so every build option that
  # changes the firmware changes the identifier shown on the device
  CONFIG="playall=$PLAYALL keys=$KEYS library=$LIBRARY m3uhook=$M3UHOOK m3uvis=$M3UVIS m3uplay=$M3UPLAY probe=$PROBE lib_max=$LIB_MAX lib_src=$LIB_SRC theme=${THEME:-none} indexing=manual-default"
  echo "== setting.ap: Information screen version (channel $CHANNEL)"
  if [ "$SD_THEME" = 1 ]; then CONFIG="$CONFIG sd_theme=icon-v1"; fi
  if [ "$COVERFLOW" = 1 ]; then CONFIG="$CONFIG coverflow=m3u-v1"; fi
  VMETA=$("$PY" -I "$T/version/version.py" --channel "$CHANNEL" --config "$CONFIG" --out "$OUT/version.json") || {
    echo "   channel $CHANNEL rejected by tools/version/version.py (nightly/release need a clean, correctly tagged tree)"; exit 2; }
  eval "$VMETA"
  echo "   channel=$CHANNEL version=$VERSION ident=$IDENT (base $BASE from VERSION$( [ $DIRTY = 1 ] && echo ', DIRTY' ))"
  cp "$F/setting.ap" setting.ap
  # new version + identifier strings go into the zero tail of bank 0x40114c00 and the
  # two row `la`s in sub_40115074 are redirected; stock bytes are asserted first
  "$PY" -I "$T/version/patch_version.py" setting.ap setting.ap "$VERSION" "$IDENT" | sed 's/^/   /'
  case " $PATCHED " in *" setting.ap "*) ;; *) PATCHED="$PATCHED setting.ap" ;; esac
  # the third Information row shows the build identifier, so its 修改时间
  # ("modify time") label becomes "build:id"; the .sty stays exactly the size of
  # its FWIMAGE.FW slot (the content tail shifts inside the fixed-size file)
  cp "$F/setting.sty" setting.sty
  "$PY" -I "$T/version/patch_label.py" setting.sty setting.sty | sed 's/^/   /'
  if [ "$TEST" = 1 ]; then cp setting.sty version-label.sty; fi
  case " $PATCHED " in *" setting.sty "*) ;; *) PATCHED="$PATCHED setting.sty" ;; esac
fi

if [ $KEYS = 1 ]; then
  echo "== $KEY_APPS: screen-off media keys"
  "${CROSS}gcc" $CFLAGS -c -o keypass.raw.o "$T/keys/keypass.S"
  "${CROSS}objcopy" --set-section-alignment .text=4 keypass.raw.o keypass.o
  "${CROSS}ld" -EL -m elf32ltsmip -Ttext=0x48694b80 -e keypass -o keypass.elf keypass.o
  "${CROSS}objcopy" -O binary -j .text keypass.elf keypass.bin
  for app in $KEY_APPS; do
    [ -f $app.ap ] || cp "$F/$app.ap" $app.ap        # browser and music already carry the scroll patch
    "$PY" -I "$T/keys/patch_keys.py" $app.ap keypass.bin $app.ap
    case " $PATCHED " in *" $app.ap "*) ;; *) PATCHED="$PATCHED $app.ap" ;; esac
  done
  echo "   hook 0x486947d0 -> keypass at 0x48694b80 in $KEY_APPS"
fi

if [ "$SD_THEME" = 1 ]; then
  [ "$LIBRARY:$LIB_SRC:$LIB_DEFS" = "1:index.c:" ] || { echo "SD theme needs the normal library builder"; exit 2; }
  LIB_DEFS=-DSD_THEME
  blob listicon "$T/theme/list_icon.c" 0x26409800
  blob showicon "$T/theme/show_icon.c" 0x263c9874
  flow_pref=
  if [ "$COVERFLOW" = 1 ]; then flow_pref=-DFLOW_PREF; fi
  blob walldraw "$T/theme/wall_draw.c" 0x261c9698 "-DLIST_ICON_ENTRY=(0x$(sym listicon.elf list_icon)|1u)"
  blob textback "$T/theme/text_backdrop.c" 0x263c9a00 $flow_pref "-DWALL_DRAW_ENTRY=(0x$(sym walldraw.elf draw_wall)|1u)"
  "$PY" -I "$T/theme/patch_driver.py" "$F/drv_ui.drv" showicon.bin drv_ui.drv "$(sym walldraw.elf draw_wall)" "$(sym walldraw.elf clear_wall)" "$(sym textback.elf text_backdrop)" "$(sym textback.elf flow_header_bitmap)"
  blob wall_music "$T/theme/wall_load.c" 0x40354e00 -DPREF_FS=0xbfc16bb5
  blob wall_setting "$T/theme/wall_load.c" 0x40094f00 -DPREF_FS=0xbfc1658d
  blob wall_config "$T/theme/wall_load.c" 0x71252600 -DPREF_FS=0xbfc1658d -DPREF_INDEX_VM=0xbfc16585
  blob prefs_music "$T/theme/preferences.c" 0x40654d80 $flow_pref -DPREF_FS=0xbfc16bb5 -DPREF_SYS=0xbfc16bb1 "-DWALL_ENTRY=(0x$(sym wall_music.elf load_wall)|1u)" "-DSAVE_ENTRY=(0x$(sym wall_music.elf save)|1u)"
  blob prefs_setting "$T/theme/preferences.c" 0x40014d00 $flow_pref -DPREF_FS=0xbfc1658d -DPREF_SYS=0xbfc16585 -DPREF_SETTING "-DWALL_ENTRY=(0x$(sym wall_setting.elf load_wall)|1u)" "-DSAVE_ENTRY=(0x$(sym wall_setting.elf save)|1u)"
  blob native_config "$T/theme/native_settings.c" 0x71292600
  blob prefs_config "$T/theme/preferences.c" 0x71152600 $flow_pref -DPREF_FS=0xbfc1658d -DPREF_SYS=0xbfc16585 "-DWALL_ENTRY=(0x$(sym wall_config.elf load_wall)|1u)" "-DSAVE_ENTRY=(0x$(sym wall_config.elf save)|1u)" "-DIMPORT_NATIVE=(0x$(sym native_config.elf import_native)|1u)" "-DINDEX_SYNC=(0x$(sym wall_config.elf sync_index)|1u)"
  blob native_setting "$T/theme/native_settings.c" 0x48594700 -DNATIVE_EXPORT \
    "-DFOLDER_ENTRY=(0x$(sym prefs_setting.elf folder)|1u)" "-DREAD_ENTRY=(0x$(sym prefs_setting.elf read_file)|1u)" \
    "-DREWRITE_ENTRY=(0x$(sym prefs_setting.elf rewrite)|1u)" "-DSAVE_ENTRY=(0x$(sym wall_setting.elf save)|1u)"
  blob restore "$T/theme/picker.c" 0x40414d00 -DMUSIC_RESTORE "-DPREF_ENTRY=(0x$(sym prefs_music.elf preferences)|1u)"
  "$PY" -I "$T/theme/patch_restore.py" music.ap restore.bin "$(sym restore.elf music_mount)"
  blob picker "$T/theme/picker.c" 0x40294e50 $flow_pref "-DPREF_ENTRY=(0x$(sym prefs_setting.elf preferences)|1u)"
  blob discover "$T/theme/discover.c" 0x40214e00 "-DPICK_APPLY=0x$(sym picker.elf choose)"
  for f in setting.ap setting.sty setting.mcg; do
    [ -f "$f" ] || cp "$F/$f" "$f"
    case " $PATCHED " in *" $f "*) ;; *) PATCHED="$PATCHED $f" ;; esac
  done
  "$PY" -I "$T/theme/patch_picker.py" setting.ap setting.sty setting.mcg picker.bin discover.bin \
    "$(sym discover.elf theme_menu)" "$(sym picker.elf theme_stock)" "$(sym discover.elf theme_apply)" "$(sym discover.elf theme_next)"
  cp "$F/config.ap" config.ap
  "$PY" -I "$T/theme/patch_prefs.py" "$(sym prefs_music.elf apply_preferences)" \
    "$(sym prefs_setting.elf apply_preferences)" "$(sym prefs_config.elf apply_preferences)" \
    "$(sym prefs_setting.elf shuffle_off)" "$(sym prefs_setting.elf shuffle_on)"
  "$PY" -I "$T/theme/patch_native.py" "$(sym native_setting.elf save_native)"
  PATCHED="$PATCHED drv_ui.drv config.ap"
fi
if [ $LIBRARY = 1 ]; then
  echo "== playlist.ap: whole-card library (up to $LIB_MAX songs$([ $LIB_SRC != index.c ] && echo ", DIAGNOSTIC BUILD: $LIB_SRC")$([ "$LIB_DEFS" = -DPROBE ] && echo ", DIAGNOSTIC PROBE BUILD"))"
  blob libindex "$T/library/$LIB_SRC" 0xbfc3c000 $LIB_DEFS
  blob libload "$T/library/loader.c" 0x400d5290
  # the stock caller jumps to the first byte of each blob: the entry function must be first
  [ "$(sym libindex.elf index_passes)" = "bfc3c000" ] || { echo "index_passes is not at the start of the passes blob"; exit 1; }
  [ "$(sym libload.elf load_and_run)" = "400d5290" ] || { echo "load_and_run is not at the start of the loader"; exit 1; }
  [ -f playlist.ap ] || cp "$F/playlist.ap" playlist.ap
  "$PY" -I "$T/library/patch_library.py" playlist.ap libload.bin libindex.bin playlist.ap $LIB_MAX $LIB_PREALLOC | sed 's/^/   /'
  for app in music browser mainmenu; do
    [ -f $app.ap ] || cp "$F/$app.ap" $app.ap
    "$PY" -I "$T/library/patch_library.py" --reader $app.ap $app.ap $LIB_MAX | sed 's/^/   /'
  done
  case " $PATCHED " in *" playlist.ap "*) ;; *) PATCHED="$PATCHED playlist.ap" ;; esac
  case " $PATCHED " in *" mainmenu.ap "*) ;; *) PATCHED="$PATCHED mainmenu.ap" ;; esac
fi

if [ -n "$THEME" ]; then
  [ -d "$THEME" ] || { echo "$THEME is not a theme directory"; exit 1; }
  echo "== theme: $THEME"
  # repaint only the bitmaps the theme names; a repaint keeps the file exactly the size
  # of its FWIMAGE.FW slot, so repack_fw.py stays happy and nothing else in the image moves
  for f in "$F"/*.sty; do
    b=$(basename "$f")
    "$PY" -I "$T/theme/sty.py" apply "$f" "$(realpath "$THEME")" "$b" | sed 's/^/   /'
    [ -f "$b" ] || continue        # the theme has no bitmap for this file
    cmp -s "$f" "$b" || case " $PATCHED " in *" $b "*) ;; *) PATCHED="$PATCHED $b" ;; esac
  done
fi

[ -f playlist.ap ] || cp "$F/playlist.ap" playlist.ap
case " $PATCHED " in *" playlist.ap "*) ;; *) PATCHED="$PATCHED playlist.ap" ;; esac
"$PY" "$T/metadata/port.py" music.ap playlist.ap
if [ "$M3UVIS:$M3UPLAY" = 1:1 ]; then
  echo "== Music: expose the stock Playlists list and index playlist files"
  cp "$F/music.mcg" music.mcg
  blob index_title "$T/m3u/index_title.S" 0x40155120
  [ "$(sym index_title.elf index_title)" = 40155120 ]
  "$PY" "$T/m3u/patch_music_playlists.py"
  PATCHED="$PATCHED music.mcg"
fi
if [ "$M3UPLAY" = 1 ]; then
  "${CROSS}gcc" $CFLAGS -c "$T/m3u/m3u_gate.S" -o gate.o
  "${CROSS}objcopy" -j .text -O binary gate.o gate.bin
  "$PY" - <<'PYGATE'
from pathlib import Path
p = Path("browser.ap")
d = bytearray(p.read_bytes())
gate = Path("gate.bin").read_bytes()
assert gate[0x2fe:0x300] != bytes(2), "branch must occupy both halfwords"
assert d[0x127fc:0x12800] == bytes.fromhex("80f00022")
body = gate[0x5a0:]
assert 0 < len(body) <= 0x60 and not any(d[0x12aa0:0x12aa0+len(body)])
d[0x127fc:0x12800] = gate[0x2fc:0x300]
d[0x12aa0:0x12aa0+len(body)] = body
p.write_bytes(d)
PYGATE
fi
echo "== community playback fixes and Settings > Library indexing"
for f in config.ap udisk.ap mainmenu.ap setting.ap setting.mcg setting.sty; do
  [ -f "$f" ] || cp "$F/$f" "$f"
  case " $PATCHED " in *" $f "*) ;; *) PATCHED="$PATCHED $f" ;; esac
done
blob indexflag "$T/community/index_gate.S" 0
blob indexevent "$T/community/index_gate.S" 0 -DINSERT_EVENT
index_pref=()
if [ "$SD_THEME" = 1 ]; then index_pref=("-DPREF_INDEX_ENTRY=(0x$(sym prefs_setting.elf preferences)|1u)"); fi
blob indexmenu "$T/community/index_menu.c" 0x402d5000 "${index_pref[@]}"
flow_actions=()
if [ "$COVERFLOW" = 1 ]; then flow_actions=(--flow "$(sym picker.elf flow_menu)" "$(sym picker.elf flow_on)" "$(sym picker.elf flow_off)"); fi
"$PY" -I "$T/community/port.py" "${flow_actions[@]}" "$(sym indexflag.elf allowed)" "$(sym indexflag.elf suppressed)" \
  "$(sym indexevent.elf allowed)" "$(sym indexevent.elf suppressed)" "$(sym indexmenu.elf index_menu)" \
  "$(sym indexmenu.elf index_manual)" "$(sym indexmenu.elf index_auto)"

if [ "$COVERFLOW" = 1 ]; then
  [ "$M3UPLAY:$M3UVIS" = 1:1 ] || { echo "Cover Flow needs M3U playback and visibility"; exit 2; }
  blob flow_queue "$T/coverflow/queue.c" 0x403d4f10
  blob flow_cache "$T/coverflow/cache.c" 0x48514720
  blob flow_draw "$T/coverflow/render.c" 0x7829b5f0
  blob flow_native "$T/coverflow/native.c" 0x48594700 "-DFLOW_DRAW_ENTRY=(0x$(sym flow_draw.elf flow_render)|1u)"
  blob flow_device "$T/coverflow/device.c" 0x40594fa0 "-DFLOW_NATIVE_ENTRY=(0x$(sym flow_native.elf native_cover)|1u)" "-DFLOW_QUEUE_ENTRY=(0x$(sym flow_queue.elf flow_tracks)|1u)" "-DFLOW_CACHE_ENTRY=(0x$(sym flow_cache.elf flow_cache)|1u)" "-DFLOW_DRAW_ENTRY=(0x$(sym flow_draw.elf flow_render)|1u)"
  "$PY" -I "$T/coverflow/patch.py" "$(sym flow_device.elf coverflow_refresh)" "$(sym flow_device.elf coverflow_bypass)" "$(sym flow_device.elf coverflow_text)" "$(sym flow_native.elf flow_scene_ui)" "$(sym flow_native.elf flow_metadata)"
fi
echo "== repack"
"$PY" -I "$T/repack_fw.py" "$STOCK" "$W/stock/dec.bin" "$OUT/MECHEN_M30.HEX" \
  $(for f in $PATCHED; do printf '%s=%s ' $f $f; done) | tail -n 1 | sed 's/^/   /'

echo "== verify"
echo "   $(unpack "$OUT/MECHEN_M30.HEX" "$W/verify") (ok/mismatch)"
"$PY" -I -c '
import struct, sys
d = open(sys.argv[1], "rb").read(); n = struct.unpack_from("<I", d, 16)[0]
ok = sum(struct.unpack("<%dI" % (n // 4), d[:n])) & 0xffffffff == struct.unpack_from("<I", d, n + 0x10)[0]
print("   FwuTail", "ok" if ok else "MISMATCH"); sys.exit(not ok)' "$OUT/MECHEN_M30.HEX"
for f in $PATCHED; do cmp -s $f "$W/verify/fw/$f" || { echo "   extracted $f differs from the patched input"; exit 1; }; done
changed=$(for f in "$F"/*; do b=$(basename "$f"); cmp -s "$f" "$W/verify/fw/$b" || printf '%s ' "$b"; done)
echo "   files changed from stock: $changed"
[ "$changed" = "$(printf '%s\n' $PATCHED | sort | tr '\n' ' ')" ] || { echo "   unexpected set of changed files"; exit 1; }
"$PY" -I "$T/refscan.py" mengine.ap 0x7300:0x7b068000:0x800 0x7b00:0x7b0a8000:0x500 0x8d00:0x7b1a8000:0x800 > refscan.txt

if [ $TEST = 1 ]; then
  if [ "$COVERFLOW" = 1 ]; then
    CROSS="$CROSS" QEMU="$QEMU" "$PY" -I "$T/coverflow/test_packed.py" "$W"
  fi
  echo "== QEMU tests"
  CROSS="$CROSS" QEMU="$QEMU" "$PY" -I "$T/community/test_policy.py" "$W/verify/fw"
  "${CROSS}gcc" $HFLAGS -c -o harness_sh.o "$T/shuffle/harness.c"
  blob shuffle_test "$T/shuffle/shuffle.c" 0x7b1a808c -DTEST -DTEST_RAM=0x10000000u -DTEST_RAND=0x00500000u
  mkdir -p t_shuffle && cp harness_sh.o t_shuffle/harness.o && cp shuffle_test.o t_shuffle/
  (cd t_shuffle && "${CROSS}ld" -EL -m elf32ltsmip -T "$T/shuffle/test.ld" -o test.elf harness.o shuffle_test.o)
  timeout 600 "$QEMU" -cpu 24Kc t_shuffle/test.elf > test_shuffle.txt || true
  echo "   shuffle:  $(tail -n 1 test_shuffle.txt)"

  if [ $PLAYALL = 1 ]; then
    TD="-DTEST -DTEST_RAM=0x10000000u -DTEST_RAND=0x00500000u -DTEST_VFS=0x00505000u -DTEST_RESET_ROOT=0x00501000u
      -DTEST_COUNT_FILES=0x00502000u -DTEST_SEEK_FILE=0x00503000u -DTEST_NEXT_CROSS=0x00504000u -DTEST_WALK=0x7b068485u
      -DTEST_FOLDER_RESEED=0x7b1a82d5u -DTEST_FOLDER_NEXT=0x7b1a836du -DTEST_FOLDER_MIX=0x7b1a82adu
      -DTEST_FOLDER_PREV=0x7b1a8755u -DTEST_FOLDER_SONG=0x7b1a878du -DTEST_PREV_CROSS=0x00506000u"
    mkdir -p t_playall; cd t_playall
    for u in a b c d e; do
      "${CROSS}gcc" $CFLAGS $TD -c -o ${u}_test_raw.o "$T/playall/playall_$u.c"
      "${CROSS}objcopy" --set-section-alignment .text=4 ${u}_test_raw.o ${u}_test.o
    done
    "${CROSS}gcc" $HFLAGS -Wno-unused-function -c -o harness.o "$T/playall/harness.c"
    "${CROSS}ld" -EL -m elf32ltsmip -T "$T/playall/test.ld" -o test.elf harness.o a_test.o b_test.o c_test.o d_test.o e_test.o
    cd ..
    timeout 900 "$QEMU" -cpu 24Kc t_playall/test.elf > test_playall.txt || true
    echo "   playall:  $(tail -n 1 test_playall.txt)"
  fi

  mkdir -p t_scroll; cd t_scroll
  "$PY" -I "$T/scroll/testbank.py" "$F/browser.ap" ../accel_test.bin "$NX" "$PV" .
  "${CROSS}gcc" $HFLAGS -c -o harness.o "$T/scroll/harness.c" 2>&1 | grep -v 'mixed declarations' || true
  sed 's/bank.o(.data)/bank.o(.bankcode)/' "$T/scroll/test.ld" > t.ld
  for v in stock patched; do
    printf '\t.section .bankcode,"awx"\n\t.incbin "%s"\n' "$PWD/bank_${v}_test.bin" > bank.s
    "${CROSS}as" -EL -mabi=32 -mips32 -o bank.o bank.s
    for slow in 1 0; do
      "${CROSS}ld" -EL -m elf32ltsmip -T t.ld --defsym SLOW_ONLY=$slow -o run_${v}_$slow.elf harness.o bank.o
      timeout 600 "$QEMU" -cpu 24Kc ./run_${v}_$slow.elf > out_${v}_$slow.txt || true
    done
  done
  cd ..
  echo "   scroll:   patched, mixed clicks: $(tail -n 1 t_scroll/out_patched_0.txt)"
  if cmp -s <(tail -n +2 t_scroll/out_stock_1.txt) <(tail -n +2 t_scroll/out_patched_1.txt); then
    echo "   scroll:   slow clicks identical to stock"
  else
    echo "   scroll:   SLOW CLICKS DIFFER FROM STOCK"
  fi
  if [ $KEYS = 1 ]; then
    mkdir -p t_keys; cd t_keys
    "${CROSS}gcc" $HFLAGS -c -o harness.o "$T/keys/harness.c"
    for app in $KEY_APPS; do
      "$PY" -I "$T/keys/patch_keys.py" --testbanks "$F/$app.ap" ../keypass.bin .
      printf '\t.section .bankrun,"awx"\n\t.globl bank_run\nbank_run:\t.space 0x600\n\t.section .rodata\n\t.globl bank_stock, bank_patched\nbank_stock:\t.incbin "%s"\nbank_patched:\t.incbin "%s"\n' \
        "$PWD/bank_stock_test.bin" "$PWD/bank_patched_test.bin" > banks.s
      "${CROSS}as" -EL -mabi=32 -mips32 -o banks.o banks.s
      "${CROSS}ld" -EL -m elf32ltsmip -T "$T/keys/test.ld" -o test_$app.elf harness.o banks.o
      timeout 300 "$QEMU" -cpu 24Kc ./test_$app.elf > ../test_keys_$app.txt || true
      echo "   keys:     $app: $(tail -n 2 ../test_keys_$app.txt | tr '\n' ' ')"
    done
    cd ..
  fi
  if [ $LIBRARY = 1 ]; then
    mkdir -p t_library
    "$PY" -I "$T/library/mklib.py" 6000 t_library/lib6k.lib > /dev/null
    CROSS=$CROSS QEMU=$QEMU PYTHON=$PY "$T/library/test.sh" "$F/playlist.ap" t_library/lib6k.lib t_library new > test_library.txt 2>&1 || true
    echo "   library:  $(grep -m1 -E 'reproduced|MISMATCH' test_library.txt || echo 'no result')"
  fi
  if [ $M3UVIS = 1 ]; then
    "$PY" -I "$T/m3u/test_m3uvis.py" "$F/fat_32n.drv" "$F/browser.ap" "$F/exfatn.drv" > test_m3uvis.txt 2>&1 || true
    grep -q 'test_m3uvis: OK' test_m3uvis.txt && echo 'ALL PASS' >> test_m3uvis.txt || true
    echo "   m3uvis:   $(grep -c '^PASS' test_m3uvis.txt) checks, $(tail -n 1 test_m3uvis.txt)"
  fi
  if [ $M3UHOOK = 1 ]; then
    M30_HOOK_BIN=$PWD/m3uhook.bin M30_HOOK_ADDR=0x$HOOK_ADDR M30_HOOK_SLOT=0x$SLOT_ADDR \
      CROSS=$CROSS QEMU=$QEMU "$PY" -I "$T/m3u/m3uhooktest.py" > test_m3uhook.txt 2>&1 || true
    grep -q 'm3uhooktest: OK' test_m3uhook.txt && echo 'ALL PASS' >> test_m3uhook.txt || true
    echo "   m3uhook:  $(tail -n 1 test_m3uhook.txt)"
  fi
  if [ $M3UPLAY = 1 ]; then
    M30_PLAY_BIN=$PWD/m3uplay.bin M30_PLAY_ELF=$PWD/m3uplay.elf \
    M30_BROW_BIN=$PWD/m3u_brow.bin M30_BROW_ELF=$PWD/m3u_brow.elf \
    M30_BTRAMP_BIN=$PWD/m3u_brow_tramp.bin M30_BTRAMP_ELF=$PWD/m3u_brow_tramp.elf \
    M30_MTRAMP_BIN=$PWD/m3u_meng_tramp.bin M30_MTRAMP_ELF=$PWD/m3u_meng_tramp.elf \
    M30_MSTEP_BIN=$PWD/m3u_meng.bin M30_MSTEP_ELF=$PWD/m3u_meng.elf \
      M30_COVERFLOW_TEST=$COVERFLOW CROSS=$CROSS QEMU=$QEMU "$PY" -I "$T/m3u/test_m3uplay.py" "$F/browser.ap" browser.ap "$F/mengine.ap" mengine.ap > test_m3uplay.txt 2>&1 || true
    grep -q 'test_m3uplay: OK' test_m3uplay.txt && echo 'ALL PASS' >> test_m3uplay.txt || true
    echo "   m3uplay:  $(grep -c '^PASS' test_m3uplay.txt) checks, $(tail -n 1 test_m3uplay.txt)"
  fi
  if [ $VERSION_PATCH = 1 ]; then
    "$PY" -I "$T/version/test_version.py" "$F/setting.ap" setting.ap "$VERSION" "$IDENT" "$CHANNEL" > test_version.txt 2>&1 || true
    grep -q 'test_version: OK' test_version.txt && echo 'ALL PASS' >> test_version.txt || true
    echo "   version:  $(grep -c '^PASS' test_version.txt) checks, $(tail -n 1 test_version.txt)"
    "$PY" -I "$T/version/test_label.py" "$F/setting.sty" version-label.sty > test_label.txt 2>&1 || true
    grep -q 'test_label: OK' test_label.txt && echo 'ALL PASS' >> test_label.txt || true
    echo "   label:    $(grep -c '^PASS' test_label.txt) checks, $(tail -n 1 test_label.txt)"
  fi
  for f in test_*.txt; do grep -q '^ALL PASS' $f || { echo "   $f: no ALL PASS line"; echo FAIL >> missing_pass.txt; }; done
  for f in t_scroll/out_*.txt; do grep -q '^events 30000 .* failures 0$' $f || { echo "   $f: incomplete"; echo FAIL >> missing_pass.txt; }; done
  fails=$(cat test_*.txt t_scroll/out_patched_*.txt missing_pass.txt 2>/dev/null | grep -c -E 'FAIL|MISMATCH|INVARIANT|FLAGS' || true)
  slow_same=$(cmp -s <(tail -n +2 t_scroll/out_stock_1.txt) <(tail -n +2 t_scroll/out_patched_1.txt) && echo 1 || echo 0)
  [ "$fails" = 0 ] && [ "$slow_same" = 1 ] || { echo "   TESTS FAILED (logs in $W)"; exit 1; }
fi

echo "== done: $OUT/MECHEN_M30.HEX"
sha256sum "$OUT/MECHEN_M30.HEX"

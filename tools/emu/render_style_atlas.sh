#!/usr/bin/env bash
# Render every extracted RU3 style as a PNG without modifying firmware/card data.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
FW=${1:-/private/tmp/m30-card-inspect.sNixua/fw}
OUT=${2:-/private/tmp/m30-style-atlas}

[ -d "$FW" ] || { echo "missing extracted firmware directory: $FW" >&2; exit 1; }
mkdir -p "$OUT"
found=0
for style in "$FW"/*.sty; do
  [ -f "$style" ] || continue
  found=1
  name=$(basename "$style" .sty)
  ppm="$OUT/$name.ppm"
  png="$OUT/$name.png"
  trace="$OUT/$name.json"
  font_args=()
  [ -f "$FW/new_m.fnt" ] && font_args+=(--font "$FW/new_m.fnt")
  text_args=()
  case "$name" in
    upgrade)
      text_args+=(--text '0=SELECT UPGRADE FILE:' --text '1=CONFIRM UPGRADE')
      ;;
    music)
      text_args+=(--text '1=TITLE' --text '2=ARTIST' --text '3=ALBUM' --text '4=TRACK-01.MP3')
      ;;
    playlist)
      text_args+=(--text '0=PLAYLIST' --text '1=TRACK-01.MP3')
      ;;
    udisk)
      text_args+=(--text '0=USB DISK')
      ;;
    usound)
      text_args+=(--text '0=USB AUDIO' --text '1=USB')
      ;;
    common)
      text_args+=(--text '0=ROOT' --text '3=FOLDER')
      ;;
  esac
  command=(python3 "$ROOT/tools/emu/ru3_preview.py" --style "$style" --out "$ppm" --trace "$trace")
  ((${#font_args[@]})) && command+=("${font_args[@]}")
  ((${#text_args[@]})) && command+=("${text_args[@]}")
  PYTHONPATH="$ROOT/tools/emu" "${command[@]}"
  sips -s format png "$ppm" --out "$png" >/dev/null
done
[ "$found" -eq 1 ] || { echo "no .sty files found in $FW" >&2; exit 1; }
echo "RU3 STYLE ATLAS READY dir=$OUT"

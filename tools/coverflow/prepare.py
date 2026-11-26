"""Prepare a Cover Flow M3U8 and its artwork companion for the M30 card root."""
import argparse
import hashlib
import io
import os
from pathlib import Path
import struct
import tempfile

from PIL import Image, ImageDraw, ImageOps
import mutagen


def encode_art(image):
    size = 48
    thumb = ImageOps.fit(image.convert("RGB"), (size, size), method=Image.Resampling.LANCZOS)
    colors = []
    pixels = bytearray(size * size)
    for y in range(0, size, 12):
        for x in (0, 24):
            indexed = thumb.crop((x, y, x + 24, y + 12)).quantize(colors=8)
            palette = indexed.getpalette()[:24]
            palette += [0] * (24 - len(palette))
            colors.extend((r >> 3) << 11 | (g >> 2) << 5 | b >> 3
                          for r, g, b in zip(palette[::3], palette[1::3], palette[2::3]))
            chunk = indexed.tobytes()
            for j in range(12):
                start = (y + j) * size + x
                pixels[start:start + 24] = chunk[j * 24:j * 24 + 24]
    packed = bytearray()
    for start in range(0, len(pixels), 8):
        group = sum(index << (3 * shift) for shift, index in enumerate(pixels[start:start + 8]))
        packed.extend(group.to_bytes(3, "little"))
    return struct.pack("<64H", *colors) + packed


def encode_center(image):
    cover = ImageOps.fit(image.convert("RGB"), (124, 104), method=Image.Resampling.LANCZOS)
    rgb = cover.tobytes()
    words = [(r >> 3) << 11 | (g >> 2) << 5 | (b >> 3)
             for r, g, b in zip(rgb[::3], rgb[1::3], rgb[2::3])]
    return b"".join(struct.pack("<248H", *words[start:start + 248]) + bytes(16)
                    for start in range(0, len(words), 248))


def artwork(path):
    try:
        media = mutagen.File(path)
    except mutagen.MutagenError:
        media = None
    pictures = list(getattr(media, "pictures", ()))
    tags = getattr(media, "tags", None)
    if hasattr(tags, "getall"):
        pictures.extend(tags.getall("APIC"))
    sources = [io.BytesIO(pic.data) for pic in sorted(pictures, key=lambda p: p.type != 3)]
    if tags and "covr" in tags:
        sources.extend(io.BytesIO(pic) for pic in tags["covr"])
    files = {p.name.casefold(): p for p in path.parent.iterdir() if p.is_file()}
    sources.extend(files[name] for name in ("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png")
                   if name in files)
    for source in sources:
        try:
            with Image.open(source) as image:
                return encode_art(image), encode_center(image), True
        except (OSError, ValueError):
            continue
    image = Image.new("RGB", (30, 30), "#15324a")
    pen = ImageDraw.Draw(image)
    pen.line([(12, 21), (12, 9), (22, 6), (22, 18)], fill="#bce6fa", width=2)
    for x, y in [(9, 21), (19, 18)]:
        pen.ellipse((x-2, y-1, x+3, y+2), fill="#bce6fa")
    return encode_art(image), encode_center(image), False


def fnv(data):
    value = 2166136261
    for byte in data:
        value = (value ^ byte) * 16777619 & 0xffffffff
    return value


def prepare(playlist, root, out):
    root = root.resolve()
    rows, tiles, centers = [], [], []
    missing = 0
    digest = hashlib.sha256(b"M30 Cover Flow v3")
    for raw in playlist.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip().replace("\\", "/")
        if not line or line.startswith("#"):
            continue
        if line[1:2] == ":":
            line = "/" + line[2:].lstrip("/")
        track = ((root / line.lstrip("/")) if line.startswith("/") else playlist.parent / line).resolve()
        relative = track.relative_to(root)
        name = "/" + relative.as_posix()
        if (not track.is_file() or len(name.encode()) >= 240 or len(relative.parts) > 9 or
                any(len(p)>78 or any(ord(c)>65535 for c in p) for p in relative.parts)):
            raise ValueError(f"unavailable or unsupported M30 path: {name}")
        tile, center, found = artwork(track)
        rows.append(name)
        tiles.append(tile)
        centers.append(center)
        missing += not found
        digest.update(name.encode() + b"\0" + tile + center)
    if not 1 <= len(rows) <= 65535:
        raise ValueError("expected 1 to 65535 playlist entries")
    token = int.from_bytes(digest.digest()[:4], "little") & 0xfffffff or 1
    stem = f"F{token:07X}"
    sectors = bytearray()
    for index, tile in enumerate(tiles, 1):
        prefix = struct.pack("<IIHH", 0x3346434d, token, index, len(tiles)) + tile + bytes(1008 - len(tile))
        sectors.extend(prefix + struct.pack("<I", fnv(prefix)))
    sectors.extend(b"".join(centers))
    out.mkdir(parents=True, exist_ok=True)
    outputs = [(out / (stem+".CF7"), sectors),
               (out / (playlist.stem+" - Cover Flow.m3u8"), (f"#EXTM3U\n#CF7={token:07X}\n"+"\n".join(rows)+"\n").encode())]
    for path, data in outputs:
        if path.exists():
            old = path.read_bytes()
            if old == data:
                continue
            if path.suffix != ".m3u8" or not old.startswith(b"#EXTM3U\n#CF7="):
                raise ValueError(f"existing file differs: {path}")
        with tempfile.NamedTemporaryFile(dir=out, delete=False) as f:
            temp = Path(f.name)
            try:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
                os.replace(temp, path)
            finally:
                temp.unlink(missing_ok=True)
    print(f"{stem} — {len(rows)} songs, {missing} missing covers")
    return stem


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("playlist", type=Path)
    parser.add_argument("card_root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(args.playlist, args.card_root, args.output)

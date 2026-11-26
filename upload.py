#!/usr/bin/env python3
"""Copy the latest built firmware to a MECHEN M30 connected in USB storage mode."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import plistlib
import secrets
import subprocess
import sys
import tempfile

HEX = "MECHEN_M30.HEX"
LABEL = "MECHEN M30"
ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
CONFIG = ROOT / ".m30-upload.json"


@dataclass(frozen=True)
class Volume:
    device: str
    identity: str
    mount: str | None
    readonly: bool = False


def latest():
    candidates = [p for pattern in (HEX, "*/" + HEX) for p in BUILD.glob(pattern) if p.is_file()]
    if not candidates:
        raise ValueError(f"No firmware found under {BUILD}.")
    return max(candidates, key=lambda p: (p.stat().st_mtime_ns, str(p)))


def linux_volumes():
    output = subprocess.check_output(
        ["lsblk", "--json", "--list", "-o", "PATH,LABEL,MAJ:MIN,MOUNTPOINTS,RO"], text=True
    )
    return [
        Volume(d["path"], d["maj:min"], next(filter(None, d["mountpoints"] or []), None), d["ro"])
        for d in json.loads(output)["blockdevices"] if d["label"] == LABEL
    ]


def mac_volumes():
    disks = plistlib.loads(subprocess.check_output(["diskutil", "list", "-plist", "external", "physical"]))
    matches = []
    for identifier in disks["AllDisks"]:
        info = plistlib.loads(subprocess.check_output(["diskutil", "info", "-plist", identifier]))
        if info.get("VolumeName") != LABEL:
            continue
        mount = info.get("MountPoint") or None
        writable = info.get("WritableVolume" if mount else "WritableMedia")
        matches.append(Volume(info["DeviceNode"], info.get("VolumeUUID") or identifier, mount, not writable))
    return matches


def windows_volumes():
    command = """
        $ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [Text.UTF8Encoding]::new()
        $volumes = Get-CimInstance Win32_Volume -Filter "Label = 'MECHEN M30'" |
            Where-Object { $_.DriveLetter -and $_.DriveType -in 2,3 -and
                            -not $_.BootVolume -and -not $_.SystemVolume } |
            Select-Object DeviceID, DriveLetter
        ConvertTo-Json -InputObject @($volumes) -Compress
    """
    output = subprocess.check_output(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command], encoding="utf-8"
    )
    return [
        Volume(d["DeviceID"], d["DeviceID"], d["DriveLetter"] + "\\")
        for d in json.loads(output)
    ]


def detect_volumes():
    if sys.platform == "darwin":
        return mac_volumes()
    if sys.platform.startswith("linux"):
        return linux_volumes()
    if sys.platform == "win32":
        return windows_volumes()
    raise ValueError(f"Player detection is unavailable on {sys.platform}.")


def saved_mount():
    try:
        config = json.loads(CONFIG.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except ValueError:
        raise ValueError(f"Invalid {CONFIG.name}. Run with --reset-config to clear it.") from None
    if not isinstance(config, dict) or not isinstance(config.get("mount"), str):
        raise ValueError(f"Invalid {CONFIG.name}. Run with --reset-config to clear it.")
    return config["mount"] if config.get("platform") == sys.platform else None


def save_mount(mount):
    out = tempfile.NamedTemporaryFile(
        dir=CONFIG.parent, prefix=f"{CONFIG.name}.", mode="w", encoding="utf-8", delete=False
    )
    tmp = Path(out.name)
    try:
        with out:
            json.dump({"platform": sys.platform, "mount": mount}, out, indent=2)
            out.write("\n")
        tmp.replace(CONFIG)
    finally:
        tmp.unlink(missing_ok=True)


def player(matches, remembered):
    if not matches:
        raise ValueError("Connect the M30 in USB storage mode and run this command again.")
    if len(matches) == 1:
        return matches[0]
    preferred = [volume for volume in matches if remembered and volume.mount == remembered]
    if len(preferred) == 1:
        return preferred[0]
    raise ValueError("Multiple volumes are labeled MECHEN M30. Connect only the target player.")


def copy_verified(data, folder, fd=None):
    dest = folder / HEX
    tmp = folder / f".m30-{secrets.token_hex(8)}"

    def open_at(path, flags):
        return os.open(path, flags | getattr(os, "O_NOFOLLOW", 0), 0o600, dir_fd=fd)

    try:
        with open(dest, "rb", opener=open_at) as existing:
            if existing.read() == data:
                return "Already up to date"
    except FileNotFoundError:
        pass

    out = open(tmp, "xb", opener=open_at)
    try:
        with out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        with open(tmp, "rb", opener=open_at) as copied:
            if copied.read() != data:
                raise OSError("Firmware read-back verification failed.")
        os.replace(tmp, dest, src_dir_fd=fd, dst_dir_fd=fd)
        return "Copied and verified"
    finally:
        try:
            os.unlink(tmp, dir_fd=fd)
        except FileNotFoundError:
            pass


def refresh(volume):
    for current in detect_volumes():
        if (current.device, current.identity) == (volume.device, volume.identity):
            if current.readonly:
                raise ValueError(f"{current.device} is read-only.")
            return current
    raise ValueError("The connected player changed. Run the uploader again.")


def upload(data, volume):
    if not volume.mount:
        volume = refresh(volume)
        if not volume.mount:
            command = ["diskutil", "mount", volume.device] if sys.platform == "darwin" else [
                "udisksctl", "mount", "-b", volume.device,
            ]
            subprocess.run(command, check=True)
            volume = refresh(volume)
    if not volume.mount:
        raise ValueError("The player has no visible mount point.")

    folder = Path(volume.mount)
    if sys.platform == "win32":
        if refresh(volume).mount != volume.mount:
            raise ValueError("The player's drive letter changed. Run the uploader again.")
        return copy_verified(data, Path(volume.device)), volume.mount

    # Pin the mounted directory so an unplug cannot redirect a copy onto the host.
    fd = os.open(folder, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        current = refresh(volume)
        pinned_device = os.fstat(fd).st_dev
        if sys.platform == "darwin":
            expected_device = folder.stat().st_dev if folder.is_mount() else None
        else:
            expected_device = os.makedev(*map(int, volume.identity.split(":")))
        if current.mount != volume.mount or pinned_device != expected_device:
            raise ValueError("The mount point no longer belongs to the player.")
        result = copy_verified(data, Path("."), fd)
        os.fsync(fd)
    finally:
        os.close(fd)
    return result, volume.mount


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", nargs="?", type=Path, help="specific HEX or build directory")
    options = parser.add_mutually_exclusive_group()
    options.add_argument("--dry-run", action="store_true", help="show the build and player without changing anything")
    options.add_argument("--reset-config", action="store_true", help="forget the saved player location and exit")
    args = parser.parse_args()
    if args.reset_config:
        if args.firmware:
            parser.error("--reset-config does not take a firmware path")
        CONFIG.unlink(missing_ok=True)
        print("Saved player location cleared.")
        return
    src = args.firmware or latest()
    if src.is_dir():
        src = src / HEX
    data = src.read_bytes()
    if not data:
        raise ValueError(f"{src} is empty.")
    print(f"Firmware: {src}\nSHA-256: {hashlib.sha256(data).hexdigest()}", flush=True)
    remembered = saved_mount()
    volume = player(detect_volumes(), remembered)
    if volume.readonly:
        raise ValueError(f"{volume.device} is read-only.")
    print(f"Host OS: {sys.platform}")
    print(f"Player: {volume.mount or 'unmounted'} ({volume.device})", flush=True)
    if remembered and volume.mount != remembered:
        print(f"Saved location: {remembered}")
    if args.dry_run:
        return
    prompt = "Copy the firmware to this player and remember its location?"
    if not volume.mount:
        prompt = "Mount this player, copy the firmware, and remember its location?"
    try:
        answer = input(f"{prompt} [y/N] ")
    except (EOFError, KeyboardInterrupt):
        answer = ""
    if answer.strip().lower() not in ("y", "yes"):
        print("\nUpload cancelled.")
        return
    result, mount = upload(data, volume)
    print(f"{result}: {Path(mount) / HEX}", flush=True)
    if mount != remembered:
        save_mount(mount)
        print(f"Saved player location in {CONFIG.name}.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"Upload failed: {error}")

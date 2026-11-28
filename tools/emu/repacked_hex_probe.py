"""Decrypt and verify a repacked M30 HEX against its extracted candidate tree."""

from __future__ import annotations

import struct
from pathlib import Path

from fwu_repack import deinterleave, derive_key, layout
from repack_fw import afi_entry, fw_entries, sum32


def sum16(data: bytes) -> int:
    return sum(struct.unpack(f"<{len(data) // 2}H", data[: len(data) // 2 * 2])) & 0xFFFF


def _decrypt_sector(ciphertext: bytes, initial_key: list[int], session_key_7: int, rounds: int) -> bytes:
    encrypted = list(struct.unpack(f"<{len(ciphertext) // 4}I", ciphertext))
    plain = encrypted.copy()
    key = initial_key.copy()
    for round_index in range(min(rounds, len(encrypted) // 8)):
        c = encrypted[round_index * 8 : round_index * 8 + 8]
        p = [
            c[0] ^ key[1],
            c[1] ^ key[2],
            c[2] ^ key[3],
            c[3] ^ key[4],
            c[4] ^ key[5],
            c[5] ^ key[6],
            c[6] ^ key[7],
            c[7] ^ key[1] ^ key[4],
        ]
        plain[round_index * 8 : round_index * 8 + 8] = p
        key = [None, key[2], key[3], key[4], key[5], key[6], key[7], c[7] ^ session_key_7]
    return struct.pack(f"<{len(plain)}I", *plain)


def decrypt_candidate(original_hex: Path, original_afi: Path, candidate_hex: Path) -> bytes:
    original_image = original_hex.read_bytes()
    source_payload = original_afi.read_bytes()
    candidate_image = candidate_hex.read_bytes()
    if len(original_image) != len(candidate_image):
        raise AssertionError("candidate HEX size differs from original")
    if layout(original_image) != layout(candidate_image):
        raise AssertionError("candidate changed FWU encrypted-block layout")

    original_ciphertext, _, _ = deinterleave(original_image)
    candidate_ciphertext, _, _ = deinterleave(candidate_image)
    if len(original_ciphertext) != len(source_payload):
        raise AssertionError("source HEX and decrypted AFI lengths do not match")
    key0, session_key_7 = derive_key(original_ciphertext, source_payload)
    rounds = max(1, 16 - (len(original_image) >> 0x19))
    decrypted = b"".join(
        _decrypt_sector(candidate_ciphertext[offset : offset + 512], key0, session_key_7, rounds)
        for offset in range(0, len(candidate_ciphertext), 512)
    )
    if len(decrypted) != len(source_payload):
        raise AssertionError("decrypted candidate AFI has an unexpected size")
    return decrypted


def verify_repacked_hex(
    original_hex: Path,
    original_afi: Path,
    candidate_hex: Path,
    baseline_fw: Path,
    candidate_fw: Path,
    changed_members: tuple[str, ...],
) -> tuple[str, ...]:
    """Round-trip decrypt and compare every firmware file slot and checksum."""
    afi = decrypt_candidate(original_hex, original_afi, candidate_hex)
    base_afi = original_afi.read_bytes()

    afi_fw_entry, _, _ = afi_entry(afi, "fwimage.fw")
    base_fw_entry, _, _ = afi_entry(base_afi, "fwimage.fw")
    if afi_fw_entry != base_fw_entry:
        raise AssertionError("FWIMAGE.FW directory entry moved")
    _, fw_offset, fw_size = afi_entry(afi, "fwimage.fw")
    _, base_fw_offset, base_fw_size = afi_entry(base_afi, "fwimage.fw")
    if (fw_size, fw_offset) != (base_fw_size, base_fw_offset):
        raise AssertionError("FWIMAGE.FW container layout changed")

    fw = afi[fw_offset : fw_offset + fw_size]
    base_fw = base_afi[base_fw_offset : base_fw_offset + base_fw_size]
    for label, image in (("baseline", base_fw), ("candidate", fw)):
        if sum32(image[0x200:0x2000]) != struct.unpack_from("<I", image, 0x10)[0]:
            raise AssertionError(f"{label} FW directory checksum mismatch")
        if sum16(image[:0x1FE]) != struct.unpack_from("<H", image, 0x1FE)[0]:
            raise AssertionError(f"{label} FW header checksum mismatch")

    entries = {entry[0]: entry for entry in fw_entries(fw)}
    base_entries = {entry[0]: entry for entry in fw_entries(base_fw)}
    if entries.keys() != base_entries.keys():
        raise AssertionError("firmware member directory changed")

    extracted_names = {path.name for path in baseline_fw.iterdir() if path.is_file()}
    if extracted_names != entries.keys():
        raise AssertionError("baseline extracted tree member set differs from source AFI")
    for name, (_, _, data_offset, size, _nbytes, _) in base_entries.items():
        source_member = baseline_fw / name
        if source_member.stat().st_size != size:
            raise AssertionError(f"baseline extracted member has wrong size: {name}")
        if source_member.read_bytes() != base_fw[data_offset : data_offset + size]:
            raise AssertionError(f"baseline extracted member differs from source AFI: {name}")

    expected_changes = set(changed_members)
    actual_changes: set[str] = set()
    for name, (_, _, data_offset, size, _nbytes, stored_checksum) in entries.items():
        base_entry = base_entries[name]
        base_data_offset, base_size = base_entry[2], base_entry[3]
        if (data_offset, size) != (base_data_offset, base_size):
            raise AssertionError(f"member slot moved or resized: {name}")
        member = fw[data_offset : data_offset + size]
        base_member = base_fw[base_data_offset : base_data_offset + base_size]
        extracted = candidate_fw / name
        baseline = baseline_fw / name
        if not extracted.is_file() or not baseline.is_file():
            raise AssertionError(f"missing extracted member: {name}")
        if len(member) != size or len(extracted.read_bytes()) != size:
            raise AssertionError(f"member size mismatch: {name}")
        if sum32(member) != stored_checksum:
            raise AssertionError(f"FW member checksum mismatch: {name}")
        if member != extracted.read_bytes():
            raise AssertionError(f"repacked member differs from candidate tree: {name}")
        if member != base_member:
            actual_changes.add(name)
        if name not in expected_changes and member != base_member:
            raise AssertionError(f"unexpected changed member: {name}")
        if name not in expected_changes and extracted.read_bytes() != baseline.read_bytes():
            raise AssertionError(f"baseline tree unexpectedly differs: {name}")

    if actual_changes != expected_changes:
        raise AssertionError(
            f"wrong changed-member set: expected={sorted(expected_changes)}, "
            f"actual={sorted(actual_changes)}"
        )
    afi_stored_checksum = struct.unpack_from("<I", afi, afi_fw_entry + 28)[0]
    if sum32(fw) != afi_stored_checksum:
        raise AssertionError("AFI FWIMAGE.FW checksum mismatch")
    if sum32(afi[:0xFFC]) != struct.unpack_from("<I", afi, 0xFFC)[0]:
        raise AssertionError("AFI container checksum mismatch")
    fwu_size = layout(candidate_hex.read_bytes())[0]
    image = candidate_hex.read_bytes()
    if sum32(image[:fwu_size]) != struct.unpack_from("<I", image, fwu_size + 0x10)[0]:
        raise AssertionError("FWU trailer checksum mismatch")
    return tuple(sorted(actual_changes))

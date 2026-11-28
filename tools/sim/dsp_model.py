"""Safe host-side model of the recovered ``mmm_mp.al`` DSP selector.

The firmware lookup table is proven, but the DSP payload ABI is not.  This
module therefore resolves a format index to a filename and optionally checks
an extracted member's opaque descriptor; it never executes DSP bytes.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
from pathlib import Path
import struct

from config_bin import read_config_bin_item


MMM_MP_DSP_NAMES: tuple[str, ...] = (
    "adMP3.dsp",
    "adWMA.dsp",
    "adWAVH.dsp",
    "adFLAC.dsp",
    "adAPE.dsp",
    "adSBC.dsp",
    "adAAC.dsp",
    "adDSD.dsp",
    "adAAX.dsp",
    "adOGG.dsp",
    "adALAC.dsp",
    "adWMAL.dsp",
    "adTTA.dsp",
    "audiopp.dsp",
    "adppext.dsp",
    "adppdrc.dsp",
)

DSP_HEADER_BYTES = 16 * 4
MENGINE_FLAG_SET_DSP_INDEX = 13
MENGINE_FLAG_CLEAR_DSP_INDEX = 14
DAE_CONFIG_BYTES = 0xF0
DAE_EQ_MAX_SAMPLE_RATE_HZ = 48_000


@dataclass(frozen=True)
class DspSelection:
    """One opaque lookup result; ``payload_executable`` is always false."""

    format_index: int
    firmware_name: str
    member_name: str
    path: Path | None
    size: int | None
    sha256: str | None
    header_words: tuple[int, ...] | None

    @property
    def available(self) -> bool:
        return self.path is not None

    @property
    def payload_executable(self) -> bool:
        return False


@dataclass(frozen=True)
class DaeGroupSelection:
    """Flag-gated groups selected by the aud_dev consumer."""

    eq_bank_enabled: bool
    speaker_compensation_enabled: bool
    gain_group_one_enabled: bool
    gain_group_two_enabled: bool
    gain_group_three_enabled: bool
    dynamic_range_enabled: bool
    output_gain_enabled: bool


def firmware_dsp_name(format_index: int) -> str:
    """Return the exact mixed-case table entry used by ``mmm_mp.al``."""
    if not 0 <= format_index < len(MMM_MP_DSP_NAMES):
        raise ValueError("mmm_mp DSP format index must be in the range 0..15")
    return MMM_MP_DSP_NAMES[format_index]


def _inspect_member(path: Path) -> tuple[int, str, tuple[int, ...]]:
    data = path.read_bytes()
    if len(data) < DSP_HEADER_BYTES:
        raise ValueError(f"DSP member is shorter than its descriptor: {path}")
    if data[:4] != b"yqhx":
        raise ValueError(f"DSP member has no offset-zero yqhx marker: {path}")
    if data[4:8] == b"yqhx":
        raise ValueError(f"DSP member unexpectedly uses a resident header: {path}")
    return len(data), hashlib.sha256(data).hexdigest(), struct.unpack_from("<16I", data)


def select_dsp(format_index: int, directory: Path) -> DspSelection:
    """Resolve one selector entry without treating a missing member as safe.

    FAT member lookup is case-insensitive on the target, while the extracted
    corpus uses lower-case names.  The host model makes that normalization
    explicit and returns an unavailable selection when a table entry is not
    present in the local corpus (for example ``adSBC.dsp``).
    """
    firmware_name = firmware_dsp_name(format_index)
    member_name = firmware_name.casefold()
    path = directory / member_name
    if not path.is_file():
        return DspSelection(format_index, firmware_name, member_name, None, None, None, None)
    size, digest, words = _inspect_member(path)
    return DspSelection(format_index, firmware_name, member_name, path, size, digest, words)


def select_mengine_dsp(processing_flag: bool, directory: Path) -> DspSelection:
    """Resolve the DSP member selected by config.ap's processing flag.

    This is the ``mmm_pp.al`` rule (nonzero shared ``+2`` opens ``audiopp.dsp``).
    The music path uses ``mmm_mp.al``, which additionally opens ``adppdrc.dsp``
    for a nonzero flag at or below 48000 Hz; see ``dae_shm_flow`` and
    ``docs/findings-kernel-op-table.md``.
    """
    if not isinstance(processing_flag, bool):
        raise ValueError("mengine processing flag must be a boolean")

    format_index = (
        MENGINE_FLAG_SET_DSP_INDEX
        if processing_flag
        else MENGINE_FLAG_CLEAR_DSP_INDEX
    )
    return select_dsp(format_index, directory)


def select_initial_mengine_dsp_from_config_item_96(
    config_item: bytes,
    directory: Path,
) -> DspSelection:
    """Resolve the initial DSP choice from config.bin item 0x96.

    The firmware copies the item's first little-endian u16 to state+0x18 and
    selects the processing DSP only when that value equals one. This models
    first-time structure initialization; a valid persistent DAE block bypasses
    this config.bin path on later boots.
    """
    if len(config_item) != 8:
        raise ValueError("config item 0x96 must contain exactly 8 bytes")

    processing_flag = struct.unpack_from("<H", config_item)[0] == 1
    return select_mengine_dsp(processing_flag, directory)


def select_initial_mengine_dsp_from_config_bin(
    config_bin: bytes,
    directory: Path,
) -> DspSelection:
    """Resolve the first-install DSP choice from the full config.bin image."""
    config_item = read_config_bin_item(config_bin, 0x96)
    return select_initial_mengine_dsp_from_config_item_96(config_item, directory)


def apply_config_bin_dae_defaults(configuration: bytes, config_bin: bytes) -> bytes:
    """Apply the verified config.ap factory-initializer writes.

    The caller supplies the existing 0xf0-byte DAE block because this firmware
    initializer writes selected fields and leaves the rest of the block alone.
    It is used only when the persistent block is invalid or absent.  The
    secondary record helper is modeled only for the call path used here
    (selector argument zero).
    """
    if len(configuration) != DAE_CONFIG_BYTES:
        raise ValueError("DAE configuration must be exactly 0xf0 bytes")

    updated = bytearray(configuration)
    scalar_offsets = ((0x82, 0x03), (0x85, 0x07), (0x98, 0x0C), (0xA5, 0x0D))
    for item_id, structure_offset in scalar_offsets:
        payload = read_config_bin_item(config_bin, item_id)
        if not payload:
            raise ValueError(f"config item 0x{item_id:x} has no value")
        updated[structure_offset] = payload[0]

    for band_index in range(16):
        item_id = 0x86 + band_index
        payload = read_config_bin_item(config_bin, item_id)
        if len(payload) != 12:
            raise ValueError(f"config item 0x{item_id:x} must contain 12 bytes")

        structure_offset = 0x10 + band_index * 8
        updated[structure_offset : structure_offset + 8] = payload[4:12]

    output_gain_payload = read_config_bin_item(config_bin, 0xA6)
    if len(output_gain_payload) != 12:
        raise ValueError("config item 0xa6 must contain 12 bytes")

    for word_index, structure_offset in enumerate((0xBA, 0xBC, 0xBE)):
        payload_offset = 4 + word_index * 2
        updated[structure_offset : structure_offset + 2] = output_gain_payload[
            payload_offset : payload_offset + 2
        ]

    # FUN_40dd4f50(param, 1), reached by the config.ap DAE-buffer builder.
    # The first byte at +2 gates this normalization; +3 selects the early
    # clear-both path.  On the active update path, +0x0c selects item 0x83 or
    # 0x84 and the other byte is cleared.
    if updated[0x02] != 0:
        if updated[0x03] == 1:
            updated[0x04] = 0
            updated[0x05] = 0
        else:
            if updated[0x0C] == 1:
                item_84 = read_config_bin_item(config_bin, 0x84)
                if not item_84:
                    raise ValueError("config item 0x84 has no value")
                updated[0x04] = 0
                updated[0x05] = item_84[0]
            else:
                item_83 = read_config_bin_item(config_bin, 0x83)
                if not item_83:
                    raise ValueError("config item 0x83 has no value")
                updated[0x04] = item_83[0]
                updated[0x05] = 0

            updated[0x06] = 0

            # FUN_40dd4e0c(param, 0) runs only for nonzero mode +0x0c.
            if updated[0x0C] != 0:
                _apply_config_bin_dae_mode_records(updated, config_bin)

    return bytes(updated)


def _apply_config_bin_dae_mode_records(
    configuration: bytearray,
    config_bin: bytes,
) -> None:
    """Apply the verified FUN_40dd4e0c(param, 0) record writes."""
    adjustment_payload = read_config_bin_item(config_bin, 0x9C)
    if len(adjustment_payload) < 2:
        raise ValueError("config item 0x9c must contain a u16 value")
    # The consumer reads a u16 and later treats it as signed. The evidence
    # does not include item 0x9c's raw payload, so first-u16 extraction remains
    # an explicit config-item-layout assumption.
    adjustment = struct.unpack_from("<h", adjustment_payload)[0]
    adjustment_limit = -adjustment
    struct.pack_into("<H", configuration, 0xE2, adjustment & 0xFFFF)
    configuration[0x0D] = 1

    maximum = struct.unpack_from("<h", configuration, 0xEA)[0]
    for record_index in range(3):
        item_id = 0x99 + record_index
        payload = read_config_bin_item(config_bin, item_id)
        if len(payload) != 12:
            raise ValueError(f"config item 0x{item_id:x} must contain 12 bytes")

        record_offset = 0xA2 + record_index * 8
        first, second, third, upper = struct.unpack_from("<4H", payload, 4)
        first_signed = struct.unpack("<h", struct.pack("<H", first))[0]
        if adjustment_limit < first_signed:
            first = adjustment_limit & 0xFFFF
        struct.pack_into("<4H", configuration, record_offset, first, second, third, 0)
        struct.pack_into("<H", configuration, 0xE4 + record_index * 2, first)

        first_signed = struct.unpack("<h", struct.pack("<H", first))[0]
        maximum = max(maximum, first_signed)
        if record_index < 2:
            struct.pack_into("<H", configuration, 0x9E + record_index * 2, upper)

    struct.pack_into("<h", configuration, 0xEA, maximum)


def select_aud_dev_dae_groups(
    configuration: bytes,
    sample_rate_hz: int,
) -> DaeGroupSelection:
    """Report which verified DAE groups pass aud_dev's byte-flag gates.

    This models the consumer's control flow only. It does not model the DSP
    coefficients, hardware writes, or audible effect of an enabled group.
    The EQ bank has an additional sample-rate gate at 48 kHz.
    """
    if len(configuration) != DAE_CONFIG_BYTES:
        raise ValueError("DAE configuration must be exactly 0xf0 bytes")

    if sample_rate_hz <= 0:
        raise ValueError("sample rate must be a positive integer")

    return DaeGroupSelection(
        eq_bank_enabled=(
            configuration[0x07] == 1
            and sample_rate_hz <= DAE_EQ_MAX_SAMPLE_RATE_HZ
        ),
        speaker_compensation_enabled=configuration[0x08] == 1,
        gain_group_one_enabled=configuration[0x09] == 1,
        gain_group_two_enabled=configuration[0x0A] == 1,
        gain_group_three_enabled=configuration[0x0B] == 1,
        dynamic_range_enabled=configuration[0x0C] == 1,
        output_gain_enabled=configuration[0x0D] == 1,
    )

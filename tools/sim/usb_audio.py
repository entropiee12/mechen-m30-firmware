"""Evidence-backed, deliberately narrow USB Audio host-side contract.

This is not a firmware or USB-controller emulator. It records only values
found in ``usound.drv`` and validates packet framing without inventing the
unresolved PCM ring/driver ABI.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping


OBSERVED_CONTROL_REQUESTS = frozenset((0x21, 0x22))
FEATURE_UNIT_RANGE_RESPONSES = {
    0x82: bytes((0xA0, 0xE3)),
    0x83: bytes((0xF0, 0xFF)),
    0x84: bytes((0x30, 0x00)),
}
CURRENT_CONTROL_LENGTHS = {
    (0x09, 0x0100): 1,
    (0x0B, 0x0100): 1,
    (0x09, 0x0200): 2,
    (0x09, 0x0201): 2,
    (0x09, 0x0202): 2,
}

USB_CONFIG_ITEM_VID = 0x107
USB_CONFIG_ITEM_PID = 0x108
USB_CONFIG_ITEM_MANUFACTURER = 0x10C
USB_CONFIG_ITEM_PRODUCT = 0x10F
USB_CONFIG_ITEM_INTERFACE_FLAGS = 0x114
USB_CONFIG_ITEM_FORMAT_FLAG = 0x115

DEVICE_VID_OFFSET = 0x08
DEVICE_PID_OFFSET = 0x0A
DEVICE_SERIAL_INDEX_OFFSET = 0x10
CONFIG_INTERFACE_COUNT_OFFSET = 0x36
CONFIG_AUDIO_SUBFRAME_OFFSET = 0x58
CONFIG_SAMPLE_RATE_OFFSET = 0x5B
CONFIG_ENDPOINT_PACKET_OFFSET = 0x65
CONFIG_DESCRIPTOR_MINIMUM_SIZE = CONFIG_ENDPOINT_PACKET_OFFSET + 2


@dataclass(frozen=True)
class UsbAudioDescriptorPatch:
    device_descriptor: bytes
    configuration_descriptor: bytes
    string_descriptors: tuple[tuple[int, bytes], ...]
    sample_rate_hz: int

    def string_descriptor(self, index: int) -> bytes:
        for descriptor_index, descriptor in self.string_descriptors:
            if descriptor_index == index:
                return descriptor

        raise KeyError(f"USB string descriptor {index} was not patched")


@dataclass(frozen=True)
class UsbAudioContract:
    usb_bcd: int = 0x0100
    vendor_id: int = 0x10D6
    product_id: int = 0xDD00
    channels: int = 2
    sample_width_bits: int = 16
    sample_rate_hz: int = 48_000
    iso_out_endpoint: int = 0x01
    packet_bytes: int = 200
    hid_in_endpoint: int = 0x82

    @property
    def frame_bytes(self) -> int:
        return self.channels * (self.sample_width_bits // 8)

    @property
    def frames_per_packet(self) -> int:
        if self.packet_bytes % self.frame_bytes:
            raise ValueError("descriptor packet is not frame-aligned")
        return self.packet_bytes // self.frame_bytes

    def accepts_sample_rate(self, rate_hz: int) -> bool:
        """The descriptor advertises one discrete rate, not a range."""

        return rate_hz == self.sample_rate_hz

    def validate_packet(self, payload: bytes) -> int:
        """Return the frame count for one frame-aligned OUT packet.

        ``packet_bytes`` is the descriptor's maximum packet capacity. The
        firmware reads the received packet length from the USB registers and
        copies that many bytes, so shorter complete-frame packets are valid.
        """

        if len(payload) > self.packet_bytes:
            raise ValueError(
                f"packet exceeds {self.packet_bytes}-byte maximum: received {len(payload)}"
            )

        if len(payload) % self.frame_bytes:
            raise ValueError("packet length must contain complete audio frames")

        return len(payload) // self.frame_bytes

    @staticmethod
    def control_request_is_observed(request: int) -> bool:
        """Report only request codes observed in the decompiled driver."""

        return request in OBSERVED_CONTROL_REQUESTS


@dataclass
class UsbAudioControlState:
    """Model recovered USB Audio control values without simulating USB I/O."""

    contract: UsbAudioContract = field(default_factory=UsbAudioContract)
    requested_sample_rate_hz: int = 44_100
    frequency_set: bool = False
    frequency_changed: bool = False
    current_control_values: dict[tuple[int, int], bytes] = field(default_factory=dict)

    def set_current_sample_rate(self, payload: bytes) -> None:
        """Store one three-byte rate while preserving the descriptor rate."""

        if len(payload) != 3:
            raise ValueError("sample-rate SET_CUR payload must contain three bytes")

        self.requested_sample_rate_hz = int.from_bytes(payload, "little")
        self.frequency_set = True
        self.frequency_changed = True

    def get_current_sample_rate(self) -> bytes:
        """Return the descriptor's rate, matching the recovered GET_CUR path."""

        return self.contract.sample_rate_hz.to_bytes(3, "little")

    def get_feature_unit_range(self, request: int) -> bytes:
        """Return the fixed s16 response for GET_MIN, GET_MAX, or GET_RES.

        The M30 handler returns these values without consulting ``wValue``.
        This host contract records the observed request/value mapping; it does
        not model entity selection, setup packets, or endpoint transfers.
        """

        if request not in FEATURE_UNIT_RANGE_RESPONSES:
            raise ValueError(f"unsupported feature-unit range request 0x{request:02x}")

        return FEATURE_UNIT_RANGE_RESPONSES[request]

    def set_current_control(
        self,
        entity: int,
        selector_and_channel: int,
        payload: bytes,
    ) -> None:
        """Store only mute and volume controls exercised in the M30 probe."""

        key = (entity, selector_and_channel)
        expected_length = CURRENT_CONTROL_LENGTHS.get(key)

        if expected_length is None:
            raise ValueError(
                f"unsupported current control entity=0x{entity:02x} "
                f"selector=0x{selector_and_channel:04x}"
            )

        if len(payload) != expected_length:
            raise ValueError(
                f"current control payload must contain {expected_length} bytes"
            )

        self.current_control_values[key] = bytes(payload)

    def get_current_control(self, entity: int, selector_and_channel: int) -> bytes:
        """Return a previously supplied value for a recovered mute/volume slot."""

        key = (entity, selector_and_channel)

        if key not in CURRENT_CONTROL_LENGTHS:
            raise ValueError(
                f"unsupported current control entity=0x{entity:02x} "
                f"selector=0x{selector_and_channel:04x}"
            )

        if key not in self.current_control_values:
            raise KeyError(
                f"no current value supplied for entity=0x{entity:02x} "
                f"selector=0x{selector_and_channel:04x}"
            )

        return self.current_control_values[key]


def patch_usound_descriptors(
    device_descriptor: bytes,
    configuration_descriptor: bytes,
    config_items: Mapping[int, bytes],
    firmware_serial: bytes | None = None,
    firmware_serial_status: int = 0,
) -> UsbAudioDescriptorPatch:
    """Apply the verified ``usound.drv`` descriptor-field writes.

    The input config values represent buffers after the config service has
    populated the driver's parameter slots. Firmware serial status and bytes
    represent the outputs of the unresolved ROM service.
    """

    if len(device_descriptor) < 18:
        raise ValueError("USB device descriptor must contain all 18 bytes")

    if len(configuration_descriptor) < CONFIG_DESCRIPTOR_MINIMUM_SIZE:
        raise ValueError("USB configuration descriptor is shorter than patched fields")

    if not 0 <= firmware_serial_status <= 0xFF:
        raise ValueError("firmware serial status must fit one byte")

    device = bytearray(device_descriptor)
    configuration = bytearray(configuration_descriptor)
    vid_value = _parse_hex_config_item(config_items.get(USB_CONFIG_ITEM_VID))
    pid_value = _parse_hex_config_item(config_items.get(USB_CONFIG_ITEM_PID))

    if vid_value is not None:
        device[DEVICE_VID_OFFSET : DEVICE_VID_OFFSET + 2] = vid_value.to_bytes(2, "little")

    if pid_value is not None:
        device[DEVICE_PID_OFFSET : DEVICE_PID_OFFSET + 2] = pid_value.to_bytes(2, "little")

    interface_flags = _first_config_byte(
        config_items,
        USB_CONFIG_ITEM_INTERFACE_FLAGS,
    )
    format_flag = _first_config_byte(
        config_items,
        USB_CONFIG_ITEM_FORMAT_FLAG,
    )

    configuration[CONFIG_INTERFACE_COUNT_OFFSET] = 3 if interface_flags & 0x02 else 1

    if format_flag == 1:
        configuration[CONFIG_AUDIO_SUBFRAME_OFFSET] = 3
        configuration[CONFIG_AUDIO_SUBFRAME_OFFSET + 1] = 24
        configuration[
            CONFIG_SAMPLE_RATE_OFFSET : CONFIG_SAMPLE_RATE_OFFSET + 3
        ] = bytes((0x00, 0x77, 0x01))
        configuration[
            CONFIG_ENDPOINT_PACKET_OFFSET : CONFIG_ENDPOINT_PACKET_OFFSET + 2
        ] = bytes((0x20, 0x03))
        sample_rate_hz = 96_000

    else:
        configuration[CONFIG_AUDIO_SUBFRAME_OFFSET] = 2
        configuration[CONFIG_AUDIO_SUBFRAME_OFFSET + 1] = 16
        configuration[
            CONFIG_SAMPLE_RATE_OFFSET : CONFIG_SAMPLE_RATE_OFFSET + 3
        ] = bytes((0x80, 0xBB, 0x00))
        configuration[
            CONFIG_ENDPOINT_PACKET_OFFSET : CONFIG_ENDPOINT_PACKET_OFFSET + 2
        ] = bytes((0xC8, 0x00))
        sample_rate_hz = 48_000

    strings = (
        (
            1,
            _make_usb_string_descriptor(
                config_items.get(USB_CONFIG_ITEM_MANUFACTURER),
                max_characters=8,
            ),
        ),
        (
            2,
            _make_usb_string_descriptor(
                config_items.get(USB_CONFIG_ITEM_PRODUCT),
                max_characters=16,
            ),
        ),
    )

    if firmware_serial_status in (1, 2):
        if firmware_serial is None:
            raise ValueError("firmware serial bytes are required for an available serial status")

        serial_text = firmware_serial[:0x10].split(b"\0", 1)[0]

        if serial_text:
            strings += ((3, _make_usb_string_descriptor(serial_text, max_characters=0x30)),)

        else:
            device[DEVICE_SERIAL_INDEX_OFFSET] = 0

    return UsbAudioDescriptorPatch(
        bytes(device),
        bytes(configuration),
        strings,
        sample_rate_hz,
    )


def _parse_hex_config_item(value: bytes | None) -> int | None:
    if value is None or len(value) <= 2 or value[2] == 0:
        return None

    digits = value[2:6]
    if len(digits) != 4:
        raise ValueError("VID/PID config value must contain four hexadecimal digits after prefix")

    try:
        text = digits.decode("ascii")
        return int(text, 16)

    except (UnicodeDecodeError, ValueError) as error:
        raise ValueError("VID/PID config value contains invalid hexadecimal digits") from error


def _first_config_byte(config_items: Mapping[int, bytes], item_id: int) -> int:
    value = config_items.get(item_id)
    if value is None or not value:
        raise ValueError(f"missing USB descriptor config item 0x{item_id:x}")

    return value[0]


def _make_usb_string_descriptor(value: bytes | None, *, max_characters: int) -> bytes:
    source = b"" if value is None else value.split(b"\0", 1)[0]
    source = source[:max_characters]
    payload = bytearray()

    for character in source:
        payload.extend((character, 0))

    return bytes((len(payload) + 2, 3)) + bytes(payload)

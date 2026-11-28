"""Host model of the recovered M30 USB-mode selector.

The selector is an application policy boundary, not a USB-controller model.
The extracted ``config.ap`` path chooses the USB Audio app when config item
``0x116`` is one; otherwise it selects mass storage or MTP from the external
``online_device`` state. Hardware VBUS/OTG behavior and the PCM handoff stay
outside this model.
"""

from __future__ import annotations

from enum import Enum


USB_MODE_CONFIG_ITEM = 0x116


class UsbMode(str, Enum):
    USB_AUDIO = "usound"
    MASS_STORAGE = "udisk"
    MTP = "mtp"


def select_usb_mode(usound_flag: int, *, online_device: bool) -> UsbMode:
    """Apply the decompiled ``config.ap`` mode-selection branch.

    ``usound_flag`` is the value read from config item ``0x116``. The
    firmware branch compares it to one, so this model preserves the observed
    equality rather than treating every non-zero value as true.
    ``online_device`` is the already-normalized state consumed by the branch;
    its hardware producer is not inferred here.
    """

    if usound_flag == 1:
        return UsbMode.USB_AUDIO
    return UsbMode.MTP if online_device else UsbMode.MASS_STORAGE


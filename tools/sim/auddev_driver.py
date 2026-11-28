"""Bounded host model for three ``aud_dev.drv`` operation candidates.

Source provenance:

* ``analysis/community/fw/aud_dev.drv`` SHA-256
  ``3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c``.
* MIPS16 bank 0 is file ``0x500..0x7ff`` mapped at ``0x9400``. Its operation
  4 and 6 entries point to ``0x9588`` and ``0x949c``.
* MIPS16 bank 1 is file ``0x800..0xdff`` mapped at ``0x9400``. Its operation
  8 entry points to ``0x9448``.
* The literal-pool words at bank-relative addresses ``0x961c..0x9628``,
  ``0x957c..0x9584``, and ``0x9538..0x9548`` identify the MMIO addresses
  below. The code at those entries supplies the masks and branches modeled
  here.

The firmware's fixed-ROM dispatcher is absent. Selecting a function with
``table[operation]`` is therefore an inference, even though independent
drivers' call shapes support it. Operation 6's arguments are treated as
sample-rate codes because its branches match common kHz values; that semantic
label is also an inference. Operations 4 and 8 intentionally have no semantic
names here.

This models only 32-bit MMIO read/modify/write results and the observed zero
return value. It does not model timing, peripheral side effects, audio output,
the ROM dispatcher, or calls outside operations 4, 6, and 8. Callers must
provide every MMIO word read by a selected operation; missing values are not
silently treated as zero.
"""

from __future__ import annotations

from collections.abc import MutableMapping


U32_MASK = 0xFFFFFFFF

OPERATION_4 = 4
OPERATION_6 = 6
OPERATION_8 = 8

MMIO_C0000000 = 0xC0000000
MMIO_C0000110 = 0xC0000110
MMIO_C000100C = 0xC000100C
MMIO_C0050000 = 0xC0050000
MMIO_C0050004 = 0xC0050004
MMIO_C005001C = 0xC005001C
MMIO_C0051000 = 0xC0051000
MMIO_C0051010 = 0xC0051010
MMIO_C0052000 = 0xC0052000
MMIO_C0090060 = 0xC0090060

_OPERATION_4_REGISTERS = (
    MMIO_C0000000,
    MMIO_C0050000,
    MMIO_C005001C,
    MMIO_C0050004,
)
_OPERATION_6_REGISTERS = (
    MMIO_C0052000,
    MMIO_C0000110,
    MMIO_C000100C,
)


def _require_u32(value: int, name: str) -> int:
    if type(value) is not int or not 0 <= value <= U32_MASK:
        raise ValueError(f"{name} must be an unsigned 32-bit integer")

    return value


def _read_words(
    mmio: MutableMapping[int, int],
    addresses: tuple[int, ...],
) -> dict[int, int]:
    words: dict[int, int] = {}
    for address in addresses:
        if address not in mmio:
            raise KeyError(f"missing synthetic MMIO word at 0x{address:08x}")

        words[address] = _require_u32(mmio[address], f"MMIO[0x{address:08x}]")

    return words


def _operation_4(arg0: int, arg1: int, words: dict[int, int]) -> dict[int, int]:
    c0000000 = words[MMIO_C0000000] | 0x800
    c0050000 = words[MMIO_C0050000] | 1
    c005001c = words[MMIO_C005001C] & 0xFFFFFF7F
    c0050004 = words[MMIO_C0050004]

    if arg1 == 0:
        c0050004 &= 0xFFFFFFCF
        c0050004 |= (arg0 << 4) & U32_MASK
        c0050004 &= 0xFFFFFFFE
        c0050004 |= 1
        c0050000 |= 0x10
    else:
        c0050004 &= 0xFFFFCFFF
        c0050004 |= (arg0 << 12) & U32_MASK
        c0050004 &= 0xFFFFFEFF
        c0050004 |= 0x100
        c0050000 |= 0x20

    return {
        MMIO_C0000000: c0000000 & U32_MASK,
        MMIO_C0050000: c0050000 & U32_MASK,
        MMIO_C005001C: c005001c & U32_MASK,
        MMIO_C0050004: c0050004 & U32_MASK,
    }


def _operation_6(arg0: int, words: dict[int, int]) -> dict[int, int]:
    mode_enabled = (words[MMIO_C0052000] & 1) != 0
    special_high_rate = mode_enabled and arg0 == 0xC0

    c0000110 = (words[MMIO_C0000110] & 0xFFFFFFF0) | 6
    selector_by_rate = {
        0x08: 6,
        0x0B: 5,
        0x0C: 5,
        0x10: 4,
        0x16: 3,
        0x18: 3,
        0x20: 2,
        0x2C: 1,
        0x30: 1,
        0x60: 0,
        0xC0: 0,
    }
    selector = selector_by_rate.get(arg0, 1)

    if arg0 in (0x0B, 0x16, 0x2C):
        c0000110 = (c0000110 & 0xFFFFFFF0) | 2

    if mode_enabled and not special_high_rate:
        if selector == 0:
            selector = 1
        elif selector < 6:
            selector += 2
        else:
            selector = 1

    c000100c = (words[MMIO_C000100C] & 0xFFFFFFF8) | selector

    return {
        MMIO_C0000110: c0000110 & U32_MASK,
        MMIO_C000100C: c000100c & U32_MASK,
    }


def _operation_8(
    arg0: int,
    arg1: int,
    words: dict[int, int],
) -> dict[int, int]:
    if arg0 != 4:
        register = words[MMIO_C0051010] | 0x04000000

        if arg0 < 2:
            register &= 0xFFFFFFF3
            register &= 0xFFFFCFFF
            register |= 0x180
            register &= 0xFFFFF1FF
            register |= (arg1 << 9) & U32_MASK
            return {MMIO_C0051010: register & U32_MASK}

        if arg0 in (2, 3):
            register &= 0xFFFFFFF3
            register &= 0xFFFFFE7F
            register |= 0x3000
            register &= 0xFFF8FFFF
            register |= (arg1 << 16) & U32_MASK

            if arg0 == 3:
                register &= 0xFFFF7FFF
                return {MMIO_C0051010: register & U32_MASK}

            register |= 0x8000
            return {MMIO_C0051010: register & U32_MASK}

        return {MMIO_C0051010: register & U32_MASK}

    c0090060 = words[MMIO_C0090060] & 0xFFFFFFFC
    c0090060 &= 0xFFFFFFF3
    c0090060 |= 5

    c0051000 = words[MMIO_C0051000] & 0xFFFFFBFF
    c0051000 |= 0x400
    c0051000 |= (arg1 << 11) & U32_MASK
    c0051000 |= 0x300

    return {
        MMIO_C0090060: c0090060 & U32_MASK,
        MMIO_C0051000: c0051000 & U32_MASK,
    }


def execute_operation(
    operation: int,
    arg0: int,
    arg1: int,
    mmio: MutableMapping[int, int],
) -> int | None:
    """Apply a supported operation to synthetic 32-bit MMIO words.

    ``operation`` is the inferred zero-based operation-table index, not a
    proven ROM-dispatch contract. Unsupported operation numbers return
    ``None`` and leave ``mmio`` untouched. Supported operations return the
    firmware-observed ``0`` after applying their modeled register effects.

    Missing required MMIO words raise ``KeyError`` and invalid argument or
    word values raise ``ValueError`` before any register is changed. Arguments
    use unsigned 32-bit MIPS register bit patterns; left shifts wrap to 32
    bits, matching the modeled instructions.
    """

    if type(operation) is not int:
        raise ValueError("operation must be an integer")

    if operation not in (OPERATION_4, OPERATION_6, OPERATION_8):
        return None

    arg0 = _require_u32(arg0, "arg0")
    arg1 = _require_u32(arg1, "arg1")

    if operation == OPERATION_4:
        words = _read_words(mmio, _OPERATION_4_REGISTERS)
        writes = _operation_4(arg0, arg1, words)
    elif operation == OPERATION_6:
        words = _read_words(mmio, _OPERATION_6_REGISTERS)
        writes = _operation_6(arg0, words)

        if writes[MMIO_C000100C] == words[MMIO_C000100C]:
            del writes[MMIO_C000100C]

    else:
        if arg0 == 4:
            required_registers = (MMIO_C0090060, MMIO_C0051000)
        else:
            required_registers = (MMIO_C0051010,)

        words = _read_words(mmio, required_registers)
        writes = _operation_8(arg0, arg1, words)

    mmio.update(writes)
    return 0

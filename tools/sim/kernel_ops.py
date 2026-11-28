"""Host model of the ``kernel.drv`` slot-0x0f operation table and its shared-
memory registry (operations 0x47, 0x48 and 0x49).

Provenance (static analysis plus Ghidra p-code, 2026-11-27):

* ``analysis/community/fw/kernel.drv`` SHA-256
  ``58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6``.
  Its resident segment (file ``0x200`` to VA ``0xbfc08000``) starts with 77
  word entries; entry ``0x3e`` is zero.  Entries are ROM pointers, resident
  pointers or bank far pointers (see ``driver_bank_table``).
* The ROM dispatcher at ``0xbfc06f18`` is not in the corpus.  That it indexes
  this table with ``a3`` is an inference supported by the order of the PD196
  SDK ``kernel_cmd_e`` enum and by the call shapes of the M30 callers.
  ``PD196_KERNEL_CMD_NAMES`` is SDK reference material, not M30 evidence; the
  function at index 3 (``FREE_AP`` in the SDK) loads an image and frees
  nothing, so the names are hints and not identities.
* Operations 0x47/0x48/0x49 are the functions at bank A6 offsets ``0x9174``,
  ``0x91e8`` and ``0x9218``.  The registry model follows their decompile; the
  create/attach/delete/full-table behavior was also executed in
  ``tools/ghidra/run_kernel_shm_ops_probe.sh`` with synthetic IRQ locks.
* Creating an id twice is not checked by the code.  The model follows the
  decompile for that case; it was not executed.
"""

from __future__ import annotations

from dataclasses import dataclass
import struct

from driver_bank_table import FarTarget, UnitBankImage


KERNEL_DRV_SHA256 = "58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6"
KERNEL_OP_TABLE_FILE_OFFSET = 0x200
KERNEL_OP_TABLE_ENTRIES = 77

OP_SHM_CREATE = 0x47
OP_SHM_DELETE = 0x48
OP_SHM_ATTACH = 0x49
OP_TASK_CREATE = 0x4A
OP_DEBUG_PRINT = 0x4B

KIND_HOLE = "hole"
KIND_ROM = "rom"
KIND_RESIDENT = "resident"
KIND_BANK = "bank"
KIND_OUTSIDE = "outside-corpus-segments"

PD196_KERNEL_CMD_NAMES: tuple[str, ...] = (
    "EXECE_AP", "LOAD_CODEC", "LOAD_MMM", "FREE_AP", "FREE_CODEC", "FREE_MMM",
    "BANK_FLUSH", "DETECT_DISK", "DRV_INSTALL", "DRV_UNINSTALL",
    "GET_DRV_INSTALL_INFO", "REQUEST_DMA_CHAN", "FREE_DMA_CHAN", "SD_FOPEN",
    "SD_FCLOSE", "SD_FSEEK", "SD_FTELL", "SD_FREAD", "VM_READ", "VM_WRITE",
    "REQUEST_IRQ", "FREE_IRQ", "MALLOC", "FREE", "MQ_SEND", "MQ_RECEIVE",
    "MQ_FLUSH", "GET_FW_INFO", "GET_AB_TIMER", "MDELAY", "UDELAY", "SLEEP",
    "USLEEP", "SET_IRQ_TIMER1", "DEL_IRQ_TIMER1", "SET_TIME", "GET_TIME",
    "SET_DATE", "GET_DATE", "SET_ALARM_TIME", "GET_ALARM_TIME", "MOUNT_FS",
    "UNMOUNT_FS", "GET_FAT_TYPE_AFTER_MOUNT", "FORMAT_DISK", "LOCAL_IRQ_SAVE",
    "LOCAL_IRQ_RESTORE", "OS_TASK_RESUME", "OS_TASK_SUSPEND", "OS_TIME_DLY",
    "OS_TIME_DLY_RESUME", "OS_SCHED_LOCK", "OS_SCHED_UNLOCK", "RANDOM",
    "SET_GWTIME", "GET_GWTIME", "SET_GWDATE", "GET_GWDATE", "SET_VIDEO_APP",
    "GET_SYS_INFO", "REQUEST_CLKADJUST", "FREE_CLKADJUST", "ADJUST_CLK",
    "VM_WRITE_MULTI", "RESERVE_7", "RESERVE_8", "RESERVE_9",
)


@dataclass(frozen=True)
class KernelOpEntry:
    index: int
    pointer: int
    kind: str
    sdk_name: str | None
    far: FarTarget | None = None

    @property
    def mips16(self) -> bool:
        return bool(self.pointer & 1)


def parse_kernel_op_table(data: bytes) -> tuple[KernelOpEntry, ...]:
    """Classify the 77 entries of the kernel operation table."""
    end = KERNEL_OP_TABLE_FILE_OFFSET + KERNEL_OP_TABLE_ENTRIES * 4
    if len(data) < end or data[4:8] != b"yqhx":
        raise ValueError("not a kernel.drv image")

    segment_file, segment_size, segment_base = struct.unpack_from("<III", data, 8)
    if segment_file != KERNEL_OP_TABLE_FILE_OFFSET:
        raise ValueError("unexpected resident segment file offset")

    resident_end = segment_base + segment_size
    banks = UnitBankImage(data)
    entries: list[KernelOpEntry] = []
    for index in range(KERNEL_OP_TABLE_ENTRIES):
        pointer = struct.unpack_from(
            "<I", data, KERNEL_OP_TABLE_FILE_OFFSET + index * 4
        )[0]
        sdk_name = (
            PD196_KERNEL_CMD_NAMES[index]
            if index < len(PD196_KERNEL_CMD_NAMES)
            else None
        )
        far = None
        if pointer == 0:
            kind = KIND_HOLE
        elif (pointer >> 28) & 0xF in (0x1, 0x2):
            kind = KIND_BANK
            far = banks.resolve(pointer)
        elif pointer < segment_base:
            kind = KIND_ROM
        elif pointer < resident_end:
            kind = KIND_RESIDENT
        else:
            kind = KIND_OUTSIDE

        entries.append(KernelOpEntry(index, pointer, kind, sdk_name, far))

    return tuple(entries)


@dataclass(frozen=True)
class ShmSlot:
    shm_id: int
    length: int
    pointer: int


class KernelShmRegistry:
    """Eight-slot id to pointer registry behind kernel operations 0x47..0x49.

    The pointer is stored as given.  No data is copied, so every party that
    attaches an id receives the same address.
    """

    SLOTS = 8
    FREE = 0xFF

    def __init__(self) -> None:
        self._slots: list[ShmSlot | None] = [None] * self.SLOTS
        self.debug_prints = 0

    def create(self, shm_id: int, pointer: int, length: int) -> int:
        """Operation 0x47.  Returns 0, or 0xffffffff when no slot is free."""
        self._check_id(shm_id)
        self._check_u32(pointer, "pointer")
        if not 0 <= length <= 0xFFFF:
            raise ValueError("length must fit an unsigned 16-bit integer")

        for index, slot in enumerate(self._slots):
            if slot is None:
                self._slots[index] = ShmSlot(shm_id, length, pointer)
                return 0

        self.debug_prints += 1
        return 0xFFFFFFFF

    def attach(self, shm_id: int) -> int:
        """Operation 0x49.  Returns the first matching pointer, or 0."""
        self._check_id(shm_id)
        for slot in self._slots:
            if slot is not None and slot.shm_id == shm_id:
                return slot.pointer

        return 0

    def delete(self, shm_id: int) -> int:
        """Operation 0x48.  Frees the first matching slot and returns 0."""
        self._check_id(shm_id)
        for index, slot in enumerate(self._slots):
            if slot is not None and slot.shm_id == shm_id:
                self._slots[index] = None
                break

        return 0

    def slots(self) -> tuple[ShmSlot | None, ...]:
        return tuple(self._slots)

    def _check_id(self, shm_id: int) -> None:
        if type(shm_id) is not int or not 0 <= shm_id < self.FREE:
            raise ValueError("shared memory id must be 0..0xfe")

    @staticmethod
    def _check_u32(value: int, name: str) -> None:
        if type(value) is not int or not 0 <= value <= 0xFFFFFFFF:
            raise ValueError(f"{name} must be an unsigned 32-bit integer")

"""Fail-closed model of observed MIPS16 break-0 module-call envelopes."""

from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import struct

from driver_bank_table import FarTarget
from kernel_ops import (
    KERNEL_DRV_SHA256,
    KIND_BANK,
    KIND_HOLE,
    KIND_RESIDENT,
    KIND_ROM,
    OP_DEBUG_PRINT,
    OP_SHM_ATTACH,
    OP_SHM_CREATE,
    OP_SHM_DELETE,
    OP_TASK_CREATE,
    KernelOpEntry,
    KernelShmRegistry,
    parse_kernel_op_table,
)


LIBC_MODULE_ID = 0x10
KERNEL_MODULE_ID = 0x0F
UDISK_MODULE_ID = 0x01
OBSERVED_MODULE_IDS = frozenset({UDISK_MODULE_ID, KERNEL_MODULE_ID, LIBC_MODULE_ID})
EXCEPTION_EPC_SAVE_ADDRESS = 0x00013DD8
EXCEPTION_RA_SAVE_ADDRESS = 0x00013DDC
EXCEPTION_HANDLER_TARGET = 0xBFC06D6C


@dataclass(frozen=True)
class Break0ExceptionContext:
    """Optional register values supplied for an observed MIPS break exception."""

    exception_code: int
    epc: int
    bad_vaddr: int
    status: int
    ra: int | None = None

    def __post_init__(self) -> None:
        if self.exception_code != 9:
            raise ValueError("MIPS break 0 must report exception code 9")

        object.__setattr__(self, "exception_code", self.exception_code & 0xFFFFFFFF)
        object.__setattr__(self, "epc", self.epc & 0xFFFFFFFF)
        object.__setattr__(self, "bad_vaddr", self.bad_vaddr & 0xFFFFFFFF)
        object.__setattr__(self, "status", self.status & 0xFFFFFFFF)

        if self.ra is not None:
            object.__setattr__(self, "ra", self.ra & 0xFFFFFFFF)

    def handoff_trace(self) -> dict[str, object] | None:
        """Describe only the proven pre-handler stores when RA was supplied."""

        if self.ra is None:
            return None

        return {
            "handler_target": EXCEPTION_HANDLER_TARGET,
            "stores": [
                {"address": EXCEPTION_EPC_SAVE_ADDRESS, "value": self.epc},
                {"address": EXCEPTION_RA_SAVE_ADDRESS, "value": self.ra},
            ],
            "provenance": (
                "derived from ker_xsys.bin; register values caller-provided; "
                "vector path unverified"
            ),
        }


@dataclass(frozen=True)
class KernelTaskRecord:
    entry: int
    arg: int
    stack_top: int
    size: int


@dataclass(frozen=True)
class KernelOpEvent:
    """Host observation or explicitly synthetic response for one kernel operation."""

    label: str
    index: int
    pointer: int
    kind: str
    result: int | None = None
    mips16: bool = False
    far: FarTarget | None = None
    task_record: KernelTaskRecord | None = None
    task_id: int | None = None
    task_flags: int | None = None


@dataclass(frozen=True)
class Break0Call:
    """Captured break-0 envelope and any separately labeled host-model event."""

    module_id: int
    args: tuple[int, int, int, int]
    exception: Break0ExceptionContext | None = None
    kernel_event: KernelOpEvent | None = None


@dataclass
class FirmwareBreak0Boundary:
    """Record break-0 calls; optionally model verified kernel registry operations."""

    calls: list[Break0Call] = field(default_factory=list)
    kernel_image: bytes | None = None
    kernel_registry: KernelShmRegistry = field(default_factory=KernelShmRegistry)
    kernel_entries: tuple[KernelOpEntry, ...] | None = field(init=False, default=None)

    def __post_init__(self) -> None:
        if self.kernel_image is None:
            return

        digest = hashlib.sha256(self.kernel_image).hexdigest()

        if digest != KERNEL_DRV_SHA256:
            raise ValueError("kernel.drv SHA-256 does not match the analyzed image")

        self.kernel_entries = parse_kernel_op_table(self.kernel_image)

    def trap(
        self,
        module_id: int,
        args: tuple[int, int, int, int],
        *,
        exception: Break0ExceptionContext | None = None,
        task_record: bytes | None = None,
    ) -> Break0Call:
        if len(args) != 4:
            raise ValueError("firmware module-call envelope requires four arguments")

        normalized = tuple(value & 0xFFFFFFFF for value in args)
        normalized_module_id = module_id & 0xFFFFFFFF
        kernel_event = None

        if task_record is not None and (
            normalized_module_id != KERNEL_MODULE_ID or normalized[3] != OP_TASK_CREATE
        ):
            raise ValueError("task record belongs only to kernel operation 0x4a")

        if normalized_module_id == KERNEL_MODULE_ID and self.kernel_entries is not None:
            kernel_event = self._kernel_event(normalized, task_record)

        call = Break0Call(
            module_id=normalized_module_id,
            args=normalized,
            exception=exception,
            kernel_event=kernel_event,
        )
        self.calls.append(call)

        return call

    def _kernel_event(
        self,
        args: tuple[int, int, int, int],
        task_record: bytes | None,
    ) -> KernelOpEvent | None:
        entries = self.kernel_entries

        if entries is None or args[3] >= len(entries):
            return None

        entry = entries[args[3]]
        result = None
        decoded_task = None
        task_id = None
        task_flags = None

        if entry.index == OP_SHM_CREATE:
            result = self.kernel_registry.create(args[0], args[1], args[2])
            label = "synthetic=shm-create"
        elif entry.index == OP_SHM_DELETE:
            result = self.kernel_registry.delete(args[0])
            label = "synthetic=shm-delete"
        elif entry.index == OP_SHM_ATTACH:
            result = self.kernel_registry.attach(args[0])
            label = "synthetic=shm-attach"
        elif entry.index == OP_TASK_CREATE:
            label = "synthetic=task-create-recorded"
            task_id = args[1] & 0xFF
            task_flags = args[2] & 0xFF

            if task_record is not None:
                if len(task_record) < 14:
                    raise ValueError("task record requires three words and a u16 size")

                decoded_task = KernelTaskRecord(*struct.unpack_from("<IIIH", task_record))
        elif entry.index == OP_DEBUG_PRINT:
            label = "synthetic=debug-print-recorded"
        elif entry.kind == KIND_ROM:
            label = "synthetic=rom-zero"
            result = 0
        elif entry.kind == KIND_BANK:
            label = "observed=bank-target"
        elif entry.kind == KIND_RESIDENT:
            label = "observed=resident-target"
        elif entry.kind == KIND_HOLE:
            label = "unresolved=hole"
        else:
            label = "unresolved=outside-corpus-target"

        return KernelOpEvent(
            label=label,
            index=entry.index,
            pointer=entry.pointer,
            kind=entry.kind,
            result=result,
            mips16=entry.mips16,
            far=entry.far,
            task_record=decoded_task,
            task_id=task_id,
            task_flags=task_flags,
        )

    @property
    def libc_module_call_count(self) -> int:
        return sum(call.module_id == LIBC_MODULE_ID for call in self.calls)

    def service_effect(self, call: Break0Call) -> None:
        """Reject service execution until the fixed-ROM dispatcher is known."""

        if call.module_id not in OBSERVED_MODULE_IDS:
            raise ValueError(f"unknown firmware module: 0x{call.module_id:x}")

        raise NotImplementedError(
            f"fixed-ROM service effect for module 0x{call.module_id:x} is unresolved"
        )


UsoundCallbackBoundary = FirmwareBreak0Boundary

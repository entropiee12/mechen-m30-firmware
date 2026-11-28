import struct
import unittest

from kernel_ops import (
    KIND_BANK,
    KIND_RESIDENT,
    KIND_ROM,
    OP_DEBUG_PRINT,
    OP_SHM_ATTACH,
    OP_SHM_CREATE,
    OP_SHM_DELETE,
    OP_TASK_CREATE,
)
from test_kernel_ops import load_kernel
from usound_callback import (
    FirmwareBreak0Boundary,
    KERNEL_MODULE_ID,
    LIBC_MODULE_ID,
    UDISK_MODULE_ID,
    Break0ExceptionContext,
    UsoundCallbackBoundary,
)


class UsoundCallbackBoundaryTests(unittest.TestCase):
    def test_verified_kernel_image_routes_shared_memory_calls(self) -> None:
        boundary = UsoundCallbackBoundary(kernel_image=load_kernel())

        create = boundary.trap(KERNEL_MODULE_ID, (3, 0x10001000, 0xF0, OP_SHM_CREATE))
        attach = boundary.trap(KERNEL_MODULE_ID, (3, 0, 0, OP_SHM_ATTACH))
        delete = boundary.trap(KERNEL_MODULE_ID, (3, 0, 0, OP_SHM_DELETE))
        missing = boundary.trap(KERNEL_MODULE_ID, (3, 0, 0, OP_SHM_ATTACH))

        self.assertEqual(create.kernel_event.result, 0)
        self.assertEqual(create.kernel_event.pointer, 0x1F189175)
        self.assertEqual(attach.kernel_event.result, 0x10001000)
        self.assertEqual(delete.kernel_event.result, 0)
        self.assertEqual(missing.kernel_event.result, 0)
        self.assertEqual(boundary.kernel_registry.attach(3), 0)
        self.assertTrue(all(call.kernel_event.label.startswith("synthetic=") for call in boundary.calls))

        with self.assertRaises(NotImplementedError):
            boundary.service_effect(create)

    def test_unmodeled_selector_and_unconfigured_kernel_do_not_synthesize_results(self) -> None:
        verified = UsoundCallbackBoundary(kernel_image=load_kernel())
        unconfigured = UsoundCallbackBoundary()

        hole = verified.trap(KERNEL_MODULE_ID, (0, 0, 0, 0x3E)).kernel_event

        self.assertEqual(hole.label, "unresolved=hole")
        self.assertIsNone(hole.result)
        self.assertIsNone(unconfigured.trap(KERNEL_MODULE_ID, (3, 0, 0, OP_SHM_ATTACH)).kernel_event)
        self.assertEqual(verified.kernel_registry.slots(), (None,) * 8)

    def test_every_rom_entry_has_an_explicit_synthetic_zero_response(self) -> None:
        boundary = UsoundCallbackBoundary(kernel_image=load_kernel())
        rom_entries = [entry for entry in boundary.kernel_entries if entry.kind == KIND_ROM]

        self.assertEqual(len(rom_entries), 29)

        for entry in rom_entries:
            event = boundary.trap(KERNEL_MODULE_ID, (1, 2, 3, entry.index)).kernel_event

            self.assertEqual(event.label, "synthetic=rom-zero")
            self.assertEqual(event.result, 0)
            self.assertEqual(event.pointer, entry.pointer)

        self.assertEqual(boundary.kernel_registry.slots(), (None,) * 8)

    def test_other_bank_and_resident_entries_expose_real_target_without_result(self) -> None:
        boundary = UsoundCallbackBoundary(kernel_image=load_kernel())
        modeled = {OP_SHM_CREATE, OP_SHM_DELETE, OP_SHM_ATTACH, OP_TASK_CREATE, OP_DEBUG_PRINT}
        bank = next(entry for entry in boundary.kernel_entries if entry.kind == KIND_BANK and entry.index not in modeled)
        resident = next(entry for entry in boundary.kernel_entries if entry.kind == KIND_RESIDENT and entry.index not in modeled)

        bank_event = boundary.trap(KERNEL_MODULE_ID, (0, 0, 0, bank.index)).kernel_event
        resident_event = boundary.trap(KERNEL_MODULE_ID, (0, 0, 0, resident.index)).kernel_event

        self.assertEqual(bank_event.label, "observed=bank-target")
        self.assertEqual(bank_event.far, bank.far)
        self.assertEqual(resident_event.label, "observed=resident-target")
        self.assertEqual(resident_event.pointer, resident.pointer)
        self.assertIsNone(bank_event.result)
        self.assertIsNone(resident_event.result)

    def test_task_record_is_decoded_only_when_caller_supplies_its_bytes(self) -> None:
        boundary = UsoundCallbackBoundary(kernel_image=load_kernel())
        raw = struct.pack("<IIIH", 0xBFC27735, 0x9FC21B08, 0x9FC1BDF0, 0x4B0)

        observed = boundary.trap(
            KERNEL_MODULE_ID,
            (0x9FC27F00, 0x103, 0x1FF, OP_TASK_CREATE),
            task_record=raw,
        ).kernel_event
        unknown = boundary.trap(
            KERNEL_MODULE_ID,
            (0x9FC27F00, 3, 0xFF, OP_TASK_CREATE),
        ).kernel_event

        self.assertEqual(
            (observed.task_record.entry, observed.task_record.arg,
             observed.task_record.stack_top, observed.task_record.size),
            (0xBFC27735, 0x9FC21B08, 0x9FC1BDF0, 0x4B0),
        )
        self.assertEqual((observed.task_id, observed.task_flags), (3, 0xFF))
        self.assertIsNone(unknown.task_record)
        self.assertIsNone(observed.result)
        self.assertEqual(boundary.kernel_registry.slots(), (None,) * 8)

    def test_task_and_debug_operations_are_recorded_without_effects(self) -> None:
        boundary = UsoundCallbackBoundary(kernel_image=load_kernel())

        task = boundary.trap(KERNEL_MODULE_ID, (1, 2, 3, OP_TASK_CREATE))
        debug = boundary.trap(KERNEL_MODULE_ID, (0x1234, 0, 0, OP_DEBUG_PRINT))

        self.assertEqual(task.kernel_event.label, "synthetic=task-create-recorded")
        self.assertEqual(debug.kernel_event.label, "synthetic=debug-print-recorded")
        self.assertIsNone(task.kernel_event.result)
        self.assertIsNone(debug.kernel_event.result)
        self.assertEqual(boundary.kernel_registry.slots(), (None,) * 8)

    def test_kernel_image_hash_mismatch_rejects_model_before_calls(self) -> None:
        image = bytearray(load_kernel())
        image[-1] ^= 1

        with self.assertRaisesRegex(ValueError, "SHA-256"):
            UsoundCallbackBoundary(kernel_image=bytes(image))

    def test_records_module_id_and_four_arguments(self) -> None:
        boundary = UsoundCallbackBoundary()
        call = boundary.trap(LIBC_MODULE_ID, (1, 2, 3, 0x1_0000_0004))
        self.assertEqual(call.module_id, LIBC_MODULE_ID)
        self.assertEqual(call.args, (1, 2, 3, 4))
        self.assertIsNone(call.exception)
        self.assertEqual(boundary.libc_module_call_count, 1)

    def test_udisk_module_call_records_abi_but_not_rom_effect(self) -> None:
        boundary = FirmwareBreak0Boundary()

        call = boundary.trap(UDISK_MODULE_ID, (0, 0, 0, 3))

        self.assertEqual(call.module_id, UDISK_MODULE_ID)
        self.assertEqual(call.args, (0, 0, 0, 3))

        with self.assertRaises(NotImplementedError):
            boundary.service_effect(call)

    def test_records_optional_exception_context_as_captured(self) -> None:
        boundary = UsoundCallbackBoundary()
        exception = Break0ExceptionContext(
            exception_code=9,
            epc=0x1_8005_26AB,
            bad_vaddr=0,
            status=0x1_0000_0002,
        )

        call = boundary.trap(
            LIBC_MODULE_ID,
            (1, 2, 3, 4),
            exception=exception,
        )

        self.assertEqual(call.exception.exception_code, 9)
        self.assertEqual(call.exception.epc, 0x8005_26AB)
        self.assertEqual(call.exception.bad_vaddr, 0)
        self.assertEqual(call.exception.status, 2)

    def test_derives_only_the_proven_handoff_when_ra_is_supplied(self) -> None:
        exception = Break0ExceptionContext(
            exception_code=9,
            epc=0x1_0000_0001,
            bad_vaddr=2,
            status=3,
            ra=0x1_0000_0004,
        )

        self.assertEqual(
            exception.handoff_trace(),
            {
                "handler_target": 0xBFC06D6C,
                "stores": [
                    {"address": 0x00013DD8, "value": 1},
                    {"address": 0x00013DDC, "value": 4},
                ],
                "provenance": (
                    "derived from ker_xsys.bin; register values caller-provided; "
                    "vector path unverified"
                ),
            },
        )

    def test_does_not_invent_handoff_without_caller_ra(self) -> None:
        exception = Break0ExceptionContext(9, 1, 2, 3)

        self.assertIsNone(exception.handoff_trace())

    def test_rejects_non_breakpoint_exception_code(self) -> None:
        with self.assertRaises(ValueError):
            Break0ExceptionContext(
                exception_code=8,
                epc=0,
                bad_vaddr=0,
                status=2,
            )

    def test_does_not_invent_kernel_service_effect(self) -> None:
        boundary = UsoundCallbackBoundary()
        call = boundary.trap(LIBC_MODULE_ID, (0, 0, 0, 0))
        with self.assertRaises(NotImplementedError):
            boundary.service_effect(call)

    def test_rejects_unknown_module_id(self) -> None:
        boundary = UsoundCallbackBoundary()
        call = boundary.trap(0x99, (0, 0, 0, 0))
        with self.assertRaises(ValueError):
            boundary.service_effect(call)


if __name__ == "__main__":
    unittest.main()

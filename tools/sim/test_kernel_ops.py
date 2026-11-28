import hashlib
import unittest
from collections import Counter
from pathlib import Path

from kernel_ops import (
    KERNEL_DRV_SHA256,
    KERNEL_OP_TABLE_ENTRIES,
    KIND_BANK,
    KIND_HOLE,
    KIND_OUTSIDE,
    KIND_RESIDENT,
    KIND_ROM,
    OP_DEBUG_PRINT,
    OP_SHM_ATTACH,
    OP_SHM_CREATE,
    OP_SHM_DELETE,
    OP_TASK_CREATE,
    PD196_KERNEL_CMD_NAMES,
    KernelShmRegistry,
    parse_kernel_op_table,
)


KERNEL_PATH = (
    Path(__file__).resolve().parents[2] / "analysis" / "community" / "fw" / "kernel.drv"
)


def load_kernel() -> bytes:
    if not KERNEL_PATH.is_file():
        raise unittest.SkipTest("kernel.drv is not available")

    data = KERNEL_PATH.read_bytes()
    if hashlib.sha256(data).hexdigest() != KERNEL_DRV_SHA256:
        raise unittest.SkipTest("kernel.drv has an unexpected SHA-256")

    return data


class KernelOpTableTests(unittest.TestCase):
    def test_table_has_77_entries_and_one_hole(self):
        entries = parse_kernel_op_table(load_kernel())
        self.assertEqual(len(entries), KERNEL_OP_TABLE_ENTRIES)
        counts = Counter(entry.kind for entry in entries)
        self.assertEqual(counts[KIND_HOLE], 1)
        self.assertEqual(counts[KIND_ROM], 29)
        self.assertEqual(counts[KIND_RESIDENT], 11)
        self.assertEqual(counts[KIND_BANK], 34)
        self.assertEqual(counts[KIND_OUTSIDE], 2)
        self.assertEqual(entries[0x3E].kind, KIND_HOLE)

    def test_sdk_names_cover_the_first_67_entries_only(self):
        entries = parse_kernel_op_table(load_kernel())
        self.assertEqual(len(PD196_KERNEL_CMD_NAMES), 67)
        self.assertEqual(entries[0x12].sdk_name, "VM_READ")
        self.assertEqual(entries[0x13].sdk_name, "VM_WRITE")
        self.assertEqual(entries[0x16].sdk_name, "MALLOC")
        self.assertEqual(entries[0x33].sdk_name, "OS_SCHED_LOCK")
        self.assertIsNone(entries[OP_SHM_CREATE].sdk_name)
        self.assertIsNone(entries[0x4C].sdk_name)

    def test_vm_read_write_are_outside_every_corpus_segment(self):
        entries = parse_kernel_op_table(load_kernel())
        self.assertEqual(entries[0x12].pointer, 0xBFC19BAD)
        self.assertEqual(entries[0x13].pointer, 0xBFC19CBD)
        self.assertEqual(entries[0x12].kind, KIND_OUTSIDE)
        self.assertEqual(entries[0x13].kind, KIND_OUTSIDE)

    def test_shared_memory_and_task_entries(self):
        entries = parse_kernel_op_table(load_kernel())
        create = entries[OP_SHM_CREATE]
        delete = entries[OP_SHM_DELETE]
        attach = entries[OP_SHM_ATTACH]
        self.assertEqual(
            [create.pointer, delete.pointer, attach.pointer],
            [0x1F189175, 0x1F1891E9, 0x1F189219],
        )
        for entry in (create, delete, attach):
            self.assertEqual(entry.kind, KIND_BANK)
            self.assertEqual((entry.far.group, entry.far.bank_index), ("A", 6))
            self.assertTrue(entry.mips16)

        self.assertEqual(entries[OP_TASK_CREATE].pointer, 0x2F009945)
        self.assertEqual(entries[OP_DEBUG_PRINT].kind, KIND_RESIDENT)
        self.assertEqual(entries[OP_DEBUG_PRINT].pointer, 0xBFC08771)


class KernelShmRegistryTests(unittest.TestCase):
    def test_attach_returns_the_pointer_given_to_create(self):
        registry = KernelShmRegistry()
        self.assertEqual(registry.attach(3), 0)
        self.assertEqual(registry.create(3, 0x10001000, 0xF0), 0)
        self.assertEqual(registry.attach(3), 0x10001000)
        self.assertEqual(registry.attach(4), 0)

    def test_delete_frees_the_slot(self):
        registry = KernelShmRegistry()
        registry.create(3, 0x10001000, 0xF0)
        self.assertEqual(registry.delete(3), 0)
        self.assertEqual(registry.attach(3), 0)
        self.assertEqual(registry.delete(3), 0)
        self.assertEqual(registry.create(5, 0x10002000, 0x10), 0)

    def test_ninth_create_fails_and_prints(self):
        registry = KernelShmRegistry()
        for shm_id in range(10, 18):
            self.assertEqual(registry.create(shm_id, 0x10000000 + shm_id, 0x10), 0)

        self.assertEqual(registry.create(18, 0x10000012, 0x10), 0xFFFFFFFF)
        self.assertEqual(registry.debug_prints, 1)
        self.assertEqual(registry.attach(17), 0x10000011)
        self.assertEqual(registry.attach(18), 0)

    def test_duplicate_id_takes_a_second_slot_and_attach_returns_the_first(self):
        registry = KernelShmRegistry()
        registry.create(3, 0x10001000, 0xF0)
        registry.create(3, 0x10002000, 0xF0)
        self.assertEqual(registry.attach(3), 0x10001000)
        registry.delete(3)
        self.assertEqual(registry.attach(3), 0x10002000)

    def test_rejects_the_free_marker_as_an_id(self):
        registry = KernelShmRegistry()
        with self.assertRaises(ValueError):
            registry.create(0xFF, 0x10001000, 0xF0)


if __name__ == "__main__":
    unittest.main()

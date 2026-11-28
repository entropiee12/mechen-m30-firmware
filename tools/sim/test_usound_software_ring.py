import unittest

from usound_format import expand_three_to_four
from usound_software_ring import (
    DIRECT_READY_THRESHOLD,
    DMA_RING_ADDRESS,
    EXPANDED_READY_THRESHOLD,
    RING_CAPACITY,
    SoftwareRing,
    build_dma_start_registers,
)


class UsoundSoftwareRingTests(unittest.TestCase):
    def test_direct_write_advances_and_stores_bytes(self) -> None:
        ring = SoftwareRing()

        self.assertFalse(ring.produce(b"abc", expanded=False))
        self.assertEqual(ring.data[:3], b"abc")
        self.assertEqual(ring.write_offset, 3)
        self.assertEqual(ring.available, 3)

    def test_direct_write_wraps(self) -> None:
        ring = SoftwareRing(write_offset=RING_CAPACITY - 2)

        ring.produce(b"abcd", expanded=False)
        self.assertEqual(ring.data[-2:], b"ab")
        self.assertEqual(ring.data[:2], b"cd")
        self.assertEqual(ring.write_offset, 2)

    def test_expanded_write_uses_three_to_four_layout(self) -> None:
        ring = SoftwareRing()

        ring.produce(bytes((1, 2, 3)), expanded=True)
        self.assertEqual(ring.data[:4], expand_three_to_four(bytes((1, 2, 3))))
        self.assertEqual(ring.available, 4)

    def test_ready_edge_uses_format_specific_threshold(self) -> None:
        direct = SoftwareRing()
        self.assertFalse(direct.produce(bytes(DIRECT_READY_THRESHOLD - 1), expanded=False))
        self.assertTrue(direct.produce(b"x", expanded=False))

        expanded = SoftwareRing()
        source = bytes(EXPANDED_READY_THRESHOLD // 4 * 3)
        self.assertTrue(expanded.produce(source, expanded=True))
        self.assertEqual(expanded.available, EXPANDED_READY_THRESHOLD)

    def test_overrun_sets_available_to_wrapped_write_offset(self) -> None:
        ring = SoftwareRing(write_offset=RING_CAPACITY - 4, available=RING_CAPACITY - 4)

        ring.produce(b"abcdefgh", expanded=False)
        self.assertEqual(ring.write_offset, 4)
        self.assertEqual(ring.available, 4)

    def test_dma_completion_resets_an_empty_fill_and_keeps_clock_bits(self) -> None:
        ring = SoftwareRing(
            available=DIRECT_READY_THRESHOLD,
            ready_signal=1,
        )

        result = ring.complete_dma(clock_control=0xA5, dma_control=0)

        self.assertTrue(result.empty_branch)
        self.assertEqual(ring.available, 0)
        self.assertEqual(ring.ready_signal, 0)
        self.assertEqual(result.clock_control, 0xA5)
        self.assertEqual(result.dma_control, 0x404)

    def test_empty_dma_completion_rearms_the_next_ready_edge(self) -> None:
        ring = SoftwareRing(
            available=DIRECT_READY_THRESHOLD,
            ready_signal=1,
        )

        ring.complete_dma(clock_control=0, dma_control=0)

        self.assertTrue(ring.produce(bytes(DIRECT_READY_THRESHOLD), expanded=False))
        self.assertEqual(ring.ready_signal, 1)

    def test_dma_completion_clears_clock_mode_below_the_observed_threshold(self) -> None:
        ring = SoftwareRing(available=DIRECT_READY_THRESHOLD + 4409)

        result = ring.complete_dma(clock_control=0xA5, dma_control=0)

        self.assertFalse(result.empty_branch)
        self.assertEqual(ring.available, 4409)
        self.assertEqual(result.clock_control, 0xA4)

    def test_dma_completion_preserves_clock_mode_at_the_clear_boundary(self) -> None:
        ring = SoftwareRing(available=DIRECT_READY_THRESHOLD + 4410)

        result = ring.complete_dma(clock_control=0xA5, dma_control=0)

        self.assertEqual(ring.available, 4410)
        self.assertEqual(result.clock_control, 0xA5)

    def test_dma_completion_sets_clock_mode_at_the_high_boundary(self) -> None:
        ring = SoftwareRing(available=DIRECT_READY_THRESHOLD + 9703)

        result = ring.complete_dma(clock_control=0xA5, dma_control=0)

        self.assertEqual(ring.available, 9703)
        self.assertEqual(result.clock_control, 0xA7)

    def test_dma_start_registers_select_the_direct_ring_path(self) -> None:
        registers = build_dma_start_registers(
            0,
            common_control_04=0x10,
            common_control_08=0x20,
        )

        self.assertEqual(registers.address, DMA_RING_ADDRESS)
        self.assertEqual(registers.channel_control, 0x4B03)
        self.assertEqual(registers.size_register, 14112)
        self.assertEqual(registers.common_control_04, 0x414)
        self.assertEqual(registers.common_control_08, 0x424)

    def test_dma_start_registers_select_the_expanded_ring_path(self) -> None:
        registers = build_dma_start_registers(1)

        self.assertEqual(registers.address, DMA_RING_ADDRESS)
        self.assertEqual(registers.channel_control, 0x8B03)
        self.assertEqual(registers.size_register, 7056)


if __name__ == "__main__":
    unittest.main()

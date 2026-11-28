import unittest

from lcd_transfer import (
    COLUMN_ADDRESS_SET,
    MEMORY_ACCESS_CONTROL,
    MEMORY_WRITE,
    RECOVERED_ORIENTATION_VALUES,
    ROW_ADDRESS_SET,
    LcdCommand,
    LcdTransferBoundary,
)
from m30_ui import Framebuffer, rgb565


class LcdTransferBoundaryTests(unittest.TestCase):
    def test_orientation_command_preserves_recovered_raw_values(self):
        transfer = LcdTransferBoundary(Framebuffer(1, 1))

        for value in RECOVERED_ORIENTATION_VALUES:
            transfer.set_orientation_value(value)

        self.assertEqual(
            transfer.commands,
            [
                LcdCommand(MEMORY_ACCESS_CONTROL, (value,))
                for value in RECOVERED_ORIENTATION_VALUES
            ],
        )

    def test_unobserved_orientation_value_is_rejected(self):
        transfer = LcdTransferBoundary(Framebuffer(1, 1))

        with self.assertRaises(ValueError):
            transfer.set_orientation_value(0x00)

    def test_window_commands_and_row_major_rgb565_write(self):
        transfer = LcdTransferBoundary(Framebuffer(4, 3))
        transfer.set_column_window(1, 2)
        transfer.set_row_window(0, 1)
        transfer.begin_memory_write()

        self.assertEqual(
            transfer.write_rgb565((0xF800, 0x07E0, 0x001F, 0xFFFF)),
            4,
        )
        self.assertEqual(
            [command.opcode for command in transfer.commands],
            [COLUMN_ADDRESS_SET, ROW_ADDRESS_SET, MEMORY_WRITE],
        )
        self.assertEqual(
            bytes(transfer.framebuffer.pixels[(0 * 4 + 1) * 3 : (0 * 4 + 1) * 3 + 3]),
            bytes(rgb565(0xF800)),
        )
        self.assertEqual(
            bytes(transfer.framebuffer.pixels[(1 * 4 + 2) * 3 : (1 * 4 + 2) * 3 + 3]),
            bytes(rgb565(0xFFFF)),
        )
        self.assertEqual(transfer.remaining_pixels, 0)

    def test_pixel_stream_can_arrive_in_bounded_chunks(self):
        transfer = LcdTransferBoundary(Framebuffer(3, 1))
        transfer.set_column_window(0, 2)
        transfer.set_row_window(0, 0)
        transfer.begin_memory_write()

        transfer.write_rgb565((0xF800,))
        transfer.write_rgb565((0x07E0, 0x001F))

        self.assertEqual(transfer.remaining_pixels, 0)

    def test_missing_window_and_overflow_fail_closed(self):
        transfer = LcdTransferBoundary(Framebuffer(2, 1))
        with self.assertRaises(ValueError):
            transfer.begin_memory_write()

        transfer.set_column_window(0, 1)
        transfer.set_row_window(0, 0)
        transfer.begin_memory_write()
        with self.assertRaises(ValueError):
            transfer.write_rgb565((0x0000, 0x0001, 0x0002))

    def test_window_outside_host_framebuffer_is_clipped_by_host_pixel_sink(self):
        transfer = LcdTransferBoundary(Framebuffer(2, 1))
        transfer.set_column_window(1, 2)
        transfer.set_row_window(0, 0)
        transfer.begin_memory_write()
        transfer.write_rgb565((0xF800, 0x07E0))

        self.assertEqual(
            bytes(transfer.framebuffer.pixels[3:6]),
            bytes(rgb565(0xF800)),
        )


if __name__ == "__main__":
    unittest.main()

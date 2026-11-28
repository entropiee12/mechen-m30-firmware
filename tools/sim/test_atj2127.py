import unittest

from atj2127 import (
    ADFU_MARKER,
    DMA_CH1_DST,
    DMA_CH1_LEN,
    DMA_CH1_SRC,
    GPIO_A_DAT,
    GPIO_BT_POWER,
    UART_DAT,
    WD_CTL,
    Atj2127Machine,
)


class Atj2127PeripheralTests(unittest.TestCase):
    def test_uart_fifo_round_trip(self):
        machine = Atj2127Machine()
        machine.inject_uart(b"OK")
        self.assertEqual(machine.read32(UART_DAT), ord("O"))
        self.assertEqual(machine.read32(UART_DAT), ord("K"))
        machine.write32(UART_DAT, ord("!"))
        self.assertEqual(bytes(machine.uart_tx), b"!")

    def test_bluetooth_power_gpio(self):
        machine = Atj2127Machine()
        machine.write32(GPIO_A_DAT, GPIO_BT_POWER)
        self.assertTrue(machine.bt_powered)
        machine.write32(GPIO_A_DAT, 0)
        self.assertFalse(machine.bt_powered)

    def test_uart_dma_transfer(self):
        machine = Atj2127Machine()
        source = 0x1000
        machine.memory.load(source, b"ATJ")
        machine.write32(DMA_CH1_SRC, source)
        machine.write32(DMA_CH1_DST, UART_DAT)
        machine.write32(DMA_CH1_LEN, 3)
        self.assertEqual(machine.dma_uart_tx(), b"ATJ")
        self.assertEqual(bytes(machine.uart_tx), b"ATJ")

    def test_adfu_marker_and_watchdog(self):
        machine = Atj2127Machine()
        machine.arm_recovery()
        marker = machine.memory.read(0x00130000, 4)
        self.assertEqual(int.from_bytes(marker, "little"), ADFU_MARKER)
        self.assertTrue(machine.recovery_requested)
        self.assertTrue(machine.reset_requested)
        self.assertEqual(machine.read32(WD_CTL), 0x5F)


if __name__ == "__main__":
    unittest.main()

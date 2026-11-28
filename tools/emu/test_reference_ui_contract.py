import tempfile
import unittest
from pathlib import Path

from reference_ui_contract import inspect


class ReferenceUiContractTests(unittest.TestCase):
    def test_extracts_the_scroll_and_sdimage_contract(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "case/drv/ui").mkdir(parents=True)
            (root / "case/drv/ui/ui_driver.xn").write_text(
                """
BANK_B_5 { bank_b_ui_scroll_string.o(.text .rodata) }
BANK_B_6 { next.o(.text .rodata) }
BANK_B_16 { bank_b_ui_string_sdimage.o(.text .rodata) }
BANK_B_17 { next.o(.text .rodata) }
"""
            )
            (root / "case/drv/ui/bank_b_ui_scroll_string.c").write_text(
                """
bool hori_scroll(void) {
    clear_remain_pixel_sdimage(&cur_region);
    clear_remain_pixel_sdimage(&cur_region);
}
"""
            )
            (root / "case/drv/ui/bank_b_ui_string_sdimage.c").write_text(
                """
void clear_remain_pixel_sdimage(void) {
    read_str_bgpic(display_buffer, &buf_region, CHAR_FILL_SDIMAGE);
    set_lcd_param_hook(&region, LCD_PARAM_SCROLL);
    dma_buffer_data_hook(size, width);
}
"""
            )
            (root / "case/drv/ui/bank_a_ui_show_listbox_2.c").write_text(
                "load_bgpic_sd(id, x, y, mode); disp_mode |= CHAR_FILL_SDIMAGE;\n"
            )

            contract = inspect(root)

        self.assertEqual(contract.scroll_bank, "B05: bank_b_ui_scroll_string.o")
        self.assertEqual(contract.sdimage_bank, "B16: bank_b_ui_string_sdimage.o")
        self.assertEqual(contract.scroll_clear_calls, 2)
        self.assertTrue(contract.tail_restore_reads_background)
        self.assertTrue(contract.tail_restore_sets_lcd_region)
        self.assertTrue(contract.tail_restore_dma)
        self.assertTrue(contract.listbox_loads_background_image)
        self.assertTrue(contract.listbox_selects_sdimage_mode)


if __name__ == "__main__":
    unittest.main()

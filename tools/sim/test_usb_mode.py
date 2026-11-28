import unittest

from usb_mode import USB_MODE_CONFIG_ITEM, UsbMode, select_usb_mode


class UsbModeTests(unittest.TestCase):
    def test_factory_default_routes_offline_cable_to_mass_storage(self):
        self.assertEqual(select_usb_mode(0, online_device=False), UsbMode.MASS_STORAGE)

    def test_online_device_routes_to_mtp_when_audio_flag_is_off(self):
        self.assertEqual(select_usb_mode(0, online_device=True), UsbMode.MTP)

    def test_audio_flag_takes_precedence_over_storage_state(self):
        self.assertEqual(select_usb_mode(1, online_device=False), UsbMode.USB_AUDIO)
        self.assertEqual(select_usb_mode(1, online_device=True), UsbMode.USB_AUDIO)

    def test_only_exact_one_selects_usb_audio(self):
        self.assertEqual(select_usb_mode(2, online_device=False), UsbMode.MASS_STORAGE)

    def test_config_item_identity_is_recorded(self):
        self.assertEqual(USB_MODE_CONFIG_ITEM, 0x116)


if __name__ == "__main__":
    unittest.main()

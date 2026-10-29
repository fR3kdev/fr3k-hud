#!/usr/bin/env python3
"""Unit checks for the emulator driver itself; run without a device."""
import unittest
from voice_setup_matrix import parse_size, center, png_size, swipe_coordinates
import struct


class DriverTest(unittest.TestCase):
    def test_override_wins_over_physical_size(self):
        self.assertEqual((1080, 2400), parse_size('Physical size: 320x640\nOverride size: 1080x2400\n'))

    def test_physical_size_without_override(self):
        self.assertEqual((720, 1600), parse_size('Physical size: 720x1600\n'))

    def test_unknown_size_fails_instead_of_guessing(self):
        with self.assertRaises(ValueError):
            parse_size('adb error')

    def test_screenshot_dimensions_are_checked_separately(self):
        data = b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', 1080, 2400)
        self.assertEqual((1080, 2400), png_size(data))

    def test_screenshot_failure_is_not_a_valid_resolution(self):
        with self.assertRaises(ValueError):
            png_size(b'adb: device offline')

    def test_swipe_starts_inside_scroll_container_below_fixed_header(self):
        x, start, end = swipe_coordinates((32, 600, 688, 1500), down=True)
        self.assertTrue(32 < x < 688)
        self.assertTrue(600 < start < end < 1500)

    def test_taps_visible_node_center(self):
        self.assertEqual((150, 230), center('[100,200][200,260]'))


if __name__ == '__main__':
    unittest.main()

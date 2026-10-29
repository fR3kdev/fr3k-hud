#!/usr/bin/env python3
"""Real ADB UI checks, persistent JSON evidence, no swallowed subprocess errors."""
import argparse
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get('ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
PKG = 'com.mcpintelligence.fr3k.hud'


def parse_size(output):
    values = re.findall(r'(?:Physical|Override) size:\s*(\d+)x(\d+)', output)
    if not values:
        raise ValueError('ADB did not report screen dimensions')
    return tuple(map(int, values[-1]))


def png_size(data):
    if len(data) < 24 or data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
        raise ValueError('Screenshot is not a PNG')
    return struct.unpack('>II', data[16:24])


def center(bounds):
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
    if not match:
        raise ValueError('Missing UI bounds: ' + bounds)
    x1, y1, x2, y2 = map(int, match.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def swipe_coordinates(bounds, down=False):
    x1, y1, x2, y2 = bounds
    start, end = (0.2, 0.85) if down else (0.85, 0.2)
    return (x1 + max(4, (x2 - x1) // 8), int(y1 + (y2 - y1) * start), int(y1 + (y2 - y1) * end))


class Device:
    def __init__(self, evidence):
        self.evidence = Path(evidence)
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.sequence = 0
        self.scroll_bounds = None

    def adb(self, *args):
        result = subprocess.run([ADB, '-s', 'emulator-5554', *map(str, args)], capture_output=True, timeout=90)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace')

    def size(self):
        return parse_size(self.adb('shell', 'wm', 'size'))

    def dump(self):
        self.sequence += 1
        remote = f'/sdcard/voice-matrix-{self.sequence}.xml'
        status = self.adb('shell', 'uiautomator', 'dump', remote)
        if 'dumped to:' not in status:
            raise RuntimeError('UI hierarchy capture failed: ' + status.strip())
        raw = self.adb('shell', 'cat', remote)
        self.adb('shell', 'rm', remote)
        root = ET.fromstring(raw[raw.index('<?xml'):])
        nodes = list(root.iter('node'))
        containers = [tuple(map(int, re.findall(r'\d+', n.get('bounds', '')))) for n in nodes if n.get('scrollable') == 'true']
        containers = [bounds for bounds in containers if len(bounds) == 4]
        self.scroll_bounds = max(containers, key=lambda b: (b[2] - b[0]) * (b[3] - b[1])) if containers else None
        if len(nodes) < 3:
            raise RuntimeError('Suspiciously empty UI dump')
        (self.evidence / f'ui-{self.sequence:04}.xml').write_text(raw)
        return nodes

    @staticmethod
    def label(node):
        return node.get('text', '') + ' ' + node.get('content-desc', '')

    def swipe(self, down=False):
        w, h = self.size()
        x, start, end = swipe_coordinates(self.scroll_bounds or (0, 0, w, h), down)
        self.adb('shell', 'input', 'swipe', x, start, x, end, 350)
        time.sleep(0.6)

    def find(self, text, scroll=True):
        for attempt in range(11 if scroll else 1):
            for node in self.dump():
                if text.casefold() in self.label(node).casefold():
                    x, y = center(node.get('bounds', ''))
                    w, h = self.size()
                    if 0 < x < w and 0 < y < h:
                        return node
            if scroll:
                self.swipe()
        raise AssertionError(f'Control not reachable: {text}')

    def top(self):
        for _ in range(7):
            self.swipe(down=True)

    def tap(self, text):
        node = self.find(text)
        self.adb('shell', 'input', 'tap', *center(node.get('bounds', '')))
        time.sleep(1)

    def screenshot(self, name):
        result = subprocess.run([ADB, '-s', 'emulator-5554', 'exec-out', 'screencap', '-p'], capture_output=True, timeout=60, check=True)
        actual = png_size(result.stdout)
        expected = self.size()
        if actual != expected:
            raise AssertionError(f'Screenshot size {actual} does not match effective size {expected}')
        (self.evidence / (name + '.png')).write_bytes(result.stdout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    device = Device(args.evidence)
    results = []
    try:
        for resolution, density in [('720x1600', 320), ('1080x2400', 420), ('1440x3120', 560)]:
            entry = {'resolution': resolution, 'density': density, 'checks': [], 'status': 'FAIL'}
            try:
                device.adb('shell', 'wm', 'size', resolution)
                device.adb('shell', 'wm', 'density', density)
                time.sleep(2)
                assert device.size() == tuple(map(int, resolution.split('x')))
                device.adb('shell', 'am', 'force-stop', PKG)
                assert 'Success' in device.adb('shell', 'pm', 'clear', PKG)
                device.adb('shell', 'pm', 'grant', PKG, 'android.permission.RECORD_AUDIO')
                device.adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', 1)
                time.sleep(8)
                device.find('CHECK YOUR VOICE')
                entry['checks'].append('voice-first launch')
                device.screenshot(resolution + '-launch')
                device.tap('SAVE AND HEAR IT')
                # Start scanning from the top after setup changes its height.
                for _ in range(4):
                    device.swipe(down=True)
                device.screenshot(resolution + '-provider')
                for label in ['Groq', 'OpenCode Go', 'OpenRouter']:
                    device.find(label)
                    entry['checks'].append(label + ' reachable')
                for label, guidance in [('○ OpenCode Go', 'subscribe to Go'), ('○ OpenRouter', 'zero-price model'), ('○ Groq', 'Free tier has rate')]:
                    device.top()
                    device.tap(label)
                    device.find(guidance)
                    device.find('Create API key')
                    entry['checks'].append(label[2:] + ' selected; matching instructions and key link shown')
                device.tap('API key (masked)')
                device.adb('shell', 'input', 'text', 'fr3k-invalid-ui-test')
                device.adb('shell', 'input', 'keyevent', 4)
                device.tap('Validate key and list models')
                time.sleep(4)
                device.find('Provider HTTP 401')
                entry['checks'].append('invalid Groq key rejected via actual HTTPS')
                device.tap('MUTE MICROPHONE')
                device.find('Type a message')
                device.find('UNMUTE MICROPHONE')
                entry['checks'].append('mute exposes typed input')
                device.screenshot(resolution + '-muted')
                device.tap('UNMUTE MICROPHONE')
                entry['checks'].append('unmute')
                device.tap('HELP INSTALL HERMES IN TERMUX')
                device.find('OPTIONAL · HERMES IN TERMUX')
                device.find('bridge cannot securely transfer')
                device.find('Termux bridge unavailable')
                visible = device.dump()
                assert not any('OPEN VERIFIED HERMES' in device.label(node) for node in visible)
                entry['checks'].append('optional Hermes offer; unavailable bridge and secure-key boundary; verified action hidden')
                device.screenshot(resolution + '-hermes')
                entry['status'] = 'PASS'
            except Exception as error:
                entry['error'] = str(error)
            results.append(entry)
            (device.evidence / 'results.json').write_text(json.dumps(results, indent=2))
            print(json.dumps(entry), flush=True)
    finally:
        device.adb('shell', 'wm', 'size', 'reset')
        device.adb('shell', 'wm', 'density', 'reset')
    return 0 if all(r['status'] == 'PASS' for r in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())

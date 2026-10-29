#!/usr/bin/env python3
"""Exercise actual companion voice UI; no mock provider answers or saved credentials."""
import argparse
import json
import time
from voice_setup_matrix import Device, center

PKG = 'com.fr3k.blackwave.debug'


def exact(device, text):
    for _ in range(8):
        for node in device.dump():
            if node.get('text', '').casefold() == text.casefold():
                device.adb('shell', 'input', 'tap', *center(node.get('bounds', '')))
                time.sleep(1)
                return
        device.swipe()
    raise AssertionError('Exact button unavailable: ' + text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    d = Device(args.evidence)
    results = []
    try:
        for size, density in [('720x1600', 320), ('1080x2400', 420), ('1440x3120', 560)]:
            item = {'resolution': size, 'density': density, 'checks': [], 'status': 'FAIL'}
            try:
                d.adb('shell', 'am', 'force-stop', PKG)
                d.adb('shell', 'wm', 'size', size)
                d.adb('shell', 'wm', 'density', density)
                assert d.size() == tuple(map(int, size.split('x')))
                d.adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', 1)
                time.sleep(12)
                d.find('VOICE · SHARED HUD AGENT')
                item['checks'].append('voice card on opening Fleet page')
                for _ in range(12):
                    if any('Welcome spoken by HUD' in d.label(n) for n in d.dump()):
                        break
                    time.sleep(2)
                else:
                    raise AssertionError('Shared HUD speech completion not received while welcome visible')
                item['checks'].append('cross-app greeting completed via actual HUD TTS callback')
                d.screenshot(size + '-welcome')
                exact(d, 'Start voice')
                time.sleep(2)
                d.find('No key for selected HUD provider')
                item['checks'].append('start reports missing selected provider honestly; no false listening')
                d.find('Mute microphone')
                switches = [node for node in d.dump() if node.get('checkable') == 'true']
                assert switches, 'Mic mute switch is not reachable'
                switch = switches[0]
                d.adb('shell', 'input', 'tap', *center(switch.get('bounds', '')))
                time.sleep(1)
                assert any(node.get('checked') == 'true' for node in d.dump() if node.get('checkable') == 'true')
                item['checks'].append('mic mute switch on')
                d.tap('Message the shared HUD agent')
                d.adb('shell', 'input', 'text', 'Help%20with%20setup')
                d.adb('shell', 'input', 'keyevent', 4)
                exact(d, 'Send · spoken reply')
                time.sleep(2)
                d.top()
                d.find('No key for selected HUD provider')
                item['checks'].append('typed-send reaches real HUD readiness; missing-key failure visible')
                d.screenshot(size + '-typed-missing-key')
                item['status'] = 'PASS'
            except Exception as error:
                item['error'] = str(error)
            results.append(item)
            (d.evidence / 'results.json').write_text(json.dumps(results, indent=2))
            print(json.dumps(item), flush=True)
    finally:
        d.adb('shell', 'wm', 'size', 'reset')
        d.adb('shell', 'wm', 'density', 'reset')
    return 0 if len(results) == 3 and all(item['status'] == 'PASS' for item in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())

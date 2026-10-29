#!/usr/bin/env python3
"""Drive the real Xiaozhi panel against the live endpoint; no mocks, no saved codes.

Asserts that the operator-visible result of a refused handshake is the server's own
binding instruction, not a generic local error.
"""
import argparse
import json
import time

from voice_setup_matrix import Device, center

PKG = 'com.mcpintelligence.fr3k.hud'
SETTINGS = 'com.mcpintelligence.fr3k.hud/com.mcpintelligence.fr3k.ui.settings.SettingsActivity'
LOCAL_FALLBACKS = ('Voice channel failed', 'Activation pending on the server')


def scroll_to(device, text, rounds=8):
    for _ in range(rounds):
        try:
            return device.find(text, scroll=False)
        except AssertionError:
            pass
        device.swipe()
    raise AssertionError('Never became visible: ' + text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    device = Device(args.evidence)
    original_dump = device.dump

    def dump_with_retry():
        # A cold Compose activity can answer uiautomator with an empty/failed dump once.
        for attempt in range(4):
            try:
                return original_dump()
            except RuntimeError:
                if attempt == 3:
                    raise
                time.sleep(2)
                device.adb('shell', 'input', 'keyevent', '224')
                device.adb('shell', 'wm', 'dismiss-keyguard')

    device.dump = dump_with_retry
    item = {'checks': [], 'status': 'FAIL'}
    try:
        device.adb('shell', 'input', 'keyevent', '224')
        device.adb('shell', 'wm', 'dismiss-keyguard')
        device.adb('shell', 'am', 'force-stop', PKG)
        device.adb('shell', 'pm', 'grant', PKG, 'android.permission.RECORD_AUDIO')
        device.adb('shell', 'am', 'start', '-n', SETTINGS)
        time.sleep(4)
        scroll_to(device, 'voice assistant')
        item['checks'].append('voice assistant panel reachable in Settings')
        device.tap('XIAOZHI')
        time.sleep(2)
        scroll_to(device, 'Xiaozhi voice')
        device.find('https://voice.fr3k.dev/xiaozhi/ota/')
        item['checks'].append('live endpoint shown in the panel')
        device.screenshot('xiaozhi-panel')
        box = [node for node in device.dump() if node.get('checkable') == 'true']
        assert box, 'Consent checkbox is not reachable'
        device.adb('shell', 'input', 'tap', *center(box[0].get('bounds', '')))
        time.sleep(1)
        device.tap('CONNECT')
        status = None
        for _ in range(20):
            time.sleep(2)
            nodes = device.dump()
            texts = [node.get('text', '') for node in nodes]
            joined = ' '.join(texts)
            if 'Xiaozhi session: ACTIVATION PENDING' in joined:
                status = joined
                break
        assert status, 'Panel never reached ACTIVATION PENDING: ' + ' '.join(
            node.get('text', '') for node in device.dump())
        item['checks'].append('rejected handshake reported as activation pending')
        codes = [node.get('text', '') for node in device.dump() if node.get('text', '').startswith('Activation code:')]
        assert codes, 'Activation code from the live OTA response is not shown'
        item['activation_code_label'] = codes[0]
        assert any(ch.isdigit() for ch in codes[0].split(':', 1)[1]), 'Activation code is not numeric'
        messages = [node.get('text', '') for node in device.dump() if node.get('text', '')]
        server_text = [m for m in messages if m not in LOCAL_FALLBACKS and
                       ('控制面板' in m or 'bind' in m.lower() or '绑定' in m)]
        assert server_text, 'Server refusal text was not surfaced verbatim; saw: ' + json.dumps(messages)
        item['server_notice'] = server_text[0]
        item['checks'].append('server refusal text shown verbatim, not a local fallback')
        assert not any(m.startswith('Voice channel failed') for m in messages), 'generic error leaked'
        device.screenshot('xiaozhi-activation')
        device.tap('DISCONNECT')
        time.sleep(2)
        item['status'] = 'PASS'
    except Exception as error:
        item['error'] = str(error)
        try:
            device.screenshot('xiaozhi-failure')
        except Exception:
            pass
    (device.evidence / 'results.json').write_text(json.dumps(item, indent=2))
    print(json.dumps(item, ensure_ascii=False), flush=True)
    return 0 if item['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())

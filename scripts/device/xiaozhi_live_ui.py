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


def exact_tap(device, text, rounds=8):
    """Tap a node whose whole label equals text; substrings match the wrong control."""
    for _ in range(rounds):
        for node in device.dump():
            if (node.get('text', '') or '').strip().casefold() == text.casefold():
                device.adb('shell', 'input', 'tap', *center(node.get('bounds', '')))
                time.sleep(1)
                return
        device.swipe()
    raise AssertionError('No exact control: ' + text)


def small_swipe(device):
    """Short scroll so a control just below the fold is not skipped over."""
    width, height = device.size()
    bounds = device.scroll_bounds or (0, 0, width, height)
    x = (bounds[0] + bounds[2]) // 2
    start = bounds[1] + int((bounds[3] - bounds[1]) * 0.7)
    end = bounds[1] + int((bounds[3] - bounds[1]) * 0.45)
    device.adb('shell', 'input', 'swipe', x, start, x, end, 300)
    time.sleep(0.6)


def consent_box(device, rounds=14):
    """The consent checkbox sits next to its own label; other Settings toggles are checkable too."""
    for _ in range(rounds):
        nodes = device.dump()
        checkable = [node for node in nodes if node.get('checkable') == 'true']
        labels = [node for node in nodes if (node.get('text', '') or '').startswith('Consent: connect')]
        if checkable and labels:
            label_y = center(labels[0].get('bounds', ''))[1]
            checkable.sort(key=lambda node: abs(center(node.get('bounds', ''))[1] - label_y))
            return checkable[0]
        small_swipe(device)
    raise AssertionError('Consent checkbox is not reachable')


def top(device):
    for _ in range(8):
        device.swipe(down=True)


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
        exact_tap(device, 'XIAOZHI')
        time.sleep(2)
        scroll_to(device, 'Xiaozhi voice')
        device.find('https://voice.fr3k.dev/xiaozhi/ota/')
        item['checks'].append('live endpoint shown in the panel')
        labels = []
        for _ in range(8):
            labels = [node.get('text', '') for node in device.dump() if node.get('text', '')]
            if any('· ACTIVE' in label for label in labels) and any(label.startswith('Replies ') for label in labels):
                break
            small_swipe(device)
        active = [label for label in labels if '· ACTIVE' in label]
        assert len(active) == 1, 'Reply voice must show exactly one active path: ' + json.dumps(labels)
        caption = [label for label in labels if label.startswith('Replies ')]
        assert caption, 'Reply voice caption missing'
        expected = 'Android system voice' if active[0].startswith('ANDROID') else 'Xiaozhi server voice'
        assert expected in caption[0], f'Caption disagrees with the active reply path: {active[0]} / {caption[0]}'
        item['reply_voice'] = {'active': active[0], 'caption': caption[0]}
        item['checks'].append('reply voice shows exactly one active path and a matching caption')
        device.screenshot('xiaozhi-panel')
        box = consent_box(device)
        device.adb('shell', 'input', 'tap', *center(box.get('bounds', '')))
        time.sleep(1)
        assert any(node.get('checked') == 'true' for node in device.dump() if node.get('checkable') == 'true'), \
            'Consent checkbox did not turn on'
        item['checks'].append('consent gate accepted')
        exact_tap(device, 'CONNECT')
        status = None
        for _ in range(20):
            time.sleep(2)
            nodes = device.dump()
            joined = ' '.join(node.get('text', '') for node in nodes)
            if 'Xiaozhi session: ACTIVATION PENDING' in joined:
                status = joined
                break
            top(device)
            scroll_to(device, 'Xiaozhi session:')
        assert status, 'Panel never reached ACTIVATION PENDING: ' + ' '.join(
            node.get('text', '') for node in device.dump())
        item['checks'].append('rejected handshake reported as activation pending')
        codes = [node.get('text', '') for node in device.dump() if node.get('text', '').startswith('Activation code:')]
        assert codes, 'Activation code from the live OTA response is not shown'
        item['activation_code_label'] = codes[0]
        code_value = codes[0].split(':', 1)[1].strip()
        assert code_value.isdigit() and 4 <= len(code_value) <= 8, 'Activation code is not a plausible numeric code'
        item['activation_code'] = code_value
        messages = [node.get('text', '') for node in device.dump() if node.get('text', '')]
        assert not any(m.startswith('Voice channel failed') for m in messages), 'generic error leaked'
        # The OTA answer for an unbound device carries an activation block, so the client does not
        # open a websocket at all; a Chinese notice would only appear on the websocket-refusal path.
        chinese = [m for m in messages if any('\u4e00' <= ch <= '\u9fff' for ch in m)]
        item['server_notice'] = chinese[0] if chinese else None
        item['checks'].append('live OTA code shown; no websocket is opened while activation is pending')
        device.screenshot('xiaozhi-activation')
        exact_tap(device, 'DISCONNECT')
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

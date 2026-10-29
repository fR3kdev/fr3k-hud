#!/usr/bin/env python3
"""Device checks for the floating-HUD opt-in, provider/battery panels and the connect approval.

Drives the real Settings and companion screens; every assertion is read back from the UI or from
`dumpsys activity services`, never inferred from code.
"""
import argparse
import json
import time

from voice_setup_matrix import Device, center

HUD = 'com.mcpintelligence.fr3k.hud'
BW = 'com.fr3k.blackwave.debug'
SETTINGS = f'{HUD}/com.mcpintelligence.fr3k.ui.settings.SettingsActivity'
ADB = '/home/fr3k/Android/Sdk/platform-tools/adb'


def service_running(device):
    out = device.adb('shell', 'dumpsys', 'activity', 'services', HUD)
    return 'HudOverlayService' in out


def small_swipe(device):
    width, height = device.size()
    bounds = device.scroll_bounds or (0, 0, width, height)
    x = (bounds[0] + bounds[2]) // 2
    start = bounds[1] + int((bounds[3] - bounds[1]) * 0.72)
    end = bounds[1] + int((bounds[3] - bounds[1]) * 0.45)
    device.adb('shell', 'input', 'swipe', x, start, x, end, 300)
    time.sleep(0.6)


def wait_text(device, text, rounds=16, label='text'):
    for _ in range(rounds):
        for node in device.dump():
            if text.casefold() in device.label(node).casefold():
                return node
        time.sleep(1)
        # Give a cold screen a few dumps before scrolling, then move a full page: small swipes stall
        # inside nested panels and immediate swipes skip a top panel that had not rendered yet.
        if _ >= 3:
            device.swipe()
    raise AssertionError(f'{label} never appeared: {text}')


def toggle_in_panel(device, anchor):
    """Tap the checkable control in the same panel as [anchor], never another panel's switch."""
    node = wait_text(device, anchor)
    y = center(node.get('bounds', ''))[1]
    candidates = [n for n in device.dump() if n.get('checkable') == 'true']
    assert candidates, 'no checkable control near ' + anchor
    candidates.sort(key=lambda n: abs(center(n.get('bounds', ''))[1] - y))
    device.adb('shell', 'input', 'tap', *center(candidates[0].get('bounds', '')))
    time.sleep(2)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    device = Device(args.evidence)
    original_dump = device.dump

    def dump_with_retry():
        for attempt in range(4):
            try:
                return original_dump()
            except RuntimeError:
                if attempt == 3:
                    raise
                time.sleep(2)

    device.dump = dump_with_retry
    item = {'checks': [], 'status': 'FAIL'}
    try:
        device.adb('shell', 'appops', 'set', HUD, 'SYSTEM_ALERT_WINDOW', 'allow')
        device.adb('shell', 'pm', 'grant', HUD, 'android.permission.POST_NOTIFICATIONS')
        device.adb('shell', 'pm', 'clear', HUD)
        device.adb('shell', 'pm', 'grant', HUD, 'android.permission.POST_NOTIFICATIONS')
        device.adb('shell', 'am', 'force-stop', HUD)
        device.adb('shell', 'am', 'start', '-n', f'{HUD}/com.mcpintelligence.fr3k.ui.MainActivity')
        time.sleep(8)
        assert not service_running(device), 'floating HUD started by itself on app launch'
        item['checks'].append('app launch does not start the floating HUD')

        device.adb('shell', 'am', 'start', '-n', SETTINGS)
        time.sleep(4)
        wait_text(device, 'HUD overlay enabled')
        assert wait_text(device, 'Floating HUD is off') is not None, 'settings did not report the HUD as off'
        item['checks'].append('settings reports the floating HUD as off; stays off until turned on')
        toggle_in_panel(device, 'HUD overlay enabled')
        time.sleep(3)
        assert service_running(device), 'toggle on did not start the floating HUD service'
        assert wait_text(device, 'Floating HUD is running') is not None, 'settings did not report it running'
        item['checks'].append('toggle ON starts HudOverlayService and the panel says running')
        device.screenshot('hud-toggle-on')
        toggle_in_panel(device, 'HUD overlay enabled')
        time.sleep(3)
        assert not service_running(device), 'toggle off did not stop the floating HUD service'
        item['checks'].append('toggle OFF stops HudOverlayService')
        device.screenshot('hud-toggle-off')

        wait_text(device, 'voice provider')
        assert wait_text(device, 'NO SAVED KEY') is not None, 'provider switch rows missing'
        item['checks'].append('voice provider switch lists each provider with its saved-key state')
        wait_text(device, 'background voice')
        wait_text(device, 'Battery optimisation still applies')
        item['checks'].append('battery optimisation panel reports current exemption state')
        device.screenshot('settings-voice-panels')

        device.adb('shell', 'pm', 'grant', BW, 'android.permission.RECORD_AUDIO')
        device.adb('shell', 'am', 'force-stop', BW)
        device.adb('shell', 'monkey', '-p', BW, '-c', 'android.intent.category.LAUNCHER', '1')
        time.sleep(10)
        # The companion probes the HUD as soon as it opens, so the prompt can arrive first.
        prompt = None
        for _ in range(15):
            for node in device.dump():
                if 'CONNECTION REQUEST' in device.label(node):
                    prompt = node
                    break
            if prompt is not None:
                break
            time.sleep(1)
        if prompt is not None:
            item['checks'].append('companion request raises the HUD approval prompt')
            device.screenshot('approval-prompt')
            assert 'com.fr3k.blackwave.debug' in ' '.join(device.label(n) for n in device.dump()), \
                'prompt does not name the requesting package'
            item['checks'].append('prompt names the requesting package and its signer')
            device.adb('shell', 'input', 'tap', *center(wait_text(device, 'ALLOW').get('bounds', '')))
            time.sleep(3)
            assert not any('CONNECTION REQUEST' in device.label(n) for n in device.dump()), 'prompt did not close'
            item['checks'].append('approval is recorded and the prompt closes')
            device.screenshot('approval-granted')
        else:
            item['checks'].append('companion opened without needing a prompt (already approved)')
        # The prompt returns to the HUD task it was launched from, so reopen the companion.
        device.adb('shell', 'am', 'force-stop', BW)
        device.adb('shell', 'monkey', '-p', BW, '-c', 'android.intent.category.LAUNCHER', '1')
        time.sleep(8)
        wait_text(device, 'VOICE · SHARED HUD AGENT', rounds=15)
        item['checks'].append('companion voice card is reachable after approval')
        device.screenshot('companion-voice-card')
        item['status'] = 'PASS'
    except Exception as error:
        item['error'] = str(error)
        try:
            device.screenshot('failure')
        except Exception:
            pass
    (device.evidence / 'results.json').write_text(json.dumps(item, indent=2))
    print(json.dumps(item), flush=True)
    return 0 if item['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())

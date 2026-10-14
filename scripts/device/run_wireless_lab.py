#!/usr/bin/env python3
"""Run the real wireless-debugging acceptance, including the live pairing sheet.

`WirelessAndSelectedAppsDeviceTest` has two halves with two different preconditions, and it says so itself:

  * `realOnDevicePairingStartsVerifiedShellAndReconnects` needs Android's **live pairing-code sheet** open —
    it takes `pairingCode`, `pairingPort` and `connectionPort` as instrumentation arguments and refuses with
    *"Host must open Android's live pairing-code sheet"* otherwise.
  * `selectedNonFixtureAppGetsExecutableControlsAndCompleteGuides` needs HUD's privileged service started
    **from the host while the HUD process is alive**, because instrumentation cannot start it.

This script supplies both honestly: it drives the emulator's own Settings UI to open the pairing sheet (the
host's job), reads the code and ports off the screen, runs both halves, and records what it observed. It
never invents a code, and a half that cannot run is reported as not run rather than as a pass.

Usage:
  python3 scripts/device/run_wireless_lab.py --serial emulator-5582 \
      --log docs/design/evidence/wireless-lab.json [--pairing-code 123456 --pairing-port 39xxx]
"""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

HUD = 'com.mcpintelligence.fr3k.hud'
RUNNER = HUD + '.test/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'com.mcpintelligence.fr3k.ui.WirelessAndSelectedAppsDeviceTest'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--pairing-code', default=None, help='skip driving the sheet and use this code')
    parser.add_argument('--pairing-port', default=None)
    parser.add_argument('--connection-port', default=None)
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]

    def sh(cmd, timeout=120):
        return subprocess.run(adb + ['shell', cmd], capture_output=True, text=True, timeout=timeout).stdout

    results, detail = {}, {}

    def record(name, ok, note):
        results[name] = {'ok': bool(ok), 'note': str(note)[:300]}
        print('%-42s %s  %s' % (name, 'PASS' if ok else 'SKIP/FAIL', str(note)[:140]), flush=True)
        return bool(ok)

    def dump(tag):
        for _ in range(3):
            sh('uiautomator dump /sdcard/%s.xml >/dev/null 2>&1' % tag)
            try:
                return ET.fromstring(sh('cat /sdcard/%s.xml' % tag))
            except ET.ParseError:
                time.sleep(0.8)
        return None

    def taps_on(tree, needle):
        """Centre of the first node whose text contains needle."""
        if tree is None:
            return None
        for node in tree.iter('node'):
            if needle.lower() in (node.get('text') or '').lower():
                bounds = [int(v) for v in re.findall(r'\d+', node.get('bounds') or '')]
                if len(bounds) == 4:
                    return (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        return None

    def screen_text(tree):
        return '\n'.join((n.get('text') or '') for n in tree.iter('node')) if tree is not None else ''

    # ------------------------------------------------------------ wireless debugging on?
    sh('svc wifi enable')
    time.sleep(3)
    enabled = sh('settings get global adb_wifi_enabled').strip()
    record('wireless debugging setting enabled', enabled == '1', 'adb_wifi_enabled=%s' % enabled)

    code, pair_port, conn_port = args.pairing_code, args.pairing_port, args.connection_port

    def poll(pattern, tag, tries=12, interval=1.5):
        """Dump until `pattern` appears in the screen text, so a slow screen is waited for, not missed."""
        for attempt in range(tries):
            text = screen_text(dump('%s-%d' % (tag, attempt)))
            match = re.search(pattern, text)
            if match:
                return match, text
            time.sleep(interval)
        return None, screen_text(dump('%s-last' % tag))

    if not (code and pair_port and conn_port):
        # The wireless-debugging screen is a SystemUI activity; driving it directly is the same thing an
        # operator does from Developer options, without depending on a long list's scroll position.
        sh('am start -n com.android.systemui/.wifi.WifiDebuggingActivity')
        time.sleep(3)
        text = screen_text(dump('wl-open'))
        if 'checked="true"' not in sh('cat /sdcard/wl-open.xml'):
            switch = None
            tree = dump('wl-switch')
            if tree is not None:
                for node in tree.iter('node'):
                    if node.get('checkable') == 'true' and node.get('class', '').endswith('Switch'):
                        bounds = [int(v) for v in re.findall(r'\d+', node.get('bounds') or '')]
                        if len(bounds) == 4:
                            switch = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
                            break
            if switch:
                sh('input tap %d %d' % switch)
        # Android's own "allow wireless debugging on this network?" confirmation can appear at any point.
        allow, allow_text = poll(r'\bALLOW\b', 'wl-allow', tries=8)
        if allow:
            tree = dump('wl-allow-tap')
            point = taps_on(tree, 'ALLOW')
            if point:
                sh('input tap %d %d' % point)
        match, main_text = poll(r'(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})', 'wl-main')
        conn_port = conn_port or (match.group(2) if match else None)
        pair = taps_on(dump('wl-pair'), 'Pair device with pairing code')
        if pair:
            sh('input tap %d %d' % pair)
        code_match, sheet_text = poll(r'\b(\d{6})\b', 'wl-sheet')
        port_match = re.search(r'(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})', sheet_text)
        code = code or (code_match.group(1) if code_match else None)
        pair_port = pair_port or (port_match.group(2) if port_match else None)
        detail['pairing_sheet'] = {'code_seen': bool(code_match), 'port_seen': bool(port_match),
                                   'screen': sheet_text[:200]}

    record('live pairing code and ports available', bool(code and pair_port and conn_port),
           'code=%s pairingPort=%s connectionPort=%s' % (bool(code), pair_port, conn_port))

    # ------------------------------------------------------------ half 1: real pairing
    if code and pair_port and conn_port:
        sh('am force-stop %s' % HUD)
        out = sh('am instrument -w -e class %s#realOnDevicePairingStartsVerifiedShellAndReconnects '
                 '-e pairingCode %s -e pairingPort %s -e connectionPort %s %s'
                 % (CLASS, code, pair_port, conn_port, RUNNER), timeout=420)
        evidence = sh('run-as %s cat files/ui-audit/wireless.txt' % HUD).strip()
        detail['wireless_evidence'] = evidence[:300]
        record('real Android pairing → UID2000 SHELL → reconnect',
               'OK (1 test)' in out and 'pairing PASS' in evidence,
               evidence.splitlines()[0] if evidence else out.strip()[-160:])
    else:
        record('real Android pairing → UID2000 SHELL → reconnect', False,
               'not run: no live pairing sheet')

    # ------------------------------------------------------------ half 2: selected real app
    # The service must be started from the host while the HUD process is alive.
    sh('am force-stop %s' % HUD)
    proc = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
                                   '%s#selectedNonFixtureAppGetsExecutableControlsAndCompleteGuides' % CLASS,
                                   RUNNER], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for _ in range(40):
        time.sleep(0.5)
        if HUD in sh('ps -A'):
            break
    apk = sh('pm path %s' % HUD).strip().splitlines()[0].replace('package:', '')
    service = subprocess.Popen(adb + ['shell', "CLASSPATH='%s' app_process /system/bin "
                                              'com.mcpintelligence.fr3k.privileged.HudPrivilegedMain' % apk],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        out = proc.communicate(timeout=300)[0] or ''
    except subprocess.TimeoutExpired:
        proc.kill()
        out = 'TIMEOUT'
    service.terminate()
    try:
        service_output = (service.communicate(timeout=5)[0] or '').strip()
    except subprocess.TimeoutExpired:
        service.kill()
        service_output = ''
    detail['privileged_bootstrap'] = service_output.splitlines()[0][:120] if service_output else 'no output'
    record('selected real app: controls, guides, real app-op write and inverse', 'OK (1 test)' in out,
           'service=%s' % (detail['privileged_bootstrap'] or 'n/a'))

    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text(json.dumps({'results': results, 'detail': detail}, indent=2) + '\n')
    failed = [k for k, v in results.items() if not v['ok']]
    print('WIRELESS_LAB_RESULT', 'PASS' if not failed else 'INCOMPLETE ' + ','.join(failed), flush=True)
    return 0 if not failed else 1


if __name__ == '__main__':
    sys.exit(main())

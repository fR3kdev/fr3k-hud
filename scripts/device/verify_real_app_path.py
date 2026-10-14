#!/usr/bin/env python3
"""Verify the real third-party-app path, end to end, on the product surface.

Everything here is what an operator does: choose an installed app that is NOT a HUD fixture, press the
product's own MAP button, then read the result back over the app's localhost capability surface (MCP).
An accessibility service cannot be hosted inside an instrumentation process, so the map is produced by the
normally-running HUD and verified from outside it.

The mapped target must be a real **user-installed** app: `BridgeGuard` refuses Android system packages as
automation targets, so a system app such as `com.android.deskclock` can be selected and inspected but not
mapped — that refusal is a real boundary, not a failure. System-app selection is covered separately by
`appcontrol/RealAppSelectionDeviceTest`.

Usage:
  python3 scripts/device/verify_real_app_path.py --serial emulator-5582 \
      --log docs/design/evidence/real-app-path.json [--package com.termux] [--keep-selected]
"""
import argparse
import http.client
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

HUD = 'com.mcpintelligence.fr3k.hud'
INITIAL_RESULT = 'Choose one installed package'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--package', default='com.termux', help='real user-installed app to map')
    parser.add_argument('--inspect-package', default='com.android.deskclock',
                        help='real app used for the read-only identity check over MCP')
    parser.add_argument('--keep-selected', action='store_true',
                        help='leave the app selected afterwards (default: remove it through the product UI)')
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    package = args.package

    def sh(cmd, timeout=90):
        return subprocess.run(adb + ['shell', cmd], capture_output=True, text=True, timeout=timeout).stdout

    results, detail = [], {}

    def check(name, condition, note):
        results.append({'name': name, 'ok': bool(condition), 'note': str(note)[:400]})
        print('%-48s %s  %s' % (name, 'PASS' if condition else 'FAIL', str(note)[:150]), flush=True)
        return bool(condition)

    # ---------------------------------------------------------------- ui helpers
    def dump(tag, attempts=3):
        for _ in range(attempts):
            sh('uiautomator dump /sdcard/%s.xml >/dev/null 2>&1' % tag)
            try:
                return ET.fromstring(sh('cat /sdcard/%s.xml' % tag))
            except ET.ParseError:
                time.sleep(0.8)
        return None

    def find_node(tree, needle, attr):
        if tree is None:
            return None
        want = needle.lower()
        for node in tree.iter('node'):
            if want in (node.get(attr) or '').lower():
                return node
        return None

    def centre(node):
        bounds = re.findall(r'\d+', node.get('bounds', '')) if node is not None else []
        if len(bounds) != 4:
            return None
        left, top, right, bottom = (int(v) for v in bounds)
        return (left + right) // 2, (top + bottom) // 2

    def find_any(tree, needle):
        # An ElementTree element with no children is FALSY, so `a or b` silently returns None for a leaf
        # node — which is exactly what a button is. Compare against None explicitly.
        node = find_node(tree, needle, 'text')
        return node if node is not None else find_node(tree, needle, 'content-desc')

    def scroll_and_find(needle, tag, swipes=12):
        """Find a control that may sit below the fold, scrolling a little at a time."""
        for step in range(swipes):
            node = find_any(dump('%s-%d' % (tag, step)), needle)
            if centre(node) is not None:
                return node
            sh('input swipe 540 1300 540 900 220')
            time.sleep(0.6)
        return None

    def result_line():
        tree = dump('real-app-result')
        if tree is not None:
            for node in tree.iter('node'):
                if (node.get('content-desc') or '') == 'app-control-result':
                    return node.get('text') or ''
        return ''

    def decline_android_dialog(tag):
        """A target app can raise its own Android permission prompt when mapping launches it.

        HUD must not click through another app's permission dialog on the operator's behalf, so this
        declines it (the safe, non-granting choice) and records that it happened. Returns the button text
        it pressed, or None when no dialog is present.
        """
        tree = dump(tag, attempts=1)
        if tree is None:
            return None
        for node in tree.iter('node'):
            text = (node.get('text') or '').strip().lower().replace('\u2019', "'")
            if text in ("don't allow", 'deny'):
                point = centre(node)
                if point is not None:
                    sh('input tap %d %d' % point)
                    time.sleep(1.2)
                    return text
        return None

    def target_field():
        tree = dump('real-app-field')
        if tree is not None:
            for node in tree.iter('node'):
                if 'app-control-target-package' in (node.get('content-desc') or ''):
                    return node
        return None

    # ---------------------------------------------------------------- preconditions
    paths = sh('pm path %s' % package).strip().replace('package:', '')
    check('target app is installed', bool(paths), paths.splitlines()[0] if paths else 'not installed')
    check('target app is real and user-installed, not a HUD fixture or a system package',
          paths.startswith('/data/') and not package.startswith('com.mcpintelligence.fr3k'),
          'apk=%s' % (paths.splitlines()[0] if paths else ''))

    # ---------------------------------------------------------------- the product, on its own path
    sh('am force-stop %s' % HUD)
    sh('am start -n %s/com.mcpintelligence.fr3k.ui.MainActivity --ez open_app_control true' % HUD)
    time.sleep(3)
    sh('settings put secure enabled_accessibility_services %s/com.mcpintelligence.fr3k.bridge.Fr3kBridgeService' % HUD)
    sh('settings put secure accessibility_enabled 1')
    time.sleep(2)
    field = target_field()
    check('APP CONTROL reached through the Deck, not by am start of a non-exported activity',
          field is not None, 'target field present=%s' % (field is not None))

    point = centre(field)
    if point is None:
        args.log.parent.mkdir(parents=True, exist_ok=True)
        args.log.write_text(json.dumps({'results': results}, indent=2) + '\n')
        return 1

    # ---------------------------------------------------------------- choose the real app
    sh('input tap %d %d' % point)
    time.sleep(0.5)
    sh('input text %s' % package)
    time.sleep(0.7)
    field = target_field()
    entered = (field.get('text') or '') if field is not None else ''
    check('package typed into the product field', package in entered, 'field=%r' % entered)
    # The soft keyboard is up after typing and covers the lower half of the screen, where the MAP control
    # lives. One BACK dismisses the keyboard without leaving the activity; swiping on the keyboard scrolls
    # nothing, which looks exactly like a control that is not there.
    sh('input keyevent 4')
    time.sleep(1)
    ime_shown = 'mInputShown=true' in sh('dumpsys input_method | grep mInputShown')
    detail['ime_after_back'] = ime_shown

    # ---------------------------------------------------------------- press the product MAP control
    dialogs = []
    seen = decline_android_dialog('real-app-pre-map')
    if seen:
        dialogs.append(seen)
    map_button = scroll_and_find('MAP SELECTED APP HOME', 'real-app-map')
    check('product MAP SELECTED APP HOME control found', map_button is not None,
          'the same control an operator presses, found after scrolling')
    line = ''
    if map_button is not None:
        sh('input tap %d %d' % centre(map_button))
        # Mapping brings the TARGET app to the foreground and explores its screens, so the App Control
        # result is unreadable until App Control is the active window again. Wait for that, then read.
        # Mapping launches the TARGET app and leaves it in front, so return to App Control before reading
        # the result — the same thing an operator does with the back gesture.
        deadline = time.time() + 120
        backs = 0
        while time.time() < deadline:
            time.sleep(2)
            focus = sh('dumpsys window | grep -m1 mCurrentFocus')
            if 'permissioncontroller' in focus or 'GrantPermissions' in focus:
                decline = decline_android_dialog('real-app-dialog')
                if decline and decline not in dialogs:
                    dialogs.append(decline)
                continue
            if 'AppControlActivity' in focus:
                line = result_line()
                if line and INITIAL_RESULT not in line:
                    break
                continue
            if backs < 4:
                sh('input keyevent 4')
                backs += 1
        detail['backs_to_return'] = backs
        if not line:
            line = result_line()
    detail['map_result_text'] = (line or '')[:400]
    detail['android_dialogs_declined'] = dialogs
    recorded = bool(re.search(r'Recorded \d+ fingerprinted screen controls', line or ''))
    refused = 'Mapping failed' in (line or '')
    check('mapping ran and reported its own outcome (never the untouched prompt)', recorded or refused,
          (line or 'no result line').strip()[:220])

    # ---------------------------------------------------------------- read it back over MCP
    sh('am broadcast -a %s.START_MCP -p %s' % (HUD, HUD))
    element = None
    deadline = time.time() + 25
    while time.time() < deadline:
        out = subprocess.run(adb + ['exec-out', 'run-as', HUD, 'cat', 'files/mcp.json'],
                             capture_output=True, text=True).stdout.strip()
        if out.startswith('{'):
            element = json.loads(out)
            break
        time.sleep(1)
    if not check('capability surface available for read-back', element is not None, element or 'no mcp.json'):
        args.log.write_text(json.dumps({'results': results, 'detail': detail}, indent=2) + '\n')
        return 1
    local_port = 18793
    subprocess.run(adb + ['forward', 'tcp:%d' % local_port, 'tcp:%d' % element['port']],
                   check=True, capture_output=True)
    token = element['token']

    def tool(name, arguments=None):
        connection = http.client.HTTPConnection('127.0.0.1', local_port, timeout=180)
        connection.request('POST', '/tools/' + name, json.dumps(arguments or {}),
                           {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        response = connection.getresponse()
        text = response.read().decode()
        connection.close()
        return json.loads(text)

    capabilities = tool('list_capabilities').get('capabilities', [])
    mine = [c for c in capabilities if c.get('package') == package]
    check('MCP reports real capabilities for the chosen app', len(mine) >= 2,
          'count=%d ids=%s' % (len(mine), [c['id'] for c in mine][:4]))
    check('every reported capability is bound to the real installed identity',
          bool(mine) and all(c.get('fingerprint') for c in mine),
          'fingerprints present=%d of %d' % (sum(1 for c in mine if c.get('fingerprint')), len(mine)))
    check('no fixture capability appears in the normal catalogue',
          not [c for c in capabilities if str(c.get('package', '')).endswith('patchfixture')],
          'packages=%s' % (sorted({c.get('package') for c in capabilities})[:4],))

    identity = tool('get_app_identity', {'package': args.inspect_package})
    detail['identity'] = {k: identity.get(k) for k in ('package', 'versionCode', 'signerSha256')}
    check('identity of a real third-party app reads back over MCP',
          bool(identity.get('versionCode')) and bool(identity.get('signerSha256')),
          '%s versionCode=%s signer=%s' % (args.inspect_package, identity.get('versionCode'),
                                           str(identity.get('signerSha256'))[:16]))

    journal = tool('get_journal').get('records', [])
    detail['journal_records'] = len(journal)
    check('journal holds records of the work', len(journal) > 0, 'records=%d' % len(journal))

    # ---------------------------------------------------------------- leave the device as found
    if not args.keep_selected:
        # Be sure App Control is the active window before driving its removal control.
        for _ in range(4):
            if 'AppControlActivity' in sh('dumpsys window | grep -m1 mCurrentFocus'):
                break
            sh('input keyevent 4')
            time.sleep(1.5)
        remove = scroll_and_find('REMOVE SELECTED APP CONTROLS', 'real-app-remove')
        if remove is not None:
            sh('input tap %d %d' % centre(remove))
            time.sleep(2.5)
            detail['remove_result'] = result_line()[:200]
        prefs = subprocess.run(adb + ['exec-out', 'run-as', HUD, 'cat', 'shared_prefs/controlled-apps.xml'],
                               capture_output=True, text=True).stdout or ''
        check('selection can be removed again through the product UI',
              remove is not None and package not in prefs,
              'removed=%s still_selected=%s' % (remove is not None, package in prefs))
    else:
        detail['selection'] = 'left selected by request'

    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text(json.dumps({'results': results, 'detail': detail, 'package': package}, indent=2) + '\n')
    failed = [r['name'] for r in results if not r['ok']]
    print('REAL_APP_RESULT', 'PASS' if not failed else 'FAIL ' + ','.join(failed), flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

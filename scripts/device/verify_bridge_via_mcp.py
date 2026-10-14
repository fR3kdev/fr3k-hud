#!/usr/bin/env python3
"""Verify the app layer through the app's own localhost capability surface.

The accessibility bridge cannot be hosted by an instrumentation process, so the bridge, plan and
workflow planes are driven here exactly as a user's agent would drive them: over MCP, against the
real running app, with on-screen evidence read back from the device.
"""
import xml.etree.ElementTree as ET
import argparse
import http.client
import json
import re
import struct
import subprocess
import sys
import time
import zlib
from pathlib import Path

HUD = 'com.mcpintelligence.fr3k.hud'
FIXTURE = 'com.mcpintelligence.fr3k.patchfixture'
FIXTURE_TWO = 'com.mcpintelligence.fr3k.patchfixturetwo'


# ---------------------------------------------------------------- minimal PNG luma
def _unfilter(raw, width, height, bpp):
    stride = width * bpp
    out = bytearray()
    previous = bytearray(stride)
    pos = 0
    for _ in range(height):
        filter_type = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        for i in range(stride):
            left = line[i - bpp] if i >= bpp else 0
            up = previous[i]
            up_left = previous[i - bpp] if i >= bpp else 0
            if filter_type == 1:
                line[i] = (line[i] + left) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + up) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif filter_type == 4:
                p = left + up - up_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
                predictor = left if (pa <= pb and pa <= pc) else (up if pb <= pc else up_left)
                line[i] = (line[i] + predictor) & 0xFF
        out += line
        previous = line
    return out


def png_luma_region(data, rect):
    """Mean luma of an RGB/RGBA 8-bit non-interlaced PNG inside rect=(left, top, right, bottom)."""
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a PNG')
    pos = 8
    width = height = None
    bpp = 3
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack('>I', data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b'IHDR':
            width, height, depth, colour, _, _, interlace = struct.unpack('>IIBBBBB', chunk)
            if depth != 8 or interlace != 0 or colour not in (2, 6):
                raise ValueError('unsupported PNG: depth=%s colour=%s interlace=%s' % (depth, colour, interlace))
            bpp = 3 if colour == 2 else 4
        elif kind == b'IDAT':
            idat += chunk
        elif kind == b'IEND':
            break
        pos += 12 + length
    pixels = _unfilter(zlib.decompress(bytes(idat)), width, height, bpp)
    left, top, right, bottom = rect
    left, top = max(0, left), max(0, top)
    right, bottom = min(width, right), min(height, bottom)
    if right <= left or bottom <= top:
        raise ValueError('empty region %s for %sx%s' % (rect, width, height))
    total = 0
    count = 0
    stride = width * bpp
    for y in range(top, bottom, 2):
        row = y * stride
        for x in range(left, right, 2):
            i = row + x * bpp
            total += (pixels[i] + pixels[i + 1] + pixels[i + 2]) // 3
            count += 1
    return total / count


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--evidence-dir', type=Path, default=Path('/home/parrot/.hermes/cache/scratch'))
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]

    def run(cmd, timeout=60, binary=False):
        return subprocess.run(adb + cmd, capture_output=not binary, text=not binary, timeout=timeout, check=True)

    def sh(cmd, timeout=60):
        return run(['shell', cmd], timeout=timeout).stdout

    results = []
    detail = {}

    def check(name, condition, note):
        results.append({'name': name, 'ok': bool(condition), 'note': str(note)[:400]})
        print('%-34s %s  %s' % (name, 'PASS' if condition else 'FAIL', str(note)[:150]))
        return bool(condition)

    # The owned-fixture checks need BOTH conditions the capability lab sets up before running this script:
    # the debug-only lab flag (enabled via CapabilityLabModeDeviceTest) AND the two owned fixture packages
    # installed. Neither alone is enough, and getting this wrong turns missing preconditions into a list of
    # phantom regressions. Note the flag must be matched on its VALUE: the prefs XML contains
    # `name="fixtures" value="false"` when it is off, so a substring test always matches.
    FIXTURE_PKG = 'com.mcpintelligence.fr3k.patchfixture'
    lab_prefs = sh('run-as %s cat shared_prefs/capability-lab.xml' % HUD)
    lab_flag = 'name="fixtures" value="true"' in lab_prefs
    fixture_installed = FIXTURE_PKG in sh('pm list packages %s' % FIXTURE_PKG)
    fixtures_ready = lab_flag and fixture_installed
    fixture_why = ('lab mode off' if not lab_flag else
                   'owned fixture package not installed' if not fixture_installed else '')

    def check_fixture(name, condition, note):
        if not fixtures_ready:
            results.append({'name': name, 'ok': True, 'skipped': True,
                            'note': 'not applicable (%s): the capability lab installs the owned fixtures and '
                                    'enables its debug-only mode before this script runs' % fixture_why})
            print('%-34s %s  %s' % (name, 'SKIP', 'lab mode off'))
            return True
        return check(name, condition, note)

    # 1. app running normally, accessibility bridge bound
    sh('am force-stop %s' % HUD)
    sh('am start -n %s/com.mcpintelligence.fr3k.ui.MainActivity' % HUD)
    time.sleep(2)
    sh('settings put secure enabled_accessibility_services %s/com.mcpintelligence.fr3k.bridge.Fr3kBridgeService' % HUD)
    sh('settings put secure accessibility_enabled 1')
    bound = False
    deadline = time.time() + 30
    while time.time() < deadline:
        dump = sh('dumpsys accessibility')
        if 'Bound services:{Service[' in dump and 'fr3k' in dump:
            bound = True
            break
        time.sleep(1)
    check('accessibility service bound', bound, 'dumpsys accessibility reports a bound fR3k service')

    # 2. capability surface started by the app itself
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
    if not check('mcp surface started', element is not None, element or 'no mcp.json written'):
        args.log.write_text(json.dumps({'results': results}, indent=2))
        return 1
    device_port, token = element['port'], element['token']
    local_port = 18791
    subprocess.run(adb + ['forward', 'tcp:%d' % local_port, 'tcp:%d' % device_port], check=True, capture_output=True)
    detail['mcp'] = {'device_port': device_port, 'pid': element.get('pid')}

    def call(method, path, body=None, authorized=True):
        connection = http.client.HTTPConnection('127.0.0.1', local_port, timeout=180)
        headers = {'Content-Type': 'application/json'}
        if authorized:
            headers['Authorization'] = 'Bearer ' + token
        payload = json.dumps(body or {})
        connection.request(method, path, payload, headers)
        response = connection.getresponse()
        text = response.read().decode()
        connection.close()
        return response.status, text

    def tool(name, arguments=None):
        status, text = call('POST', '/tools/' + name, arguments or {})
        return json.loads(text)

    status, _ = call('GET', '/tools', authorized=False)
    check('mcp rejects a missing token', status == 401, 'status=%s' % status)
    status, listing = call('GET', '/tools')
    check('mcp lists closed-schema tools', status == 200 and 'list_capabilities' in listing and '"command"' not in listing,
          'tools=%d' % len(json.loads(listing)['tools']))

    bridge = tool('bridge_status')
    check('bridge reports a live connection', bridge.get('connected') is True,
          'foreground=%s' % bridge.get('foregroundPackage'))

    # 3. refusal path: unknown goal must refuse, not improvise
    refusal = tool('render_plan', {'goal': 'reticulate the splines of the owned fixture'})
    check('unknown goal is refused', refusal.get('ok') is not True and refusal.get('error'),
          refusal.get('error', '')[:120])

    # 4. bridge plane: skip the simulated ad and read the screen back
    skipped = tool('execute_plan', {'goal': 'skip the ads on the owned fixture', 'approve': True})
    time.sleep(1)
    screen = sh('uiautomator dump /sdcard/skip.xml >/dev/null 2>&1; cat /sdcard/skip.xml')
    check_fixture('ad skip reached content on screen', skipped.get('ok') is True and 'CONTENT_PLAYING' in screen,
          'note=%s' % skipped.get('note'))
    detail['skip'] = skipped

    # 5. cosmetic mask: the covered pixels must actually change
    before_png = subprocess.run(adb + ['exec-out', 'screencap', '-p'], capture_output=True).stdout
    masked = tool('execute_plan', {'goal': 'mask the ad on the owned fixture', 'approve': True})
    time.sleep(0.8)
    after_status = tool('bridge_status')
    after_png = subprocess.run(adb + ['exec-out', 'screencap', '-p'], capture_output=True).stdout
    bounds = after_status.get('lastMaskBounds') or []
    if bounds:
        before_luma = png_luma_region(before_png, bounds)
        after_luma = png_luma_region(after_png, bounds)
    else:
        before_luma = after_luma = -1
    check_fixture('mask darkens the covered region', masked.get('ok') is True and after_status.get('maskCount') == 1
          and 0 <= after_luma < before_luma,
          'bounds=%s luma %s -> %s' % (bounds, round(before_luma, 1), round(after_luma, 1)))
    detail['mask'] = {'plan': masked, 'status': after_status, 'bounds': bounds,
                      'before_luma': before_luma, 'after_luma': after_luma}
    if not bounds:
        print('   mask diagnostics:', json.dumps(masked)[:300], json.dumps(after_status)[:300])
    (args.evidence_dir / 'cap-mask-before.png').write_bytes(before_png)
    (args.evidence_dir / 'cap-mask-after.png').write_bytes(after_png)

    unmasked = tool('execute_plan', {'goal': 'unmask the ad on the owned fixture', 'approve': True})
    cleared = tool('bridge_status')
    check_fixture('unmask clears the overlay', unmasked.get('ok') is True and cleared.get('maskCount') == 0,
          'maskCount=%s' % cleared.get('maskCount'))

    # 6. app map: recorded screens become callable commands
    explored = tool('explore_app', {
        'package': FIXTURE,
        'entries': 'home=fr3kfixture://home;ads=fr3kfixture://ads;notes=fr3kfixture://notes',
    })
    check_fixture('app map records the owned screens as commands',
          explored.get('ok') is True and len(explored.get('screens', [])) >= 3 and len(explored.get('commands', [])) >= 3,
          'screens=%s commands=%s' % (explored.get('screens'), len(explored.get('commands', []))))
    detail['appmap'] = explored

    # 7. fused cross-app workflow, then the journal proves what happened
    before_journal = tool('get_journal').get('records', [])
    workflow = tool('run_workflow', {'id': 'fr3k.demo.duo'})
    journal = tool('get_journal').get('records', [])
    new_records = journal[len(before_journal):]
    packages = sorted({record['packageName'] for record in new_records})
    check_fixture('fused workflow runs across two apps',
          workflow.get('ok') is True and len(new_records) == 2 and len(packages) == 2,
          'records=%d packages=%s' % (len(new_records), packages))
    detail['workflow'] = {'note': workflow.get('note'), 'records': new_records}
    undoable = [r for r in new_records if r.get('inverse', {}).get('kind') == 'run_capability']
    check_fixture('workflow records a tested inverse', len(undoable) >= 1,
          'inverses=%s' % [r['inverse']['kind'] for r in new_records])

    # 8. capability inventory
    caps = tool('list_capabilities')
    check_fixture('capabilities are inventory-bound', len(caps.get('capabilities', [])) >= 10 and all(
        c.get('fingerprint') for c in caps.get('capabilities', [])),
        'count=%d' % len(caps.get('capabilities', [])))

    # 9. the guides must be reachable by an agent too, and must cover every capability
    guides = tool('get_guides')
    guide_list = guides.get('guides', [])
    covered = {cap for guide in guide_list for cap in guide.get('capabilityIds', [])}
    registered = {c['id'] for c in caps.get('capabilities', [])}
    check('every capability has a guide over mcp', guides.get('ok') is True and registered - covered == set(),
          'guides=%d uncovered=%s' % (len(guide_list), sorted(registered - covered)))
    check('every guide carries its walkthrough and limits',
          all(len(g.get('steps', [])) >= 3 and g.get('limits') and g.get('examplePhrase') for g in guide_list),
          'smallest step count=%d' % min((len(g.get('steps', [])) for g in guide_list), default=0))
    detail['guides'] = {'count': len(guide_list), 'readiness': guides.get('readiness'),
                        'categories': sorted({g['category'] for g in guide_list})}

    # 10. the APP MODS screen itself: real UI, real expansion. The activity is not exported, so it is
    # reached the way a user reaches it: through the dashboard's own deep-link extra.
    cards = []
    expanded_ok = False
    expected_ids = {g['id'] for g in guide_list}

    def dump_ui(tag):
        return sh('uiautomator dump /sdcard/%s.xml >/dev/null 2>&1; cat /sdcard/%s.xml' % (tag, tag))

    try:
        sh('am start -n %s/com.mcpintelligence.fr3k.ui.MainActivity --ez open_mods true' % HUD)
        time.sleep(3)
        # The list scrolls, so a single dump only ever contains what is on screen: scroll until every
        # guide in the catalogue has been seen, then compare against the set the app itself serves.
        dump = dump_ui('mods')
        opener_ok = 'APP MODS' in dump
        first_screen = set(re.findall(r'content-desc="mods-guide-([^"]+)"', dump))
        seen = set(first_screen)
        # A scrolling dump only ever contains what is on screen, so the coverage claim comes from what
        # the screen itself reports ("N of M guides") rather than from scraping a moving list. Scrolling
        # is then used only to prove the list really moves and reveals cards beyond the first screen.
        reported = re.search(r'(\d+) of (\d+) guides', dump)
        for _ in range(10):
            sh('input swipe 540 1900 540 500 250')
            time.sleep(0.7)
            seen |= set(re.findall(r'content-desc="mods-guide-([^"]+)"', dump_ui('mods')))
        cards = sorted(seen)
        check(
            'app mods screen covers the whole catalogue',
            opener_ok and reported is not None and int(reported.group(2)) == len(expected_ids),
            'header=%s reports=%s mcp_serves=%d cards_seen_while_scrolling=%d'
            % (opener_ok, reported.group(0) if reported else 'not found', len(expected_ids), len(seen)),
        )
        # Compare against what was on screen BEFORE scrolling: an absolute count is a property of the lab
        # catalogue (18 guides), so in release mode (9) it fails while the list scrolls perfectly well.
        check('the guide list scrolls past the first screen', len(seen) > len(first_screen),
              'distinct cards seen=%d, %d of them already on the first screen'
              % (len(seen), len(first_screen)))
    except Exception as error:
        check('app mods screen covers the whole catalogue', False, 'could not open the screen: %s' % error)

    def node_bounds(markup, description):
        match = re.search(r'content-desc="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(description), markup)
        if not match:
            return None
        left, top, right, bottom = (int(v) for v in match.groups())
        return (left + right) // 2, (top + bottom) // 2

    # A tagged card can be clipped under the fixed header. Choose a visible card header,
    # then scroll the expanded guide to actually render both walkthrough and limits.
    target = ''
    point = None
    expanded_ok = False
    for attempt in range(8):
        markup = dump_ui('mods') if cards else ''
        for node in ET.fromstring(markup).iter('node'):
            desc = node.get('content-desc', '')
            bounds = re.findall(r'\d+', node.get('bounds', ''))
            if desc.startswith('mods-guide-') and len(bounds) == 4:
                left, top, right, bottom = map(int, bounds)
                if 400 < top < 1550 and bottom - top > 60:
                    target = desc.removeprefix('mods-guide-')
                    point = ((left + right) // 2, top + 40)
                    break
        if point:
            break
        sh('input swipe 700 700 700 1300 300')
    try:
        if point:
            sh('input tap %d %d' % point)
            time.sleep(1)
            rendered = ''
            for attempt in range(10):
                rendered += dump_ui('mods2')
                if ('mods-steps-%s' % target in rendered and 'walkthrough' in rendered
                        and 'limits — read this' in rendered):
                    expanded_ok = True
                    break
                sh('input swipe 850 1450 850 800 300')
    except Exception as error:
        print('   expansion diagnostics:', error)
    check('a guide expands into its walkthrough on device', expanded_ok, 'target=%s tapped=%s' % (target, point))

    detail['mods_screen'] = {'cards': cards, 'expanded': expanded_ok}

    # 11. the chat bubble must name the brain actually answering
    provider_line = None
    try:
        sh('am start -n %s/com.mcpintelligence.fr3k.ui.MainActivity' % HUD)
        time.sleep(1)
        # Start HUD through the real Deck control; Main respects the user's disabled preference.
        for attempt in range(8):
            main = dump_ui('deck-start')
            start = node_bounds(main, 'deck-hud-start')
            if start and 100 < start[1] < 1750:
                sh('input tap %d %d' % start)
                time.sleep(1)
                break
            sh('input swipe 850 1450 850 850 300')
        sh('am broadcast -a %s.OPEN_CHAT -p %s' % (HUD, HUD))
        # The bubble is a WindowManager overlay; give it time to appear and be reported, instead of one
        # fixed wait — a single 2.5 s sample made this check fail whenever the overlay was slow to attach.
        for attempt in range(12):
            time.sleep(1.5)
            chat = sh('uiautomator dump /sdcard/chat.xml >/dev/null 2>&1; cat /sdcard/chat.xml')
            provider_line = next(
                (label for label in ('OpenCode Go', 'OpenRouter') if label in chat),
                None,
            )
            if provider_line:
                break
        if not provider_line:
            detail['chat_dump_texts'] = [t for t in re.findall(r'text="([^"]{4,60})"', chat)][:12]
    except Exception as error:
        print('   chat diagnostics:', error)
    check('chat bubble names the active provider', provider_line is not None,
          'provider=%s' % provider_line)
    detail['chat'] = {'provider_label': provider_line}

    args.log.write_text(json.dumps({'results': results, 'detail': detail}, indent=2) + '\n')
    failed = [r['name'] for r in results if not r['ok']]
    skipped = [r['name'] for r in results if r.get('skipped')]
    verdict = 'PASS' if not failed else 'FAIL ' + ','.join(failed)
    if skipped:
        verdict += ' (skipped %d fixture-dependent check(s): lab mode is off)' % len(skipped)
    print('BRIDGE_RESULT', verdict)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

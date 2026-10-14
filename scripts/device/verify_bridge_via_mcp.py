#!/usr/bin/env python3
"""Verify the app layer through the app's own localhost capability surface.

The accessibility bridge cannot be hosted by an instrumentation process, so the bridge, plan and
workflow planes are driven here exactly as a user's agent would drive them: over MCP, against the
real running app, with on-screen evidence read back from the device.
"""
import argparse
import http.client
import json
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
    check('ad skip reached content on screen', skipped.get('ok') is True and 'CONTENT_PLAYING' in screen,
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
    check('mask darkens the covered region', masked.get('ok') is True and after_status.get('maskCount') == 1
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
    check('unmask clears the overlay', unmasked.get('ok') is True and cleared.get('maskCount') == 0,
          'maskCount=%s' % cleared.get('maskCount'))

    # 6. app map: recorded screens become callable commands
    explored = tool('explore_app', {
        'package': FIXTURE,
        'entries': 'home=fr3kfixture://home;ads=fr3kfixture://ads;notes=fr3kfixture://notes',
    })
    check('app map records the owned screens as commands',
          explored.get('ok') is True and len(explored.get('screens', [])) >= 3 and len(explored.get('commands', [])) >= 3,
          'screens=%s commands=%s' % (explored.get('screens'), len(explored.get('commands', []))))
    detail['appmap'] = explored

    # 7. fused cross-app workflow, then the journal proves what happened
    before_journal = tool('get_journal').get('records', [])
    workflow = tool('run_workflow', {'id': 'fr3k.demo.duo'})
    journal = tool('get_journal').get('records', [])
    new_records = journal[len(before_journal):]
    packages = sorted({record['packageName'] for record in new_records})
    check('fused workflow runs across two apps',
          workflow.get('ok') is True and len(new_records) == 2 and len(packages) == 2,
          'records=%d packages=%s' % (len(new_records), packages))
    detail['workflow'] = {'note': workflow.get('note'), 'records': new_records}
    undoable = [r for r in new_records if r.get('inverse', {}).get('kind') == 'run_capability']
    check('workflow records a tested inverse', len(undoable) >= 1,
          'inverses=%s' % [r['inverse']['kind'] for r in new_records])

    # 8. capability inventory
    caps = tool('list_capabilities')
    check('capabilities are inventory-bound', len(caps.get('capabilities', [])) >= 10 and all(
        c.get('fingerprint') for c in caps.get('capabilities', [])),
        'count=%d' % len(caps.get('capabilities', [])))

    args.log.write_text(json.dumps({'results': results, 'detail': detail}, indent=2) + '\n')
    failed = [r['name'] for r in results if not r['ok']]
    print('BRIDGE_RESULT', 'PASS' if not failed else 'FAIL ' + ','.join(failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

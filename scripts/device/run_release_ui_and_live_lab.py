#!/usr/bin/env python3
"""Run the production-mode UI and live-integration suites, one class per instrumentation process.

Each class gets a fresh app process: running twenty device tests in a single `am instrument` invocation
accumulated state and resource pressure across classes, so a class that passes alone (verified) could fail
or take the process down inside the combined run. The capability lab already instruments class by class for
the same reason.

A synthetic emulator GPS fix is supplied every five seconds so the location checks observe a real fix.

Usage:
  python3 scripts/device/run_release_ui_and_live_lab.py --serial emulator-5582 \
      --log docs/design/evidence/release-ui-and-live.txt [--gps 153.025,-27.469] [--timeout 600]
"""
import argparse
import json
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

HUD = 'com.mcpintelligence.fr3k.hud'
RUNNER = 'com.mcpintelligence.fr3k.hud.test/androidx.test.runner.AndroidJUnitRunner'

# WirelessAndSelectedAppsDeviceTest is deliberately NOT here: its pairing test needs the operator to open
# Android's live pairing-code sheet, and its selected-app test needs HUD's privileged service started from
# the host while the HUD process publishes the provider. Run it from the capability lab's privileged phase,
# not from a suite that would have to fake those preconditions.
CLASSES = [
    'com.mcpintelligence.fr3k.ui.RedesignUiDeviceTest',
    'com.mcpintelligence.fr3k.ui.FloatingWorkspaceDeviceTest',
    'com.mcpintelligence.fr3k.ui.FloatingSurfaceDeviceTest',
    'com.mcpintelligence.fr3k.VisibleToolsDeviceTest',
    'com.mcpintelligence.fr3k.ui.LocationAndTextDeviceTest',
    'com.mcpintelligence.fr3k.integrations.opencode.OpenCodeGoLiveDeviceTest',
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--gps', default='153.025,-27.469', help='lon,lat for the emulator fix')
    parser.add_argument('--timeout', type=int, default=600, help='per-class timeout in seconds')
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]

    stop = threading.Event()

    def gps_fixes():
        lon, lat = args.gps.split(',')
        while not stop.is_set():
            subprocess.run(adb + ['emu', 'geo', 'fix', lon, lat],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            stop.wait(5)

    thread = threading.Thread(target=gps_fixes, daemon=True)
    thread.start()

    results = {}
    try:
        with args.log.open('w') as out:
            for cls in CLASSES:
                subprocess.run(adb + ['shell', 'am', 'force-stop', HUD],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                started = time.time()
                try:
                    run = subprocess.run(
                        adb + ['shell', 'am', 'instrument', '-w', '-e', 'class', cls, RUNNER],
                        capture_output=True, text=True, timeout=args.timeout)
                    text = run.stdout + run.stderr
                    crashed = 'Process crashed' in text
                    ok = bool(text) and 'OK (' in text and 'FAILURES!!!' not in text and not crashed
                    count = re.search(r'OK \((\d+) test', text)
                    failures = re.search(r'Failures: (\d+)', text)
                    results[cls] = {
                        'ok': bool(ok),
                        'tests': int(count.group(1)) if count else None,
                        'failures': int(failures.group(1)) if failures else 0,
                        'crashed': crashed,
                        'seconds': round(time.time() - started, 1),
                    }
                except subprocess.TimeoutExpired:
                    text = 'TIMEOUT after %ds\n' % args.timeout
                    results[cls] = {'ok': False, 'tests': None, 'failures': None,
                                    'crashed': False, 'timeout': True, 'seconds': args.timeout}
                out.write('\n===== %s =====\n' % cls)
                out.write(text)
                out.flush()
                state = 'PASS' if results[cls]['ok'] else 'FAIL'
                print('%-62s %s  %s' % (cls, state, json.dumps(results[cls])), flush=True)
    finally:
        stop.set()
        thread.join(10)

    failed = [cls for cls, r in results.items() if not r['ok']]
    total = sum(r['tests'] or 0 for r in results.values())
    print('RELEASE_UI_RESULT %s  classes=%d tests=%d failed=%s'
          % ('PASS' if not failed else 'FAIL', len(results), total, failed or 'none'))
    # Keep the machine-readable summary beside the human log.
    args.log.with_suffix('.json').write_text(json.dumps({'results': results, 'failed': failed}, indent=2) + '\n')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""End-to-end embedded app-control lab run on an explicitly selected disposable emulator.

Order matters: the on-device patcher republishes the prepared fixture, so the prepared APK
must be (re)installed before the live-control tests can pass their fail-closed SHA check.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

HUD = 'com.mcpintelligence.fr3k.hud'
RUNNER = HUD + '.test/androidx.test.runner.AndroidJUnitRunner'
SCRATCH = Path('/home/parrot/.hermes/cache/scratch')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk-dir', type=Path, required=True, help='app/build/outputs/apk')
    parser.add_argument('--log', type=Path, required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]

    def run(cmd, **kw):
        return subprocess.run(adb + cmd, check=True, text=True, capture_output=True, **kw)

    def instrument(cls, evidence_name, expect):
        out = SCRATCH / evidence_name
        result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
            'com.mcpintelligence.fr3k.appcontrol.' + cls, RUNNER], text=True, capture_output=True, timeout=180)
        text = result.stdout + result.stderr
        out.write_text(text)
        ok = expect in text and 'FAILURES' not in text
        print('%-36s %s (%s)' % (cls, 'PASS' if ok else 'FAIL', expect))
        if not ok:
            print(text[-2000:])
        return ok

    failures = []
    run(['install', '-r', str(args.apk_dir / 'debug/app-debug.apk')])
    run(['install', '-r', str(args.apk_dir / 'androidTest/debug/app-debug-androidTest.apk')])
    if not instrument('EmbeddedFixturePatcherDeviceTest', 'hud-ondevice-patch-instrumentation.txt', 'OK (1 test)'):
        failures.append('patcher')

    # Extract exactly what the on-device engine published, verified against its own recorded digest.
    meta = json.loads(run(['exec-out', 'run-as', HUD, 'cat', 'files/shares/owned-fixture-latest.json']).stdout)
    payload = subprocess.run(adb + ['exec-out', 'run-as', HUD, 'cat', meta['path']],
                             check=True, capture_output=True).stdout
    digest = hashlib.sha256(payload).hexdigest()
    if digest != meta['sha256']:
        raise SystemExit('published prepared APK hash mismatch')
    prepared = SCRATCH / 'hud-ondevice-prepared.apk'
    prepared.write_bytes(payload)
    print('%-36s %s' % ('on-device prepared APK', digest))
    subprocess.run(adb + ['uninstall', 'com.mcpintelligence.fr3k.patchfixture'],
                   text=True, capture_output=True)
    run(['install', str(prepared)])

    for cls, evidence, expect in (
            ('OwnedFixtureControllerDeviceTest', 'hud-owned-controller-instrumentation.txt', 'OK (1 test)'),
            ('FixtureAgentApprovalDeviceTest', 'hud-FixtureAgentApprovalDeviceTest-instrumentation.txt', 'OK (1 test)'),
            ('AppInspectionDeviceTest', 'hud-AppInspectionDeviceTest-instrumentation.txt', 'OK (2 tests)')):
        if not instrument(cls, evidence, expect):
            failures.append(cls)

    privilege = subprocess.run([sys.executable, str(Path(__file__).with_name('test_embedded_privilege.py')),
        '--serial', args.serial, '--output', str(SCRATCH / 'hud-embedded-default-instrumentation.txt')],
        text=True, capture_output=True, timeout=180)
    privilege_ok = privilege.returncode == 0 and 'OK (2 tests)' in privilege.stdout
    print('%-36s %s' % ('EmbeddedPrivilegeDeviceTest', 'PASS' if privilege_ok else 'FAIL'))
    if not privilege_ok:
        failures.append('privilege')

    for remote in ('owned-banner-hidden.png', 'owned-banner-restored.png', 'app-control.png', 'app-control-bottom.png'):
        local = SCRATCH / ('hud-' + remote if remote.startswith('app-') else remote)
        subprocess.run(adb + ['pull', '/sdcard/Android/data/%s/files/%s' % (HUD, remote), str(local)],
                       check=True, text=True, capture_output=True)
    summary = {'serial': args.serial, 'prepared_sha256': digest, 'failures': failures,
               'packages_on_device': [p for p in run(['shell', 'pm', 'list', 'packages']).stdout.split()
                                      if any(k in p for k in ('shizuku', 'lspatch', 'termux'))]}
    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text(json.dumps(summary, indent=2) + '\n')
    print('companion packages present:', summary['packages_on_device'] or 'NONE')
    print('RESULT', 'PASS' if not failures else 'FAIL ' + ','.join(failures))
    return 1 if failures else 0


if __name__ == '__main__':
    raise SystemExit(main())

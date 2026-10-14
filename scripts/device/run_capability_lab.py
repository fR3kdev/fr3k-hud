#!/usr/bin/env python3
"""Full capability-platform verification on an explicitly selected disposable emulator.

Order matters and is deliberate:
  1. app running normally + accessibility bound  -> bridge/plan/workflow/mask/map via the app's MCP surface
  2. instrumentation                             -> patch plane, inspection, agent-approved write
  3. privileged service at the shell tier        -> typed app-op operations
  4. privileged service at the root tier         -> root-gated operations actually granted
"""
import atexit
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

HUD = 'com.mcpintelligence.fr3k.hud'
RUNNER = HUD + '.test/androidx.test.runner.AndroidJUnitRunner'
FIXTURE = 'com.mcpintelligence.fr3k.patchfixture'
COMPANIONS = ['org.lsposed.lspatch', 'com.termux.api', 'moe.shizuku.privileged.api']
SCRATCH = Path('/home/parrot/.hermes/cache/scratch')

INSTRUMENTED = [
    ('com.mcpintelligence.fr3k.ui.ProviderPreferenceDeviceTest', 3),
    ('com.mcpintelligence.fr3k.appcontrol.PatchPlaneDeviceTest', 2),
    ('com.mcpintelligence.fr3k.appcontrol.AppInspectionDeviceTest', 2),
    ('com.mcpintelligence.fr3k.appcontrol.FixtureAgentApprovalDeviceTest', 1),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--skip-root', action='store_true')
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    repo = args.repo.resolve()
    results = {}

    def run(cmd, timeout=200, check=True):
        return subprocess.run(adb + cmd, text=True, capture_output=True, timeout=timeout, check=check)

    def sh(cmd, timeout=120, check=True):
        return run(['shell', cmd], timeout=timeout, check=check)

    def record(name, ok, note=''):
        results[name] = {'ok': bool(ok), 'note': str(note)[:600]}
        print('%-46s %s  %s' % (name, 'PASS' if ok else 'FAIL', str(note)[:120]))
        return bool(ok)

    def instrument(cls, expected, timeout=300):
        proc = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class', cls, RUNNER],
                              text=True, capture_output=True, timeout=timeout)
        text = proc.stdout + proc.stderr
        (SCRATCH / ('cap-%s.txt' % cls.split('.')[-1])).write_text(text)
        marker = 'OK (%d test' % expected if expected != 1 else 'OK (1 test)'
        ok = marker in text and 'FAILURES' not in text
        tail = text.strip().splitlines()[-1] if text.strip() else 'no output'
        return record(cls.split('.')[-1], ok, tail)

    def wait_for_hud(timeout=25):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if sh('pidof %s' % HUD, check=False).stdout.strip():
                return True
            time.sleep(0.5)
        return False

    def privileged(cls, expected, tag):
        """Instrumentation needs a live HUD process; the service itself must be started from the host."""
        proc = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class', cls, RUNNER],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if not wait_for_hud():
            proc.kill()
            return record('privileged-%s' % tag, False, 'HUD process never started')
        apk = sh('pm path %s' % HUD).stdout.strip().splitlines()[0].replace('package:', '')
        service = subprocess.Popen(
            adb + ['shell', "CLASSPATH='%s' app_process /system/bin com.mcpintelligence.fr3k.privileged.HudPrivilegedMain" % apk],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            text = proc.communicate(timeout=240)[0] or ''
        except subprocess.TimeoutExpired:
            proc.kill()
            text = 'instrumentation timed out'
        finally:
            service.terminate()
            try:
                service_output = service.communicate(timeout=5)[0] or ''
            except subprocess.TimeoutExpired:
                service.kill()
                service_output = ''
        (SCRATCH / ('cap-privileged-%s.txt' % tag)).write_text(text + '\nSERVICE: ' + service_output)
        marker = 'OK (%d test' % expected if expected != 1 else 'OK (1 test)'
        ok = marker in text and 'FAILURES' not in text
        tier = next((line.strip() for line in service_output.splitlines() if 'HUD_PRIVILEGED_READY' in line), '')
        return record('privileged-%s' % tag, ok, tier or (text.strip().splitlines()[-1][:120] if text.strip() else 'no output'))

    # ---------------------------------------------------------------- install
    for pkg in COMPANIONS:
        run(['uninstall', pkg], check=False)
    # The fixtures may be installed as a previously re-signed build; they are disposable owned apps.
    run(['uninstall', FIXTURE], check=False)
    run(['uninstall', 'com.mcpintelligence.fr3k.patchfixturetwo'], check=False)
    run(['install', '-r', str(repo / 'app/build/outputs/apk/debug/app-debug.apk')])
    run(['install', '-r', str(repo / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')])
    run(['install', '-r', str(repo / 'patch-fixture/build/outputs/apk/debug/patch-fixture-debug.apk')])
    run(['install', '-r', str(repo / 'patch-fixture-two/build/outputs/apk/debug/patch-fixture-two-debug.apk')])
    record('install artifacts', True, 'hud + test apk + two owned fixtures')

    def leave_lab_mode():
        subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
                       'com.mcpintelligence.fr3k.ui.CapabilityLabModeDeviceTest#disableFixtures', RUNNER],
                       capture_output=True, timeout=60, check=False)
    # Leave lab mode at process exit AND immediately when the body finishes, so a device that is left
    # behind after this run never keeps disposable fixture capabilities reachable.
    atexit.register(leave_lab_mode)
    instrument('com.mcpintelligence.fr3k.ui.CapabilityLabModeDeviceTest#enableFixtures', 1)

    # ---------------------------------------------------------------- phase 1
    print('--- phase 1: bridge, plan, workflow, mask, app map (through the app MCP surface) ---')
    mcp = subprocess.run([sys.executable, str(repo / 'scripts/device/verify_bridge_via_mcp.py'),
                          '--serial', args.serial, '--log', str(SCRATCH / 'cap-mcp-verify.json')],
                         text=True, capture_output=True, timeout=600)
    print(mcp.stdout)
    evidence = SCRATCH / 'cap-mcp-verify.json'
    checks = len(json.loads(evidence.read_text())['results']) if evidence.exists() else 0
    record('bridge-plane-via-mcp', mcp.returncode == 0, 'checks=%d' % checks)

    # ---------------------------------------------------------------- phase 2
    print('--- phase 2: owned-fixture patching, then the instrumented suites ---')
    run(['shell', 'am', 'force-stop', HUD], check=False)
    instrument('com.mcpintelligence.fr3k.appcontrol.EmbeddedFixturePatcherDeviceTest', 1)
    meta = json.loads(sh('run-as %s cat files/shares/owned-fixture-latest.json' % HUD).stdout)
    payload = subprocess.run(adb + ['exec-out', 'run-as', HUD, 'cat', meta['path']],
                             capture_output=True, timeout=60).stdout
    digest = hashlib.sha256(payload).hexdigest()
    record('on-device prepared APK', digest == meta['sha256'], digest[:24])
    prepared = SCRATCH / 'cap-prepped-fixture.apk'
    prepared.write_bytes(payload)
    run(['uninstall', FIXTURE], check=False)
    run(['install', str(prepared)])
    instrument('com.mcpintelligence.fr3k.appcontrol.OwnedFixtureControllerDeviceTest', 1)
    for cls, expected in INSTRUMENTED:
        instrument(cls, expected)

    # ---------------------------------------------------------------- phase 3
    print('--- phase 3: privileged service at the shell tier ---')
    # Drop any root adbd left over from an earlier run so the shell tier is genuinely unrooted.
    run(['unroot'], check=False)
    time.sleep(3)
    run(['shell', 'am', 'force-stop', HUD], check=False)
    privileged('com.mcpintelligence.fr3k.privileged.PrivilegedPlaneDeviceTest', 4, 'shell')

    # ---------------------------------------------------------------- phase 4
    if not args.skip_root:
        print('--- phase 4: privileged service at the root tier ---')
        root = run(['root'], check=False)
        if root.returncode == 0 and 'cannot run as root' not in (root.stdout + root.stderr):
            time.sleep(3)
            for pkg in COMPANIONS:
                run(['uninstall', pkg], check=False)
            run(['shell', 'am', 'force-stop', HUD], check=False)
            privileged('com.mcpintelligence.fr3k.privileged.PrivilegedPlaneDeviceTest', 4, 'root')
        else:
            record('privileged-root', False, 'adb root unavailable on this image: NOT TESTED')

    instrument('com.mcpintelligence.fr3k.ui.CapabilityLabModeDeviceTest#disableFixtures', 1)

    companions = [p for p in sh('pm list packages').stdout.split() if p.removeprefix('package:') in COMPANIONS]
    summary = {'serial': args.serial, 'results': results, 'companion_packages': companions}
    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text(json.dumps(summary, indent=2) + '\n')
    failed = [k for k, v in results.items() if not v['ok']]
    print('companion packages present:', companions or 'NONE')
    print('CAPABILITY_RESULT', 'PASS' if not failed else 'FAIL ' + ','.join(failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())

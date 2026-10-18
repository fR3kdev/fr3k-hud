#!/usr/bin/env python3
"""Build, freeze, initialize and verify both preserved task AVDs; retain every failed attempt."""
import argparse
import concurrent.futures
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
import uuid
from evidence import *

ADB = '/usr/lib/android-sdk/platform-tools/adb'
PACKAGE = 'com.mcpintelligence.fr3k.hud'
ARTIFACTS = ('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
             'app/build/generated/appmod/assets/appmod/classes.dex')
STATE_LOCK = threading.Lock()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--work', type=Path, default=Path('/home/parrot/.cache/hud-appmod/runs'))
    parser.add_argument('--live-github', action='store_true')
    # Diagnostic narrowing only: a filtered run never satisfies the acceptance contract because the
    # validator requires every frozen scenario, so it cannot be promoted or packaged.
    parser.add_argument('--only', help='comma-separated scenario names for a diagnostic subset')
    parser.add_argument('--api', type=int, action='append', help='restrict a diagnostic run to one API level')
    args = parser.parse_args()
    subset = [name.strip() for name in args.only.split(',')] if args.only else []
    if any(name not in SCENARIOS for name in subset):
        raise SystemExit('unknown scenario in --only: ' + ','.join(n for n in subset if n not in SCENARIOS))
    run = args.work / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8])
    run.mkdir(parents=True)
    manifest = dict(live={}, schema='hud-appmod-run/2', status='RUNNING', source=snapshot(), artifacts={}, evidence={}, scenarios={}, jvm={}, coverage={})
    if subset or args.api:
        manifest['diagnostic'] = dict(scenarios=subset or list(SCENARIOS), apis=args.api or [31, 35],
                                      note='narrowed diagnostic run: not a complete frozen acceptance run; cannot satisfy evidence.py validate()')
    def save():
        with STATE_LOCK:
            write_json(run / 'run-manifest.json', manifest)
    save()
    print(run, flush=True)
    env = os.environ.copy()
    tmp = run / 'tmp'; tmp.mkdir()
    env.update(JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64', ANDROID_HOME='/usr/lib/android-sdk', TMPDIR=str(tmp), JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=' + str(tmp))
    def command(argv, log, timeout=900, check=True):
        path = run / log; path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('w') as stream:
            try:
                result = subprocess.run(argv, cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
            except subprocess.TimeoutExpired:
                stream.write('\nRUNNER_TIMEOUT\n'); raise
        if check and result.returncode:
            raise RuntimeError('command failed: ' + log)
        return path.read_text(errors='replace')
    try:
        host_log = command([sys.executable, '-m', 'unittest', 'discover', '-v', '-s', 'scripts/appmod', '-p', 'test_*.py'], 'host-contract.log')
        manifest['host'] = dict(status='PASS', log='host-contract.log', tests=int(re.search(r'Ran (\d+) tests?', host_log).group(1)))
        command(['./gradlew', ':capability:test', ':core:testDebugUnitTest', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:testReleaseUnitTest', '--offline', '--console=plain', '-Pkotlin.incremental=false'], 'gradle.log')
        if snapshot() != manifest['source']:
            raise RuntimeError('source changed during build')
        pins = json.loads((REPO / 'docs/app-control/evidence/appmod-dependency-pins.json').read_text())
        dependency_ledger = {}
        cache = Path('/home/parrot/.gradle/caches/modules-2/files-2.1')
        for pin in pins['dependencies']:
            group, name, version = pin['coordinate'].split(':')
            result = {}
            for extension, expected in (('jar', pin['jarSha256']), ('pom', pin['pomSha256'])):
                candidates = list((cache / group / name / version).glob('*/*.' + extension))
                if not candidates or not any(sha256(p) == expected for p in candidates):
                    raise RuntimeError('dependency pin mismatch: ' + pin['coordinate'] + '/' + extension)
                result[extension + 'Sha256'] = expected
            dependency_ledger[pin['coordinate']] = result
        write_json(run / 'dependency-verification.json', dependency_ledger)
        manifest['artifacts'] = {p: sha256(REPO / p) for p in ARTIFACTS}
        manifest['artifacts'].update({str(p.relative_to(REPO)): sha256(p) for p in (REPO / 'app/src/main/assets/app-control').glob('*') if p.is_file()})
        for module, pattern in MODULES.items():
            reports = sorted(REPO.glob(pattern)); manifest['jvm'][module] = xml_counts(reports)
            folder = run / 'jvm' / module; folder.mkdir(parents=True)
            for path in reports:
                shutil.copy2(path, folder / path.name)
        save()
        def device(api, serial):
            prefix = f'api{api}'
            counter = 0
            def adb(*argv, check=True):
                nonlocal counter
                counter += 1
                return command([ADB, '-s', serial, *argv], f'{prefix}/setup-{counter:03}.log', timeout=180, check=check).strip()
            # Capture each modified setting and restore even after failures. Userdata is never wiped.
            originals = {}
            permission = None
            overlay_permission = None
            try:
                if adb('shell', 'getprop', 'ro.build.version.sdk') != str(api):
                    raise RuntimeError('wrong device API')
                if adb('shell', 'getprop', 'sys.boot_completed') != '1':
                    raise RuntimeError('AVD not booted')
                originals['font_scale'] = adb('shell', 'settings', 'get', 'system', 'font_scale')
                for key in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
                    originals[key] = adb('shell', 'settings', 'get', 'global', key)
                originals['size'] = adb('shell', 'wm', 'size')
                originals['density'] = adb('shell', 'wm', 'density')
                permission = adb('shell', 'appops', 'get', PACKAGE, 'REQUEST_INSTALL_PACKAGES', check=False)
                overlay_permission = adb('shell', 'appops', 'get', PACKAGE, 'SYSTEM_ALERT_WINDOW', check=False)
                adb('shell', 'am', 'force-stop', PACKAGE)
                # The HUD's own app-modification store survives a reinstall, and the preview digest is
                # deterministic, so a previous run's job record would be replayed instead of exercised.
                # Every frozen run therefore starts from a clean product install, like the fixtures below.
                adb('uninstall', PACKAGE, check=False)
                adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'); adb('shell', 'wm', 'dismiss-keyguard')
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
                adb('install', '-r', str(REPO / ARTIFACTS[0])); adb('install', '-r', str(REPO / ARTIFACTS[1]))
                adb('push', str(REPO / 'app/src/main/assets/app-control/appmod-gallery-fixture.apk'), '/sdcard/Download/owned-appmod-selection.apk')
                adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
                adb('shell', 'appops', 'set', PACKAGE, 'REQUEST_INSTALL_PACKAGES', 'allow')
                # Only the task's qualified clone is removed, allowing fresh-install then update assertions.
                adb('uninstall', 'com.mcpintelligence.fr3k.patchfixture.hudmod', check=False)
                logcat = subprocess.Popen([ADB, '-s', serial, 'logcat', '-v', 'threadtime', '-T', '1'], stdout=(run / prefix / 'platform.log').open('w'), stderr=subprocess.STDOUT)
                try:
                    for name, (klass, expected, markers) in ([(n, SCENARIOS[n]) for n in subset] if subset else list(SCENARIOS.items())):
                        key = f'{prefix}/{name}'; log = f'{key}.log'
                        result = dict(status='RUNNING', log=log, candidate=manifest['artifacts'])
                        manifest['scenarios'][key] = result; save()
                        try:
                            if name == 'browser_regression':
                                adb('shell', 'am', 'force-stop', PACKAGE)
                                adb('shell', 'appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
                            if name == 'installed_controls':
                                adb('install', '-r', str(REPO / 'app/src/main/assets/app-control/appmod-gallery-fixture.apk'))
                            if name == 'permission':
                                adb('shell', 'am', 'force-stop', PACKAGE)
                                adb('shell', 'appops', 'set', PACKAGE, 'REQUEST_INSTALL_PACKAGES', 'deny')
                            if name in ('restart_verify', 'platform_session_verify'):
                                adb('shell', 'am', 'force-stop', PACKAGE)
                            if name == 'real_preparation_verify':
                                death_path = run / prefix / 'intentional-process-death.log'
                                argv = [ADB, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.mcpintelligence.fr3k.appcontrol.AppModRestartDeviceTest#beginRealPreparationForHostProcessDeath', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
                                process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
                                timeout = threading.Timer(120, process.terminate); timeout.start()
                                stopped = False
                                try:
                                    with death_path.open('w') as stream:
                                        for line in process.stdout:
                                            stream.write(line); stream.flush()
                                            if 'APPMOD_REAL_PREPARING_CHECKPOINT' in line and not stopped:
                                                adb('shell', 'am', 'force-stop', PACKAGE)
                                                stream.write('\nRUNNER_EXPECTED_FORCE_STOP at=' + str(time.time_ns()) + '\n'); stream.flush()
                                                stopped = True
                                    process.wait(timeout=10)
                                    if not stopped:
                                        raise RuntimeError('preparation never reached real PREPARING checkpoint')
                                    result['intentionalProcessDeath'] = str(death_path.relative_to(run))
                                finally:
                                    timeout.cancel()
                                    if process.poll() is None:
                                        process.terminate(); process.wait(timeout=10)
                            if name == 'performance':
                                # The app-modification surface is not exported, so a shell cold start of it is
                                # refused by the platform. Cold process startup is measured on the exported
                                # entry point; the app-mod surface's own warm startup, scroll frames and
                                # main-thread disk I/O are asserted inside AppModPerformanceDeviceTest.
                                startup = adb('shell', 'am', 'start', '-W', '-S', '-n', PACKAGE + '/com.mcpintelligence.fr3k.ui.MainActivity')
                                measured = re.search(r'TotalTime: (\d+)', startup)
                                if 'Status: ok' not in startup or not measured or int(measured.group(1)) >= 10000:
                                    raise RuntimeError('cold startup missing or exceeds 10s emulator budget')
                                result['coldStartMs'] = int(measured.group(1))
                                result['coldStartSurface'] = 'com.mcpintelligence.fr3k.ui.MainActivity (exported launcher)'
                            if name == 'small_200':
                                adb('shell', 'wm', 'size', '720x1280'); adb('shell', 'wm', 'density', '320')
                                adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
                            if name == 'tablet_accessibility':
                                adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
                                adb('shell', 'wm', 'size', '1600x2560'); adb('shell', 'wm', 'density', '240')
                            if name == 'reduced_motion_accessibility':
                                for setting in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
                                    adb('shell', 'settings', 'put', 'global', setting, '0')
                            content = command([ADB, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', klass if klass.startswith('com.') else 'com.mcpintelligence.fr3k.appcontrol.' + klass, '-e', 'appmodUpdate', 'true' if name == 'update' else 'false', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], log, timeout=600)
                            result['tests'] = instrument_count(content, expected, markers)
                            result['status'] = 'PASS'
                        except Exception as failure:
                            result.update(status='FAIL', error=str(failure))
                            adb('shell', 'am', 'force-stop', PACKAGE, check=False)
                            adb('shell', 'input', 'keyevent', 'KEYCODE_BACK', check=False)
                        finally:
                            if name == 'installed_controls':
                                adb('uninstall', 'com.mcpintelligence.fr3k.patchfixturetwo', check=False)
                            if name == 'permission':
                                adb('shell', 'appops', 'set', PACKAGE, 'REQUEST_INSTALL_PACKAGES', 'allow')
                            save()
                    if args.live_github:
                        # Optional transport checks have their own status; deterministic gates remain separate.
                        live = dict(status='RUNNING', log=prefix + '/live-github.log', candidate=manifest['artifacts'])
                        manifest['live'][prefix] = live; save()
                        try:
                            content = command([ADB, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.mcpintelligence.fr3k.appcontrol.' + JOURNEY + '#publicResearchUsesLiveGithubTransport', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], live['log'], timeout=180)
                            live.update(status='PASS', tests=instrument_count(content, 1, ('APPMOD_LIVE_GITHUB_PASS',)))
                        except Exception as failure:
                            live.update(status='FAIL', error=str(failure))
                        save()
                finally:
                    logcat.terminate(); logcat.wait(timeout=10)
            finally:
                for key, value in originals.items():
                    if key in ('size', 'density'):
                        override = re.search(r'Override (?:size|density): ([0-9x]+)', value)
                        adb('shell', 'wm', key, override.group(1) if override else 'reset', check=False)
                    else:
                        namespace = 'system' if key == 'font_scale' else 'global'
                        adb('shell', 'settings', 'delete' if value == 'null' else 'put', namespace, key, *([] if value == 'null' else [value]), check=False)
                if overlay_permission is not None:
                    overlay_mode = re.search(r'SYSTEM_ALERT_WINDOW: (\w+)', overlay_permission)
                    adb('shell', 'appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', overlay_mode.group(1) if overlay_mode else 'default', check=False)
                if permission is not None:
                    mode = re.search(r'REQUEST_INSTALL_PACKAGES: (\w+)', permission)
                    adb('shell', 'appops', 'set', PACKAGE, 'REQUEST_INSTALL_PACKAGES', mode.group(1) if mode else 'default', check=False)
                adb('shell', 'rm', '-f', '/sdcard/Download/owned-appmod-selection.apk', check=False)
                adb('shell', 'run-as', PACKAGE, 'rm', '-f', 'cache/appmod-space-reservation', check=False)
                for key, value in originals.items():
                    if key in ('size', 'density'):
                        observed = adb('shell', 'wm', key)
                        expected_override = re.search(r'Override (?:size|density): ([0-9x]+)', value)
                        actual_override = re.search(r'Override (?:size|density): ([0-9x]+)', observed)
                        if (expected_override.group(1) if expected_override else None) != (actual_override.group(1) if actual_override else None):
                            raise RuntimeError('device geometry restoration failed: ' + key)
                    else:
                        namespace = 'system' if key == 'font_scale' else 'global'
                        if adb('shell', 'settings', 'get', namespace, key) != value:
                            raise RuntimeError('device setting restoration failed: ' + key)
                if permission is not None:
                    expected_mode = re.search(r'REQUEST_INSTALL_PACKAGES: (\w+)', permission)
                    observed_mode = re.search(r'REQUEST_INSTALL_PACKAGES: (\w+)', adb('shell', 'appops', 'get', PACKAGE, 'REQUEST_INSTALL_PACKAGES', check=False))
                    if (expected_mode.group(1) if expected_mode else 'default') != (observed_mode.group(1) if observed_mode else 'default'):
                        raise RuntimeError('installation permission restoration failed')

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(device, api, serial) for api, serial in ((31, 'emulator-5586'), (35, 'emulator-5584')) if not args.api or api in args.api]
            for future in futures:
                future.result()
        if snapshot() != manifest['source'] or any(sha256(REPO / p) != h for p, h in manifest['artifacts'].items()):
            raise RuntimeError('frozen candidate changed')
        manifest['status'] = 'PASS' if all(s['status'] == 'PASS' for s in manifest['scenarios'].values()) else 'FAIL'
    except Exception as failure:
        manifest.update(status='FAIL', error=str(failure))
    finally:
        manifest['evidence'] = {str(p.relative_to(run)): sha256(p) for p in sorted(run.rglob('*')) if p.is_file() and p.name != 'run-manifest.json' and not p.is_relative_to(tmp)}
        manifest['coverage'] = derive_coverage(run, manifest, REPO)
        if manifest['status'] == 'PASS' and any(row['status'] != 'PASS' for row in manifest['coverage'].values()):
            manifest.update(status='FAIL', error='mandatory assertion-backed coverage incomplete')
        save()
    print(json.dumps(dict(run=str(run), status=manifest['status'], jvm=manifest['jvm']), indent=2), flush=True)
    return 0 if manifest['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())

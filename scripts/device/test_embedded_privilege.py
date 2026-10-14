#!/usr/bin/env python3
"""Exercise the actual HUD-owned shell service on an explicitly selected test device."""
import argparse
import pathlib
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    def run(*parts):
        return subprocess.check_output(adb + list(parts), text=True, timeout=15).strip()
    hud = 'com.mcpintelligence.fr3k.hud'
    run('shell', 'am', 'force-stop', hud)
    source = run('shell', 'pm', 'path', hud).splitlines()[0].removeprefix('package:')
    assert source.startswith('/data/app/') and source.endswith('.apk'), source
    test = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class',
        'com.mcpintelligence.fr3k.appcontrol.EmbeddedPrivilegeDeviceTest',
        hud + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True)
    service = None
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if subprocess.run(adb + ['shell', 'pidof', hud], capture_output=True, timeout=5).stdout.strip():
                break
            if test.poll() is not None:
                raise RuntimeError('Instrumentation exited before target started')
            time.sleep(0.1)
        else:
            raise RuntimeError('Target process did not start')
        command = 'CLASSPATH=' + shlex.quote(source) + ' app_process /system/bin com.mcpintelligence.fr3k.privileged.HudPrivilegedMain'
        service = subprocess.Popen(adb + ['shell', command], stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True)
        output = test.communicate(timeout=50)[0]
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
        print(output)
        if test.returncode != 0 or 'OK (2 tests)' not in output or 'FAILURES' in output:
            raise RuntimeError('Embedded privilege device tests failed')
    finally:
        if test.poll() is None:
            test.kill()
            test.communicate()
        if service is not None:
            # The test calls shutdown; a failed test may require explicit shell-process cleanup.
            try:
                output = service.communicate(timeout=3)[0]
                print('SERVICE:', output)
            except subprocess.TimeoutExpired:
                service.terminate()
                service.communicate(timeout=5)

if __name__ == '__main__':
    main()

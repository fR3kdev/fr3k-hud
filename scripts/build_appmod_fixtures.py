#!/usr/bin/env python3
"""Build the owned app-mod fixtures and drop them into the bundled assets.

The fixtures are generated APKs, never committed. They used to be produced by hand on one machine,
so a fresh checkout failed the app-mod tests with FileNotFoundException. This script reproduces them
from the in-repo modules using whatever Android SDK this machine has:

    python3 scripts/build_appmod_fixtures.py          # build + copy
    python3 scripts/build_appmod_fixtures.py --check  # report what is missing, copy nothing
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/app-control'

# asset name -> (gradle module, build output path)
FIXTURES = {
    'appmod-media-fixture.apk': (':patch-fixture', 'patch-fixture/build/outputs/apk/debug/patch-fixture-debug.apk'),
    'owned-fixture.apk': (':patch-fixture', 'patch-fixture/build/outputs/apk/debug/patch-fixture-debug.apk'),
    'appmod-gallery-fixture.apk': (':patch-fixture-two', 'patch-fixture-two/build/outputs/apk/debug/patch-fixture-two-debug.apk'),
    'appmod-feature-split.apk': (':patch-fixture-two', 'patch-fixture-two/build/outputs/apk/debug/patch-fixture-two-debug.apk'),
}


def android_sdk() -> Path:
    for key in ('ANDROID_SDK_ROOT', 'ANDROID_HOME'):
        value = os.environ.get(key)
        if value and Path(value).is_dir():
            return Path(value)
    for candidate in (Path.home() / 'Android/Sdk', Path('/usr/lib/android-sdk')):
        if candidate.is_dir():
            return candidate
    raise SystemExit('No Android SDK found: set ANDROID_SDK_ROOT or ANDROID_HOME')


def java_home() -> Path:
    for key in ('JAVA_HOME',):
        value = os.environ.get(key)
        if value and Path(value, 'bin/javac').is_file():
            return Path(value)
    for candidate in sorted(Path('/usr/lib/jvm').glob('*17*')):
        if (candidate / 'bin/javac').is_file():
            return candidate
    raise SystemExit('No JDK 17 found: set JAVA_HOME')


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true', help='only report missing assets')
    args = parser.parse_args()

    ASSETS.mkdir(parents=True, exist_ok=True)
    missing = [name for name in FIXTURES if not (ASSETS / name).is_file()]
    if args.check:
        print('present:', sorted(name for name in FIXTURES if name not in missing))
        print('missing:', missing)
        return 1 if missing else 0

    env = dict(os.environ, JAVA_HOME=str(java_home()), ANDROID_SDK_ROOT=str(android_sdk()))
    env['PATH'] = str(Path(env['JAVA_HOME'], 'bin')) + os.pathsep + env['PATH']
    modules = sorted({module for module, _ in FIXTURES.values()})
    subprocess.run([str(ROOT / 'gradlew'), *[f'{m}:assembleDebug' for m in modules],
                    '--console=plain'], cwd=ROOT, env=env, check=True)

    for name, (_, output) in FIXTURES.items():
        source = ROOT / output
        if not source.is_file():
            raise SystemExit(f'{output} was not produced by the build')
        shutil.copy2(source, ASSETS / name)
        print(f'{name} <- {output}  sha256 {digest(ASSETS / name)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())

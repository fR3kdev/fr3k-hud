#!/usr/bin/env python3
"""Compile the owned hook module from source and refresh the bundled on-device asset."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/assets/app-control'
WORK = Path('/home/parrot/.hermes/cache/scratch/hud-hook-asset-build')
ANDROID = Path('/usr/lib/android-sdk/platforms/android-35/android.jar')
TOOLS = Path('/usr/lib/android-sdk/build-tools/35.0.1')
API = Path('/home/parrot/.hermes/cache/scratch/hud-fixture-qualified/xposed-api-82.jar')
MANIFEST_EXPECTED = 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if not API.is_file():
        raise SystemExit('Run scripts/qualify_lspatch_fixture.py once to download the pinned Xposed API jar')
    assert digest(API) == MANIFEST_EXPECTED, 'Xposed API artifact changed; review before using'
    env = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
    if WORK.exists():
        shutil.rmtree(WORK)
    classes = WORK / 'classes'
    dex = WORK / 'dex'
    classes.mkdir(parents=True)
    dex.mkdir(parents=True)
    module = ROOT / 'qualification/fixture-hook'
    javac = Path(env['JAVA_HOME']) / 'bin/javac'
    subprocess.run([str(javac), '-source', '8', '-target', '8', '-Xlint:-options',
                    '-classpath', str(ANDROID) + os.pathsep + str(API), '-d', str(classes),
                    str(module / 'FixtureHook.java')], env=env, check=True)
    compiled = [str(p) for p in sorted(classes.rglob('*.class'))]
    assert compiled, 'no classes compiled'
    subprocess.run([str(TOOLS / 'd8'), '--lib', str(ANDROID), '--classpath', str(API),
                    '--min-api', '31', '--output', str(dex), *compiled], env=env, check=True)
    unsigned = WORK / 'owned-fixture-hook-unsigned.apk'
    subprocess.run([str(TOOLS / 'aapt'), 'package', '-f', '-M', str(module / 'AndroidManifest.xml'),
                    '-I', str(ANDROID), '-A', str(module / 'assets'), '-F', str(unsigned)], env=env, check=True)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    with zipfile.ZipFile(unsigned) as archive:
        names = archive.namelist()
        assert 'classes.dex' in names and 'assets/xposed_init' in names, names
        assert archive.read('assets/xposed_init').decode().strip().endswith('FixtureHook')
    OUT.mkdir(parents=True, exist_ok=True)
    asset = OUT / 'owned-fixture-hook.apk'
    shutil.copy2(unsigned, asset)
    source = ROOT / 'patch-fixture/build/outputs/apk/debug/patch-fixture-debug.apk'
    if not source.is_file():
        raise SystemExit('Run ./gradlew :patch-fixture:assembleDebug first')
    target_asset = OUT / 'owned-fixture.apk'
    shutil.copy2(source, target_asset)
    print('HOOK_ASSET_SHA256', digest(asset))
    print('SOURCE_ASSET_SHA256', digest(target_asset))
    print('ASSET_BYTES', asset.stat().st_size)


if __name__ == '__main__':
    main()

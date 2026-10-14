#!/usr/bin/env python3
"""Build a module and prepare ONLY the repository-owned banner fixture; never installs it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--lspatch-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    source = root/'patch-fixture/build/outputs/apk/debug/patch-fixture-debug.apk'
    sdk = Path('/usr/lib/android-sdk')
    tools = sdk/'build-tools/35.0.1'
    android = sdk/'platforms/android-35/android.jar'
    env = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
    env['PATH'] = env['JAVA_HOME']+'/bin:'+env['PATH']
    def run(cmd):
        subprocess.run([str(x) for x in cmd], env=env, check=True)
    badging = subprocess.check_output([str(tools/'aapt'), 'dump', 'badging', str(source)], text=True)
    if "name='com.mcpintelligence.fr3k.patchfixture'" not in badging:
        raise RuntimeError('Only the owned patch fixture is authorised')
    api = out/'xposed-api-82.jar'
    if not api.exists():
        api.write_bytes(urllib.request.urlopen('https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar', timeout=30).read())
    digest = hashlib.sha256(api.read_bytes()).hexdigest()
    if digest != 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25':
        raise RuntimeError('Xposed API artifact changed; review before using')
    classes = out/'classes'
    dex = out/'dex'
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    module = root/'qualification/fixture-hook'
    run([Path(env['JAVA_HOME'])/'bin/javac', '-source', '8', '-target', '8',
        '-classpath', str(android)+os.pathsep+str(api), '-d', classes, module/'FixtureHook.java'])
    compiled = sorted(classes.rglob('*.class'))
    assert compiled
    run([tools/'d8', '--lib', android, '--classpath', api, '--min-api', '31', '--output', dex, *compiled])
    apk = out/'fixture-hook-unsigned.apk'
    run([tools/'aapt', 'package', '-f', '-M', module/'AndroidManifest.xml', '-I', android,
        '-A', module/'assets', '-F', apk])
    with zipfile.ZipFile(apk, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex/'classes.dex', 'classes.dex')
    # LSPatch embeds this module; no standalone module/manager APK is installed.
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    subprocess.run(['/usr/lib/jvm/java-25-openjdk-amd64/bin/java', '-jar', str(args.lspatch_jar.resolve()),
        '-l', '0', '-m', str(apk), '-o', str(out), '-f', str(source)], check=True)
    assert hashlib.sha256(source.read_bytes()).hexdigest() == before, 'Original fixture changed'
    patched = sorted(out.glob('*-lspatched.apk'))
    assert len(patched) == 1, patched
    run([tools/'apksigner', 'verify', '--verbose', str(patched[0])])
    with zipfile.ZipFile(patched[0]) as archive:
        config = json.loads(archive.read('assets/lspatch/config.json'))
        assert config['useManager'] is False and config['sigBypassLevel'] == 0, config
        assert any(n.startswith('assets/lspatch/modules/') for n in archive.namelist())
    report = {'fixture_only': True, 'source_sha256': before,
        'patched_apk': str(patched[0]), 'patched_sha256': hashlib.sha256(patched[0].read_bytes()).hexdigest(),
        'engine_jar_sha256': hashlib.sha256(args.lspatch_jar.read_bytes()).hexdigest(),
        'manager_mode': False, 'signature_bypass': 0, 'installed': False,
        'runtime_verified': False}
    (out/'qualification.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()

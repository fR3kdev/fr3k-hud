#!/usr/bin/env python3
"""Package the verified app-control candidate; never install or refresh the fleet implicitly."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release-dir', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
    sdk = Path('/usr/lib/android-sdk/build-tools/35.0.1')
    signature = subprocess.check_output([str(sdk/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    badging = subprocess.check_output([str(sdk/'aapt'), 'dump', 'badging', str(apk)], text=True)
    if "versionCode='417'" not in badging or "versionName='0.4.17'" not in badging:
        raise RuntimeError('Expected the built 0.4.17/417 candidate')
    tests = {}
    for module in ('core', 'app', 'patch-fixture'):
        results = list((root/module/'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
        if not results:
            raise RuntimeError('No JVM results for ' + module)
        suites = [ET.parse(p).getroot() for p in results]
        tests[module] = {'tests': sum(int(x.get('tests', 0)) for x in suites),
            'failures': sum(int(x.get('failures', 0)) + int(x.get('errors', 0)) for x in suites)}
        if tests[module]['failures']:
            raise RuntimeError('Failing JVM tests for ' + module)
    scratch = Path('/home/parrot/.hermes/cache/scratch')
    for name in ('hud-embedded-default-instrumentation.txt', 'hud-inspect-default-instrumentation.txt'):
        result = (scratch/name).read_text()
        if 'OK (2 tests)' not in result or 'FAILURES' in result:
            raise RuntimeError('Missing passing device evidence: ' + name)
    out = args.release_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    dest = out/'fr3k-hud-0.4.17-debug.apk'
    artifacts = root/'artifacts'
    for target in (dest, artifacts/'FR3K-HUD-0.4.17.apk'):
        if target.exists() and sha(target) != sha(apk):
            raise RuntimeError('Refusing to overwrite a different published candidate: ' + str(target))
        shutil.copy2(apk, target)
        assert sha(target) == sha(apk)
    metadata = {'versionName': '0.4.17', 'versionCode': 417, 'sha256': sha(apk),
        'bytes': apk.stat().st_size, 'source_commit': subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(),
        'source_tree': 'Includes preserved pre-existing dirty BLACKWAVE work; not a clean source-tree claim',
        'signature': 'APK_V2_VERIFIED_DEBUG', 'jvm_tests': tests,
        'emulator_api35_tests': {'embedded_service': 2, 'inspection_ui': 2},
        'hardware_tested': False, 'launcher_install_tested': False,
        'capabilities': ['selected_package_identity', 'shell_uid_2000_read_only_service', 'local_inspection_ui', 'agent_inspect_and_plan'],
        'not_implemented': ['wireless_adb_pairing_ui', 'embedded_apk_patcher', 'target_hook_runtime', 'youtube_recipe', 'apply_and_undo'],
        'full_objective': 'OPEN'}
    (out/'validation.json').write_text(json.dumps(metadata, indent=2)+'\n')
    (out/'apksigner.txt').write_text(signature)
    (out/'SHA256SUMS').write_text(''.join(f'{sha(out/name)}  {name}\n' for name in (dest.name, 'validation.json', 'apksigner.txt')))
    (artifacts/'FR3K-HUD-0.4.17.apk.sha256').write_text(f'{sha(apk)}  FR3K-HUD-0.4.17.apk\n')
    (artifacts/'FR3K-HUD-0.4.17.apk.meta.json').write_text(json.dumps(metadata, indent=2)+'\n')
    evidence = out/'evidence'
    evidence.mkdir(exist_ok=True)
    for name in ('hud-final-build.log', 'hud-embedded-instrumentation.txt', 'hud-embedded-default-instrumentation.txt',
                 'hud-inspect-default-instrumentation.txt', 'hud-app-control-default.png'):
        shutil.copy2(scratch/name, evidence/name)
    print(json.dumps(metadata, indent=2))
    print('RELEASE', out)

if __name__ == '__main__':
    main()

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
    import re
    version_name = re.search(r"versionName='([^']+)'", badging).group(1)
    version_code = int(re.search(r"versionCode='(\d+)'", badging).group(1))
    if not re.match(r'^0\.4\.\d+$', version_name) or version_code < 417:
        raise RuntimeError('Unexpected candidate %s/%s' % (version_name, version_code))
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
    dest = out/('fr3k-hud-%s-debug.apk' % version_name)
    artifacts = root/'artifacts'
    for target in (dest, artifacts/('FR3K-HUD-%s.apk' % version_name)):
        if target.exists() and sha(target) != sha(apk):
            raise RuntimeError('Refusing to overwrite a different published candidate: ' + str(target))
        shutil.copy2(apk, target)
        assert sha(target) == sha(apk)
    metadata = {'versionName': version_name, 'versionCode': version_code, 'sha256': sha(apk),
        'bytes': apk.stat().st_size, 'source_commit': subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(),
        'source_tree': 'Includes preserved pre-existing dirty BLACKWAVE work; not a clean source-tree claim',
        'signature': 'APK_V2_VERIFIED_DEBUG', 'jvm_tests': tests,
        'emulator_api35_tests': {'embedded_service': 2, 'inspection_ui': 2, 'on_device_patcher': 1,
                                 'owned_controller_ui': 1, 'owned_agent_approval': 1},
        'hardware_tested': False, 'launcher_install_tested': False,
        'capabilities': ['selected_package_identity', 'shell_uid_2000_read_only_service', 'local_inspection_ui',
                          'agent_inspect_and_plan', 'on_device_apk_patching_owned_fixture',
                          'on_device_keystore_signing', 'owned_fixture_live_method_control', 'agent_approved_owned_write'],
        'not_implemented': ['wireless_adb_pairing_ui', 'third_party_target_recipes', 'signature_bypass',
                            'youtube_recipe', 'general_apply_and_undo_for_arbitrary_apps'],
        'full_objective': 'OPEN'}
    (out/'validation.json').write_text(json.dumps(metadata, indent=2)+'\n')
    (out/'apksigner.txt').write_text(signature)
    (out/'SHA256SUMS').write_text(''.join(f'{sha(out/name)}  {name}\n' for name in (dest.name, 'validation.json', 'apksigner.txt')))
    stem = 'FR3K-HUD-%s.apk' % version_name
    (artifacts/(stem + '.sha256')).write_text(f'{sha(apk)}  {stem}\n')
    (artifacts/(stem + '.meta.json')).write_text(json.dumps(metadata, indent=2)+'\n')
    evidence = out/'evidence'
    evidence.mkdir(exist_ok=True)
    for name in ('hud-final-build.log', 'hud-embedded-default-instrumentation.txt',
                 'hud-AppInspectionDeviceTest-instrumentation.txt', 'hud-FixtureAgentApprovalDeviceTest-instrumentation.txt',
                 'hud-owned-controller-instrumentation.txt', 'hud-ondevice-patch-instrumentation.txt',
                 'hud-app-control-default.png', 'hud-app-control-bottom.png',
                 'owned-banner-hidden.png', 'owned-banner-restored.png'):
        shutil.copy2(scratch/name, evidence/name)
    print(json.dumps(metadata, indent=2))
    print('RELEASE', out)

if __name__ == '__main__':
    main()

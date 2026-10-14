#!/usr/bin/env python3
"""Package the current fR3k HUD debug build as the benchmark candidate.

One command, and it leaves a trail: the release directory (APK + SHA256SUMS + apksigner output +
validation.json), the ignored copy under `artifacts/`, the HUD row in the active benchmark selection, the
HUD row in the benchmark manifest, and a re-verification of every other artifact hash in that manifest.

It deliberately touches the HUD row only: firmware rows and operator results are somebody else's contract.

Usage:
  python3 scripts/release/package_hud_release.py --status DEVICE_VERIFIED_UI_AND_ALL_PLANES_EMULATOR
  python3 scripts/release/package_hud_release.py --no-build            # reuse the existing APK
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path('/home/parrot/repos/fr3k-hud')
RELEASE_ROOT = Path('/home/parrot/worktrees/blackwave-unified-20261012/releases')
SELECTION = RELEASE_ROOT / 'benchmark-selection.json'
BENCH = Path('/home/parrot/FR3K-BENCHMARK')
MANIFEST = BENCH / 'manifest.json'
SDK = Path('/usr/lib/android-sdk')
BUILD_TOOLS = '35.0.1'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def module_test_counts():
    counts = {}
    for result in sorted(REPO.glob('*/build/test-results/*/TEST-*.xml')):
        # Paths from an absolute glob start with '/', so take the module from the path relative to the repo.
        module = result.relative_to(REPO).parts[0]
        totals = counts.setdefault(module, {'tests': 0, 'failures': 0, 'errors': 0})
        top = ET.parse(result).getroot()
        totals['tests'] += int(top.get('tests', 0))
        totals['failures'] += int(top.get('failures', 0))
        totals['errors'] += int(top.get('errors', 0))
    return counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--status', required=True, help='benchmark status string for the HUD row')
    parser.add_argument('--no-build', action='store_true')
    parser.add_argument('--version-dir', default='ui-redesign-2026-10-14')
    args = parser.parse_args()

    gradle = REPO / 'gradlew'
    env = {'JAVA_HOME': '/usr/lib/jvm/java-17-openjdk-amd64', 'ANDROID_HOME': str(SDK),
           'PATH': '/usr/lib/jvm/java-17-openjdk-amd64/bin:/usr/bin:/bin'}
    if not args.no_build:
        subprocess.run([str(gradle), ':app:assembleDebug', '--console=plain'], cwd=REPO, env=env, check=True)

    apk = REPO / 'app/build/outputs/apk/debug/app-debug.apk'
    if not apk.exists():
        sys.exit('no APK at %s' % apk)
    gradle_source = (REPO / 'app/build.gradle.kts').read_text()
    name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_source)
    code_match = re.search(r'versionCode\s*=\s*(\d+)', gradle_source)
    if not name_match or not code_match:
        sys.exit('could not read versionName/versionCode from app/build.gradle.kts')
    version_name = name_match.group(1)
    version_code = int(code_match.group(1))
    digest = sha256(apk)
    size = apk.stat().st_size
    commit = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=REPO, capture_output=True, text=True).stdout.strip()

    # 1. release directory
    release_dir = RELEASE_ROOT / args.version_dir / 'hud'
    release_dir.mkdir(parents=True, exist_ok=True)
    target = release_dir / ('fr3k-hud-%s-debug.apk' % version_name)
    shutil.copy2(apk, target)
    (release_dir / 'SHA256SUMS').write_text('%s  %s\n' % (digest, target.name))

    apksigner = SDK / ('build-tools/%s/apksigner' % BUILD_TOOLS)
    signer_out = subprocess.run([str(apksigner), 'verify', '--print-certs', '-v', str(target)],
                                capture_output=True, text=True)
    (release_dir / 'apksigner.txt').write_text(signer_out.stdout + signer_out.stderr)

    for evidence in ('capability-lab-evidence.json', 'capability-bridge-evidence.json'):
        source = REPO / 'artifacts' / evidence
        if source.exists():
            shutil.copy2(source, release_dir / ('hud-redesign-%s' % evidence.replace('-evidence', '')))

    tests = module_test_counts()
    validation = {
        'versionName': version_name,
        'versionCode': version_code,
        'sha256': digest,
        'bytes': size,
        'source_commit': commit,
        'source_tree': 'Committed in this repository at the recorded source_commit; the tree also carries\n'
                       'pre-existing BLACKWAVE work, so this is not a clean-tree reproducibility claim. '
                       'See docs/design/VERIFICATION-REPORT.md and docs/design/CONTINUATION.md.',
        'signature': 'APK_V2_VERIFIED_DEBUG' if 'Verified' in signer_out.stdout else 'APK_SIGNATURE_VERIFICATION_FAILED',
        'jvm_tests': tests,
        'emulator_evidence': {
            'accessibility_and_performance': 'docs/design/evidence/a11y-perf.json',
            'release_ui_and_live': 'docs/design/evidence/release-ui-and-live.txt',
            'capability_lab': 'docs/design/evidence/capability-lab.log',
            'verification_report': 'docs/design/VERIFICATION-REPORT.md',
        },
        'hardware_tested': False,
        'full_objective': args.status,
        'continuation': str(REPO / 'docs/design/CONTINUATION.md'),
    }
    (release_dir / 'validation.json').write_text(json.dumps(validation, indent=2) + '\n')

    # 2. ignored artifact copy
    artifact = REPO / 'artifacts' / ('FR3K-HUD-%s.apk' % version_name)
    shutil.copy2(target, artifact)
    (REPO / 'artifacts' / ('FR3K-HUD-%s.apk.sha256' % version_name)).write_text(
        '%s  %s\n' % (digest, artifact.name))
    meta = {k: validation[k] for k in ('versionName', 'versionCode', 'sha256', 'bytes', 'source_commit',
                                       'source_tree', 'signature', 'jvm_tests')}
    (REPO / 'artifacts' / ('FR3K-HUD-%s.apk.meta.json' % version_name)).write_text(json.dumps(meta, indent=2) + '\n')

    # 3. the HUD row in the active selection, and nothing else
    selection = json.loads(SELECTION.read_text())
    source_rel = 'releases/%s/hud/%s' % (args.version_dir, target.name)
    for entry in selection:
        if entry.get('target') == 'hud':
            entry['source'] = source_rel
            entry['status'] = args.status
            entry['version'] = version_name
            break
    else:
        sys.exit('no hud row in %s' % SELECTION)
    SELECTION.write_text(json.dumps(selection, indent=2) + '\n')

    # 4. the HUD row in the benchmark manifest, then re-verify every hash it claims
    manifest = json.loads(MANIFEST.read_text())
    for entry in manifest['artifacts']:
        if entry.get('target') == 'hud':
            entry['source'] = source_rel
            entry['status'] = args.status
            entry['version'] = version_name
            entry['sha256'] = digest
            entry['bytes'] = size
            entry['validation_report'] = 'evidence/hud-latest-debug.apk.validation.json'
            entry['evidence'] = 'evidence/hud-redesign-CONTINUATION.md'
            break
    MANIFEST.write_text(json.dumps(manifest, indent=2) + '\n')

    shutil.copy2(target, BENCH / 'hud-latest-debug.apk')
    (BENCH / 'hud-latest-debug.apk.sha256').write_text('%s  hud-latest-debug.apk\n' % digest)
    # Keep the aggregate sums file honest for the HUD line only; every other row is another contract.
    sums = BENCH / 'SHA256SUMS'
    if sums.exists():
        lines = sums.read_text().splitlines()
        sums.write_text('\n'.join(
            ('%s  hud-latest-debug.apk' % digest) if line.strip().endswith('hud-latest-debug.apk') else line
            for line in lines) + '\n')
    shutil.copy2(release_dir / 'validation.json', BENCH / 'evidence/hud-latest-debug.apk.validation.json')
    shutil.copy2(release_dir / 'apksigner.txt', BENCH / ('evidence/hud-%s-apksigner.txt' % version_name))
    shutil.copy2(REPO / 'docs/design/CONTINUATION.md', BENCH / 'evidence/hud-redesign-CONTINUATION.md')
    verification = REPO / 'docs/design/VERIFICATION-REPORT.md'
    if verification.exists():
        shutil.copy2(verification, BENCH / 'evidence/hud-redesign-VERIFICATION-REPORT.md')
    for name in ('capability-lab-evidence.json', 'capability-bridge-evidence.json'):
        source = REPO / 'artifacts' / name
        if source.exists():
            shutil.copy2(source, BENCH / 'evidence' / ('hud-redesign-%s' % name))

    checked = mismatched = 0
    for entry in manifest['artifacts']:
        claimed = entry.get('sha256')
        if not claimed:
            continue
        path = BENCH / entry['filename']
        if not path.exists():
            print('MISSING  %-24s %s' % (entry['target'], path))
            mismatched += 1
            continue
        actual = sha256(path)
        checked += 1
        if actual != claimed:
            print('MISMATCH %-24s claimed=%s actual=%s' % (entry['target'], claimed[:16], actual[:16]))
            mismatched += 1

    print('hud %s  sha256=%s  bytes=%d' % (version_name, digest, size))
    print('release %s' % release_dir)
    print('hashes re-verified: %d checked, %d mismatched' % (checked, mismatched))
    return 1 if mismatched else 0


if __name__ == '__main__':
    sys.exit(main())

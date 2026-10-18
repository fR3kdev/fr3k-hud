#!/usr/bin/env python3
"""Package only a complete, matching frozen acceptance run; promote only the HUD row."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'appmod'))
from evidence import REPO, sha256, validate, write_json

SELECTION = Path('/home/parrot/worktrees/blackwave-unified-20261012/releases/benchmark-selection.json')
SDK = Path('/usr/lib/android-sdk/build-tools/35.0.1')
APK_NAME = 'hud-latest-debug.apk'


def checksums(folder):
    (folder / 'SHA256SUMS').write_text(''.join(f'{sha256(p)}  {p.relative_to(folder)}\n' for p in sorted(folder.rglob('*')) if p.is_file() and p.name != 'SHA256SUMS'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True, help='unique run directory containing run-manifest.json')
    parser.add_argument('--update-selection', action='store_true')
    args = parser.parse_args()
    try:
        manifest = validate(args.work)
    except (ValueError, KeyError, OSError) as failure:
        sys.exit('verified packaging refused: ' + str(failure))
    out = args.release_dir.resolve()
    if out.exists():
        sys.exit('refusing to overwrite release: ' + str(out))
    if args.update_selection and not out.is_relative_to(SELECTION.parents[1] / 'releases'):
        sys.exit('selected package must be inside BLACKWAVE worktree releases')
    apk = REPO / 'app/build/outputs/apk/debug/app-debug.apk'
    signature = subprocess.check_output([str(SDK / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True, stderr=subprocess.STDOUT)
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in signature:
        sys.exit('candidate signature not v2-verified')
    badging = subprocess.check_output([str(SDK / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    version = re.search(r"versionName='([^']+)'", badging).group(1)
    package = re.search(r"package: name='([^']+)'", badging).group(1)
    if package != 'com.mcpintelligence.fr3k.hud':
        sys.exit('unexpected APK package')
    selection_before = SELECTION.read_text()
    out.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='.appmod-package-', dir=out.parent))
    try:
        folder = stage / 'hud'; folder.mkdir()
        evidence = stage / 'evidence'; evidence.mkdir()
        for relative in manifest['evidence']:
            dest = evidence / relative; dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(args.work / relative, dest)
        shutil.copy2(args.work / 'run-manifest.json', evidence / 'run-manifest.json')
        (evidence / 'selection-before.json').write_text(selection_before)
        write_json(evidence / 'device-test-ledger.json', manifest['scenarios'])
        write_json(evidence / 'coverage-matrix.json', manifest['coverage'])
        shutil.copy2(apk, folder / APK_NAME)
        if sha256(folder / APK_NAME) != manifest['artifacts'][str(apk.relative_to(REPO))]:
            raise ValueError('candidate changed during packaging')
        (folder / 'apksigner.txt').write_text(signature)
        write_json(folder / 'source-manifest.json', manifest['source'])
        write_json(folder / 'fixture-helper-manifest.json', manifest['artifacts'])
        shutil.copy2(args.work / 'dependency-verification.json', folder / 'dependency-manifest.json')
        validation = dict(status='DETERMINISTIC_ACCEPTANCE_PASS_PHYSICAL_UNTESTED', sha256=sha256(apk), bytes=apk.stat().st_size,
                          package=package, versionName=version, jvm=manifest['jvm'], device_tests=manifest['scenarios'],
                          hardware_tested=False, run=args.work.name, live_checks=manifest.get('live', {}),
                          not_tested=['physical phone', 'manual TalkBack', 'physical performance', 'configured live AI', 'public distribution licensing'])
        write_json(folder / 'validation.json', validation)
        # Ledgers precede checksums, including the selected run manifest itself.
        checksums(folder); checksums(evidence)
        validate(args.work)  # Reject any source/evidence changes during staging.
        stage.rename(out)
        if args.update_selection:
            if SELECTION.read_text() != selection_before:
                raise ValueError('benchmark selection changed concurrently; package retained without promotion')
            selection = json.loads(selection_before)
            row = next(r for r in selection if r.get('target') == 'hud')
            row.update(source=str((out / 'hud' / APK_NAME).relative_to(SELECTION.parents[1])), filename=APK_NAME,
                       status=validation['status'], version=version,
                       use='App modification debug candidate; mandatory deterministic acceptance measured against frozen APK. Physical phone, manual TalkBack, physical performance, configured live AI and public distribution licensing remain separate gates.')
            write_json(SELECTION, selection)
        print(json.dumps(dict(release=str(out), sha256=validation['sha256'], jvm=sum(v['tests'] for v in manifest['jvm'].values())), indent=2))
    finally:
        if stage.exists():
            shutil.rmtree(stage)


if __name__ == '__main__':
    main()

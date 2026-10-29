#!/usr/bin/env python3
"""Regenerate APK delivery evidence for the HUD voice candidate.

Computes the real sha256, signer digest, badging, zipalign result and ARM64 16 KiB
LOAD alignment straight from the built APK. No values are carried over from earlier runs.
"""
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

SDK = Path('/home/fr3k/Android/Sdk')
BUILD_TOOLS = SDK / 'build-tools/35.0.1'
APK_SRC = Path('/home/fr3k/BLACKWAVE-STANDALONE/work/fr3k-hud/app/build/outputs/apk/debug/app-debug.apk')
APK_DST = Path('/home/fr3k/FR3K-HUD-0.4.16-voice.apk')
EVIDENCE = Path('/home/fr3k/BLACKWAVE-STANDALONE/work/fr3k-hud/evidence/2026-10-29/delivery-apk-verification-xiaozhi.json')


def run(*args):
    return subprocess.run([str(a) for a in args], capture_output=True, text=True)


def main():
    shutil.copy2(APK_SRC, APK_DST)
    sha_src = hashlib.sha256(APK_SRC.read_bytes()).hexdigest()
    sha_dst = hashlib.sha256(APK_DST.read_bytes()).hexdigest()
    assert sha_src == sha_dst, 'delivered APK differs from the built APK'

    certs = run(BUILD_TOOLS / 'apksigner', 'verify', '--print-certs', APK_DST)
    badging = run(BUILD_TOOLS / 'aapt2', 'dump', 'badging', APK_DST).stdout.splitlines()[:6]
    align = run(SDK / 'build-tools/35.0.0/zipalign', '-c', '-P', '16', '-v', '4', APK_DST)

    native = {}
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(APK_DST) as apk:
            libs = [n for n in apk.namelist() if n.startswith('lib/arm64-v8a/') and n.endswith('.so')]
            for name in libs:
                apk.extract(name, tmp)
                out = run('llvm-readelf', '-lW', Path(tmp) / name).stdout
                native[name] = sorted({line.split()[-1] for line in out.splitlines()
                                       if line.strip().startswith('LOAD')})

    report = {
        'build_apk': str(APK_SRC),
        'delivery_apk': str(APK_DST),
        'sha256': sha_dst,
        'bytes': APK_DST.stat().st_size,
        'signature': [line.strip() for line in certs.stdout.splitlines()
                      if 'certificate SHA-256 digest' in line],
        'badging': badging,
        'zipalign_16k': [line for line in align.stdout.splitlines() if line.strip()][-1:],
        'native_alignment': native,
        'all_arm64_load_segments_page_aligned': all(
            all(int(a, 16) % 0x4000 == 0 for a in aligns) and aligns for aligns in native.values()),
    }
    assert report['all_arm64_load_segments_page_aligned'], json.dumps(native, indent=1)
    assert report['signature'], certs.stderr
    EVIDENCE.write_text(json.dumps(report, indent=1))
    print(json.dumps({k: report[k] for k in
                      ('sha256', 'bytes', 'signature', 'zipalign_16k', 'all_arm64_load_segments_page_aligned')}, indent=1))


if __name__ == '__main__':
    main()

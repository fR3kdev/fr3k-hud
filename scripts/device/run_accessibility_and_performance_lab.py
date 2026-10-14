#!/usr/bin/env python3
"""Accessibility, 200% font scale, reduced motion and performance measurements on a real device.

Honest about what it does NOT prove: TalkBack is not installed, so no speech-traversal claim is made.
Every number here is read back from the device after the work, not asserted from the source.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

HUD = 'com.mcpintelligence.fr3k.hud'
RUNNER = HUD + '.test/androidx.test.runner.AndroidJUnitRunner'
A11Y_CLASS = 'com.mcpintelligence.fr3k.ui.AccessibilityAndScaleDeviceTest'
FLOAT_CLASS = 'com.mcpintelligence.fr3k.ui.FloatingSurfaceDeviceTest'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    results = {}
    detail = {}

    def sh(cmd, timeout=180, check=False):
        return subprocess.run(adb + ['shell', cmd], capture_output=True, text=True, timeout=timeout, check=check).stdout

    def record(name, ok, note=''):
        results[name] = {'ok': bool(ok), 'note': str(note)[:500]}
        print('%-52s %s  %s' % (name, 'PASS' if ok else 'FAIL', str(note)[:140]))
        return bool(ok)

    def instrument(cls, timeout=600):
        proc = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class', cls, RUNNER],
                              capture_output=True, text=True, timeout=timeout)
        text = proc.stdout + proc.stderr
        ok = bool(re.search(r'OK \(\d+ test', text)) and 'FAILURES' not in text
        return ok, text

    def set_font(scale):
        sh('settings put system font_scale %s' % scale)
        sh('am force-stop %s' % HUD)
        time.sleep(1.5)

    def set_motion(enabled):
        # 0 removes animation duration entirely: the reduced-motion case.
        for key in ('animator_duration_scale', 'transition_animation_scale', 'window_animation_scale'):
            sh('settings put global %s %s' % (key, '1.0' if enabled else '0'))

    def cold_start(label):
        sh('am force-stop %s' % HUD)
        time.sleep(1.0)
        out = sh('am start -W -n %s/com.mcpintelligence.fr3k.ui.MainActivity' % HUD)
        values = {k: int(v) for k, v in re.findall(r'(TotalTime|WaitTime|ThisTime): (\d+)', out)}
        detail.setdefault('coldStart', {})[label] = values
        return values

    def frame_stats(label):
        out = sh('dumpsys gfxinfo %s' % HUD)
        total = re.search(r'Total frames rendered: (\d+)', out)
        janky = re.search(r'Janky frames: (\d+)', out)
        p90 = re.search(r'90th percentile: (\d+)ms', out)
        stats = {
            'totalFrames': int(total.group(1)) if total else None,
            'jankyFrames': int(janky.group(1)) if janky else None,
            'p90ms': int(p90.group(1)) if p90 else None,
        }
        detail.setdefault('frames', {})[label] = stats
        return stats

    def scroll_for_frames():
        bounds = re.search(r'(\d+)x(\d+)', sh('wm size'))
        w, h = (int(bounds.group(1)), int(bounds.group(2))) if bounds else (1080, 1920)
        for _ in range(12):
            sh('input swipe %d %d %d %d 220' % (w // 2, int(h * 0.75), w // 2, int(h * 0.35)))
            time.sleep(0.25)

    baseline_font = '1.0'
    try:
        baseline_font = sh('settings get system font_scale').strip() or '1.0'
        detail['baselineFontScale'] = baseline_font

        # ---------------------------------------------------------------- 1. current scale
        set_font(baseline_font)
        sh('am force-stop %s' % HUD)
        ok, text = instrument(A11Y_CLASS)
        (args.log.parent / 'a11y-at-current-scale.txt').write_text(text)
        record('accessibility + clipping at current font scale', ok, text.strip().splitlines()[-1] if text.strip() else 'no output')

        # ---------------------------------------------------------------- 2. 200% font scale
        set_font('2.0')
        ok200, text200 = instrument(A11Y_CLASS)
        (args.log.parent / 'a11y-at-200-percent.txt').write_text(text200)
        record('no unlabelled control and nothing clipped at 200% font', ok200,
               text200.strip().splitlines()[-1] if text200.strip() else 'no output')

        # ---------------------------------------------------------------- 3. reduced motion
        set_motion(False)
        ok_motion, text_motion = instrument(FLOAT_CLASS, timeout=420)
        (args.log.parent / 'reduced-motion.txt').write_text(text_motion)
        record('floating tools still work with animations disabled', ok_motion,
               text_motion.strip().splitlines()[-1] if text_motion.strip() else 'no output')
        set_motion(True)

        # ---------------------------------------------------------------- 4. restore scale, then measure
        set_font(baseline_font)
        first = cold_start('cold-with-existing-install')
        record('cold start measured', first.get('TotalTime', 0) > 0,
               'TotalTime=%sms WaitTime=%sms' % (first.get('TotalTime'), first.get('WaitTime')))

        sh('am start -n %s/com.mcpintelligence.fr3k.ui.MainActivity' % HUD)
        time.sleep(2)
        sh('dumpsys gfxinfo %s reset' % HUD)
        scroll_for_frames()
        idle = frame_stats('after-scroll')
        record('frame timing measured while scrolling', idle.get('totalFrames') is not None,
               'frames=%s janky=%s p90=%sms' % (idle.get('totalFrames'), idle.get('jankyFrames'), idle.get('p90ms')))

        mem = sh('dumpsys meminfo %s' % HUD)
        pss = re.search(r'TOTAL PSS:\s+(\d+)', mem) or re.search(r'TOTAL\s+(\d+)', mem)
        detail['memory'] = {'totalPssKb': int(pss.group(1)) if pss else None}
        record('memory footprint recorded', detail['memory']['totalPssKb'] is not None,
               'TOTAL PSS=%s kB' % detail['memory']['totalPssKb'])

    finally:
        set_font(baseline_font)
        set_motion(True)
        failed = [k for k, v in results.items() if not v['ok']]
        args.log.parent.mkdir(parents=True, exist_ok=True)
        args.log.write_text(json.dumps({'results': results, 'detail': detail}, indent=2) + '\n')
        print('A11Y_PERF_RESULT', 'PASS' if not failed else 'FAIL ' + ','.join(failed))
    return 1 if any(not v['ok'] for v in results.values()) else 0


if __name__ == '__main__':
    sys.exit(main())

# Handover to the next agent — app-mod acceptance, 2026-10-18 (19:35 AEST)

Read this first, then `docs/app-control/USAGE-LIMIT-HANDOVER-2026-10-18.md` (the original task list) and
`GITHUB-BACKUP-2026-10-18.md`. Your job is the one item still open in all of them: **produce a promotable
frozen acceptance run, package HUD only, sync the benchmark, verify the 18 hashes, and finish the docs.**

Nothing in this file is a plan to be redone. Everything marked VERIFIED was measured on a device or in a JVM
gate in this session; everything marked OPEN has not been measured and must not be asserted as working.

---

## 1. Repository and push state

- Repo: `/home/parrot/repos/fr3k-hud` (branch `checkpoint/2026-10-18-hud`).
- Commits this session, oldest first:
  `984a937` runner hardening + harness repair, `49fbb81` hand-off windows and page state `a1a6eb9` page
  scroller + keyboard, `e72a7bf` docs of verified fixes, `c741c16` merged labels / driven surface / one-pass
  traversal, `1be03db` wait for the product's own review launch + never tap a part-clipped control.
- Working tree additionally holds the **uncommitted** harness delta (findTarget visibility preference +
  `walkMatch` manual substring walk + `ACTION_CLICK` for a clipped control). It is committed by this handover's
  commit; `git log -1 --stat` confirms what is in.
- `origin` is `https://github.com/fr3kchy/fr3k-hud.git` and **push is refused (403)** with the active credential.
  Push instead to `https://github.com/fr3k-d3v/fr3k-ecosystem-checkpoint.git`
  `HEAD:refs/heads/checkpoint/2026-10-18-hud` (this is how every commit above got off the host). Never write a
  token, password or connection string into any file, commit message or document.
- Untracked junk in the tree: `scripts/appmod/__pycache__/`, `scripts/release/__pycache__/` — ignore, do not commit.

## 2. Environment and the exact commands

```bash
cd /home/parrot/repos/fr3k-hud
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/usr/lib/android-sdk
export TMPDIR=/home/parrot/.cache/hud-appmod/tmp
export JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/home/parrot/.cache/hud-appmod/tmp

# host gates (must stay green; counts come from the XML, not from stdout)
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain -Pkotlin.incremental=false

# one scenario on one device, clean product state, real fixture, full logcat:
bash /home/parrot/.cache/hud-appmod/targeted_probe.sh <serial> <TestName> <tag> [class] [font_scale]
#   serial is emulator-5586 (API31) or emulator-5584 (API35); font_scale defaults to 1.0
#   outputs /home/parrot/.cache/hud-appmod/target-<serial>-<TestName>-<tag>{,-instr,-run}.log

# diagnostic runner (NEVER promotable: it writes `diagnostic` into the manifest)
python3 scripts/appmod/run_acceptance.py --only <comma,names> [--api 31] [--work <dir>]

# the real thing, only after a green preflight
bash /home/parrot/.cache/hud-appmod/preflight_then_full.sh   # preflight, then chains into:
python3 scripts/appmod/run_acceptance.py --live-github

# packaging + promotion (only accepts a validated, complete, matching frozen run)
python3 scripts/release/package_appmod_hud_release.py \
  --release-dir /home/parrot/worktrees/blackwave-unified-20261012/releases/<new-name> \
  --work /home/parrot/.cache/hud-appmod/runs/<frozen-run> --update-selection
```

AVDs and how they were started (they are **not** auto-started for you):

```bash
/usr/lib/android-sdk/emulator/emulator -avd codex_appmod_api31_20261016 -port 5586 \
  -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &
/usr/lib/android-sdk/emulator/emulator -avd codex_appmod_api35_20261016 -port 5584 \
  -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &
```

## 3. Done — and measured

- Runner hardening in `scripts/appmod/run_acceptance.py` (VERIFIED): every frozen run uninstalls the product
  before installing the artifacts (the app-mod store survives reinstall, so a stale job record replayed and made
  run3's journey/update fail); cold start now measures the exported launcher
  `com.mcpintelligence.fr3k.ui.MainActivity` with `am start -W -S` and refuses a missing `Status: ok` or
  `TotalTime >= 10000` (the app-mod surface is not exported, so starting it is refused by the platform);
  `--only`, `--api`, `--work` exist to shorten the diagnostic loop and mark the manifest `diagnostic`.
- JVM gates (VERIFIED, earlier today, unchanged source): capability 66 + core 81 + app 69 debug + app 69 release,
  0 failures, plus host evidence contracts 9/9.
- Harness repairs, each one written only after the failure was measured (all in
  `app/src/androidTest/java/com/mcpintelligence/fr3k/appcontrol/AppModJourneyDeviceTest.kt`):
  manual-tree-walk `shown()` (the platform text search was measured returning nothing for
  `Installed artifact matches the expected hashes and signer.` that the same window's tree contained);
  `settle()` before a tap (a gesture injected while the page is still settling is consumed stopping the scroll);
  taps target the clickable ancestor inside the window minus system bars (a label's own box is a one-line sliver
  clipped by the nav bar and that gesture goes to the system); the largest scrollable is scrolled, not the first
  (the page nests lists and the sweep oscillated inside an inner one); sweeps are accepted only on a real
  `pageStamp()` change and rewind to the top instead of flipping direction; the package-installer dialog is
  searched across all windows with an exact label match, `SYSTEM_CONTROLS = {Cancel, Install, Update, Done}`
  never match by containment (the product's own `Review in Android installer` contains `Install`);
  `edit()` finds the field by the label inside its own subtree; actions with an observable effect are driven with
  `clickUntil`/`waitUntil` on real product state; an open IME is dismissed and waited out; the removal-review
  detour (Settings → patched app) is walked back with `returnToProduct()`, and the picker/installer/launcher are
  treated as driven windows that are never backed out of.
- Scenario-level device evidence on the frozen source (single-scenario probes, one device at a time, product
  uninstalled first). These are VERIFIED PASS and are the baseline to fall back to:
  journey `…-r7` (5586) and `…-r10` (5584); basket `…-r8` (5586) and `…-r10` (5584);
  installed_controls `…-r12` (5586); picker `documentPickerImportsWithoutStoragePermission …-r13` (5586);
  split_restore `…-r15` and `…-r17` (5586).
- Diagnostic aggregate runs (retained, non-promotable): `20261018T052108Z-2593016d`, `…T061129Z-721fb002`,
  `…T062753Z-8e4be65e`, `…T065823Z-27343677`, `…T072418Z-735f1eb0`, `…T073659Z-baafddbc`,
  `…T081555Z-f3441af0` (preflight, red on split_restore/small_200/picker at the time).

## 4. OPEN — the whole remaining task list

1. A single non-diagnostic **frozen acceptance run** on both APIs with every scenario PASS
   (`run_acceptance.py --live-github`). No promotable run exists yet. No APK has been promoted.
2. Packaging + promotion of the HUD row only, benchmark sync, and verification of the 18 artifact hashes:
   `package_appmod_hud_release.py … --update-selection`, then `sync_benchmark.py` in
   `/home/parrot/worktrees/blackwave-unified-20261012`, then re-verify `SHA256SUMS` and compare
   `~/.cache/hud-appmod/benchmark-before-20261018.json` (43 preserved files; BLACKWAVE, firmware and operator
   bytes must stay byte-identical). Current benchmark is still `phone-control-2026-10-16-r3`
   (HUD `ae78d17891c8…`, BLACKWAVE `9f648fb2759a…`).
3. Final numbers in `USAGE-LIMIT-HANDOVER-2026-10-18.md` (and this file) once the run is frozen.
4. Push everything further you complete to the checkpoint branch (see §1).

## 5. What is actually failing, with the evidence

Four scenarios are red on the current build. Everything else in the suite was green in the earlier aggregate
runs and is not implicated by the current changes.

| scenario | where it dies | evidence |
|---|---|---|
| journey `actualReviewInstallerAndModifiedBehavior` | last step, `AppModJourneyDeviceTest.kt:538` = `click("Digest: <digest>", 60000)` | probe r18 (19:29): taps show the run reached `Review in Android installer` → `Check installed artifact` → `I checked the changed behavior` → `Review removing the separate app` → Settings detour → `Open analysis, saved jobs and offline guides` → `Review saved preview / resume`, then the digest click times out. The failing wait's page dump starts with `page=[`, i.e. the review page's changes-JSON blob is the first text of the page. |
| basket `selectionBasketPreviewAndDraftRecoveryUseActualControls` | `…:702` = `clickUntil("Select <title>") { draftSelection(...) }` (called from 705) | probe r17 (19:18). |
| installed_controls `installedSelectionEvidenceSearchAndOfflineGuidesUseActualControls` | `…:637` = `click(guide.title, 60000)` inside the offline-guide loop (also seen at 187/623) | probe r17 (19:24). |
| small_200 (journey at 200 % font) | `…:522` = `clickUntil("Check installed artifact", 150000)` | probe r15 at font 2.0. |

Leading hypothesis for the digest click (and probably for the guide titles, which are also long strings that
appear inside other text): `findTarget`'s **containing-match fallback** can select a *huge* node — the changes-JSON
blob contains the digest string, and `minByOrNull { text.length }` picks the shortest candidate, but when the JSON
blob is the only candidate it is still returned, and a tap on it does nothing. `walkMatch` (added in the
uncommitted delta) walks in document order and would reach the real `Digest: …` text node first, but it is only
consulted when `containing` is null. Suggested change, in this order:
prefer `walkMatch` when the containing candidate's own text is much longer than the searched text
(e.g. `it.text.length > text.length + 32`), keep the containing candidate only for near-equal lengths, and keep
`matchNode` (exact) for `SYSTEM_CONTROLS`. Then re-probe journey at font 1.0 before touching anything else.

Second hypothesis for the guide rows: the guide titles are long labels on a list that also renders the same words
in a header — same containing-match selection problem; the same fix applies.

## 6. Infrastructure facts you will need

- **The emulators died once during this session** (~19:05, both of them, mid-probe, no OOM message in the journal;
  no emulator process survived). They were relaunched with the commands in §2 and came back in ~3 minutes. Check
  `adb devices` before you trust a probe result, and re-run a probe that started while a device was missing — the
  runner and the probe both fail *fast and oddly* in that state (instr log of a few hundred bytes).
- Three emulators are attached right now: `emulator-5586` (API31), `emulator-5584` (API35) and an extra
  `emulator-5592` (API35) that is not part of this work. **Never run two instrumentations on one emulator.**
- `settings put system font_scale` persists per emulator: the probe sets it every run (argument 5), but check it
  if a scenario behaves oddly (`adb -s <serial> shell settings get system font_scale`).
- `targeted_probe.sh` uninstalls the product, installs the gallery fixture (the installed-app scenarios analyse
  `com.mcpintelligence.fr3k.patchfixturetwo`; without it the test dies with `NameNotFoundException` before
  touching the product), installs the debug + androidTest APKs, pushes the fixture to `/sdcard/Download`, sets
  `REQUEST_INSTALL_PACKAGES`, wakes the device, presses HOME, clears logcat and captures logcat for the run.
- Evidence lives in `/home/parrot/.cache/hud-appmod/`: `runs/<run-id>/` (manifest + api31/api35 logs),
  `target-*` probe logs, `preflight.json`, `benchmark-before-20261018.json`, build logs.
- Disk is at 90 % (`/home/parrot` 206 GB free) — probe logs from earlier rounds are large; prune only logs, never
  run directories or evidence.

## 7. Rules that decide whether a run is promotable

- A run is promotable only when the manifest is **not** `diagnostic`, its status is PASS, and **every** frozen
  scenario is PASS on **both** APIs. `--only`/`--api` shorten the debug loop only; a filtered run can never
  satisfy `scripts/appmod/coverage_contract.py` and `evidence.py` will refuse to package it.
- Any edit to product sources after a run started invalidates that run (`validate()` compares the source digest).
  Test-only edits are what all the work above has been; a product change restarts the freeze.
- The packager re-validates the run, re-checks the APK signature (v2 required) and the package name, refuses to
  overwrite an existing release directory, and refuses to promote if the benchmark selection changed underneath it.
- Failed attempts stay retained — do not delete a red run directory; the repo's own doctrine is that the failures
  are the evidence.
- Do not relax the performance budget or the evidence contract to make a scenario pass. The `small_200` /
  accessibility scenarios exist to catch exactly that.

## 8. Recommended order of work (roughly 3 h if the digest fix lands)

1. Apply the containing-match fix in §5, rebuild, and probe journey at font 1.0 on 5586 (~12 min). Iterate only on
   that scenario until it is green — it is the sequence the other three red scenarios borrow from.
2. Probe basket, installed_controls and small_200 (font 2.0) on 5586, and journey + basket on 5584 (~30 min).
3. `preflight_then_full.sh` (~20 min). It writes `preflight.json` and only chains into the full run when
   `"failed": []`.
4. The full frozen run: 29 scenario records × 2 APIs, roughly 2–2.5 h, both APIs in parallel — watch for a dead
   emulator (§6) and re-run rather than trusting a partial manifest.
5. Package + promote + benchmark + 18 hashes (§4.2), then finish both handover docs and push (§1).

If you run out of session before step 4 finishes: leave the run directory in place, write its id and the failing
scenarios into this file, commit that, push, and say plainly that no APK was promoted.

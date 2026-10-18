> FINAL STATUS — 2026-10-18: [authoritative acceptance record](APP-MODIFICATION-ACCEPTANCE-2026-10-18.md). Complete frozen run `20261018T121810Z-02213752` PASS: 58/58 scenario records, 78 Android executions, 285 JVM executions, 9 host checks, all10 coverage areas and both live GitHub checks. HUD SHA256 `fa73b23d684bbbe828e50ea13c3475b8070850f4b8b077b0b67d33c58d9875e7` is packaged/promoted; benchmark18/18PASS; other41baseline hashes and17non-HUD selections unchanged. Emulator scroll p95 API31/API35: 50.819881/50.030106ms, zero app-mod main-thread disk I/O. Private qualified release `hud-appmod-2026-10-18-r1`: all7assets downloaded and hash-verified. Physical/manual TalkBack/live AI/public-distribution gates and broader firmware work remain separate. All earlier entries below are historical, not current instructions.

# Continuation — app-mod harness and runner repair (2026-10-18, session 2)

Runner hardening in scripts/appmod/run_acceptance.py: every frozen run now uninstalls the product before installing the artifacts, because the app-mod store survives reinstall and the deterministic preview digest replayed a previous run's job record instead of exercising it (the root cause of the run3 journey/update failures). Cold start now measures the exported launcher com.mcpintelligence.fr3k.ui.MainActivity (`am start -W -S`, refuses a missing `Status: ok` or TotalTime >=10000); the app-mod surface is not exported so the platform refuses to start it. `--only`, `--api` and `--work` exist to shorten the diagnostic loop only and record `diagnostic` in the manifest: a filtered run can never satisfy the coverage contract, so it is never promotable.

Harness repairs in AppModJourneyDeviceTest, each measured before it was written: sweeps are accepted only when a real page fingerprint (pageStamp, text+bounds) changes, because the platform scroll is acknowledged by an editable field without moving the page; taps target the clickable ancestor inside the window minus system bars, because a label's own box can be a one-line sliver clipped by the navigation bar and that gesture goes to the system; the package-installer dialog is searched across all windows with an exact label match, because it is not always the active window and a tap during its open animation cancelled a session ("User rejected permissions"); edit() finds the field by the label inside its own subtree, because the label node itself is not editable; a control with an observable effect is driven with clickUntil/waitUntil keyed on real product state (basket draft selection/mode, verification status, install session) rather than a fixed tap sequence. Text that is evidence rather than a control is observed by a manual tree walk: the platform text search was measured returning nothing for `Installed artifact matches the expected hashes and signer.` while the same window's tree contained it, which is what made the journey verify step look like a product refusal. Taps are preceded by settle(), which waits for the page to stop moving: a gesture injected while the page is still settling from a sweep is consumed stopping the scroll, measured as silently dropped taps immediately after a sweep brought the control into view. A failed wait now logs the page's text as one compact line, because the chunked tree dump is mostly dropped by logcat.

Diagnostic evidence (subset runs and single-scenario probes, not promotable): 20261018T061129Z-721fb002, 20261018T062753Z-8e4be65e, 20261018T065823Z-27343677, 20261018T072418Z-735f1eb0, 20261018T073659Z-baafddbc. Focused probes with the current build: journey PASS on both platforms (target-emulator-5586-…-r7, target-emulator-5584-…-r10), basket PASS on both (…-r8 on 5586, …-r10 on 5584), installed_controls PASS on 5586 (…-r12). The three journey-derived UI scenarios that carried the whole failure family are therefore green on the frozen source.

Three further measured causes were fixed after the first diagnostic batches: the removal review hands off to Settings and then to the patched app, so one back action only reaches the patched app and the tests now walk the detour back to the product; the page nests scrollables and the first one found is an inner list, so a field that exists on the page was never found while the sweep oscillated inside that list, and the largest scrollable is now used; an open keyboard covers the controls below a filled field and swallows taps aimed at them, so the keyboard is dismissed and waited for. Probes that skipped the fixture install failed with NameNotFoundException before touching the product: the installed-app scenarios analyse the fixture package, which the runner installs in its own setup.

Host gates remain green: capability66 + core81 + app69 debug + app69 release, 0 failures; host evidence contracts9 pass.

Still required: the full frozen acceptance run on both platforms (started after a green preflight of the remaining UI-heavy scenarios), then packaging and promotion of HUD only, benchmark sync and the18 hashes, and this handover's final numbers. No APK promoted yet.

> Current state, verified scenario evidence, the four red scenarios with their exact failure lines and the
> infrastructure facts: see `HANDOVER-NEXT-AGENT-2026-10-18.md` (written 2026-10-18 19:35 AEST). Read that first.

---

# Latest follow-up — 2026-10-18, backup still transferring

Authoritative private backup: https://github.com/fr3k-d3v/fr3k-ecosystem-checkpoint (main README lists source branches). Release checkpoint-2026-10-18 contains benchmark/evidence plus split archive uploads. Check GITHUB-BACKUP-2026-10-18.md and cache/ecosystem-github-20261018 before claiming all uploads finished. Long-lived connections stalled and were restarted; remaining large files are split into96MiB parts. Verify PART-SHA256SUMS, concatenate each archive's parts in lexical order, then verify SHA256SUMS. Existing public remotes were not overwritten.

Follow-up build13 PASS: XML capability66/core81/app69 (216 debug JVM), app release69; no failures/errors/skips. Log /home/parrot/.cache/hud-appmod/correctness-build-20261018-13.log. Test harness now waits without swiping for system buttons, uses real-timed gesture fallback, and restores DocumentsUI clickable-ancestor selection. New source/test changes are compiled.

Focused device evidence: /home/parrot/.cache/hud-appmod/target-ui-20261018-13, artifact hashes in artifacts.json. Both API31 and35: real DocumentsUI picker PASS; explicit Cancel PASS retaining outputs and retry; guide controls FAIL. Basket/draft/preview PASS on35, FAIL on31 reaching search control. Both performance runs measure zero app main-thread disk violations but FAIL scrolling budget (API35 p95=128.44648ms under concurrent host work; preserve evidence and diagnose rather than relaxing budget). This is diagnostic evidence, not a complete frozen acceptance run. UI scroll traversal may still choose a nested scrollable container; investigate largest page scroller instead of first nested node. No production change after build13 in this follow-up.

The original run3 completed FAIL: frozen candidate changed (58 scenario records). No runner is active now. Task AVD userdata is preserved; focused runner restores installation appops and removes its shared picker fixture. Full fresh acceptance, all three template runtime cases and complete journey remain required after harness/performance corrections. No new APK promoted. Benchmark18 hashes and all43 preservation-ledger files pass unchanged.

---

# Ecosystem handover — 2026-10-18

User requested this durable checkpoint and GitHub backup of HUD, BLACKWAVE and all related firmware. Push work is in progress; consult GITHUB-BACKUP-2026-10-18.md for final destinations and hashes.

## Workspaces and preservation

- HUD: /home/parrot/repos/fr3k-hud (master; existing local commits plus uncommitted APK discovery and BLACKWAVE integration).
- BLACKWAVE: /home/parrot/worktrees/blackwave-unified-20261012 (initially detached b037bc5; includes companion, firmware, release packages and evidence).
- Cardputer: /home/parrot/worktrees/cardputer-unified-20261012 (initially detached 27eb704; includes sprites, renderer, game, GPS, storage and NQ work).
- Benchmark: /home/parrot/FR3K-BENCHMARK. Read AGENT-HANDOFF.md and each repo AGENTS.md before work. Preserve operator records and historical failures.
- APK task cache: /home/parrot/.cache/hud-appmod. SDK /usr/lib/android-sdk; Java17 /usr/lib/jvm/java-17-openjdk-amd64. Always use task TMPDIR and disable incremental Kotlin.

## APK discovery acceptance remains OPEN

Implemented SQLite transaction lock-order fix; owner-bound isolated-worker cancellation; exact DEX/resource/layout/manifest/helper/archive readback; durable installer sessions, stale callback protection, startup reconciliation and fresh callback when resuming after process death. Added deterministic negative tests, device concurrency/storage/restart/template/UI coverage, unique-run evidence validator and release packager. Public five tools and approval boundary retained; schema remains3.

Latest completed JVM gates: capability66 + core81 + app69 =216 debug tests, plus69 app release tests; host evidence contracts9 pass. Counts come from XML. Later source/test changes remain unbuilt. Latest passing build log correctness-build-20261018-12.log. Do not claim latest source fully verified.

Three diagnostic aggregate runs exist under cache/runs; all are ineligible for promotion because tests failed or source changed after freeze. Latest: 20261018T052108Z-2593016d, wrapper run-20261018-3.log. A runner may still be active; inspect process and manifest before launching instrumentation. It owns device settings and restores them on completion. API31 emulator-5586 and API35 emulator-5584 preserve userdata. Never run simultaneous instrumentation on one emulator.

Both platforms passed domain, migrations, execution templates (old two-test version), boundary tests, storage exhaustion, four concurrency cases, UI queued cancellation and actual split/signer-conflict/original restoration. Real platform session process-death resume/cancel targeted tests passed both. Run3 main journey/update/basket/picker/guide controls have harness failures; no passing aggregate. Earlier performance measurements detected one main-thread disk read on each platform; prefs moved to IO afterward, requiring remeasurement.

## Next fixes and verification

1. Generic UI click must never scroll/swipe HUD while waiting for system Cancel/Install/Update/Done; API35 installer can open during swipe and abort from outside touch before explicit button tap. Only count explicit Cancel evidence. Run3 API31 explicit cancellation passed; API35 did not.
2. Scroll helper sends future-timestamp MotionEvents without real spacing; use accessibility scroll first or real timed swipe. Guides stall before later steps. Restore DocumentsUI ancestor ACTION_CLICK after physical-tap change regressed real picker.
3. Latest unbuilt test changes already address offscreen verification/digest text and ACTION_SET_TEXT on editable descendants; added actual inserted-share runtime template (runner now expects3 template tests).
4. Latest unbuilt ModificationStore publication checks require complete matching job/artifact/output metadata; concurrency fixtures updated accordingly. Build and run JVM/host checks.
5. Run targeted basket, picker, all guide controls, journey/update/cancel, performance and new runtime share case before another complete runner.
6. Freeze a fresh complete acceptance run using python3 scripts/appmod/run_acceptance.py --live-github. Failed attempts stay retained. Live checks are separate from deterministic acceptance. Any source edit invalidates the freeze.
7. Only after every mandatory deterministic gate passes, run scripts/release/package_appmod_hud_release.py against that selected run, promote HUD selection only, sync benchmark and verify18 artifact hashes. Compare cache/benchmark-before-20261018.json (43 preserved files); BLACKWAVE, firmware and operator bytes must remain unchanged.

Current benchmark stays phone-control-2026-10-16-r3: HUD ae78d17891c847b65423cae8348437a06e0fa6d5ab7a8f94716d6d28c2b3af7b; BLACKWAVE 9f648fb2759a7f4aa328c5bff6a6926f56931a93754eb1f0d75bb77bab01d4a6. No APK discovery release promoted. Physical phones, manual TalkBack, physical performance, configured live AI and distribution licensing remain separate pending gates.

## Broader firmware continuation

Read BLACKWAVE docs/research/current-state.md, blockers.md, evidence-vocabulary.md and docs/phone-control/NATIVE-MISSIONS-R3.md plus benchmark historical handoff. Room7 is packaged; future persistent companion schema changes require explicit migration. Many shared firmware source changes remain unbuilt for older selected candidates. No physical flashing/RF/provisioning/security-fuse authorization follows from GitHub backup. Preserve per-target qualification limitations.

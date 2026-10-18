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

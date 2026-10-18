> Current checkpoint: [session 3 continuation](HANDOVER-CONTINUATION-2026-10-18-SESSION-3.md). The complete frozen run `20261018T112043Z-c63c71f1` is active; no new APK is promoted. Historical entries follow.

# App modification feature — agent continuation

## 2026-10-18 continuation checkpoint (work in progress)

The previous totals and device state below are historical. Work is still active; do not promote the current build as accepted.

- `correctness-build-20261018-10.log` passes the established Java17/offline/debug gates plus `:app:testReleaseUnitTest`. XML: capability66, core81, app69 =216 debug tests, plus69 release-configuration app tests. Later source/test/harness changes are **not built yet**.
- The new task runner is `python3 scripts/appmod/run_acceptance.py --live-github`. Shared fail-closed validation is in `scripts/appmod/evidence.py` and `coverage_contract.py`; host contract tests: `python3 -m unittest discover -s scripts/appmod -p 'test_*.py'` (9 passing when last run). Each run freezes sources/APK/test APK/fixtures/helper, retains logs/XML, initializes stateful scenarios separately, restores geometry/font/motion/permission settings, checks teardown, and fails on required missing/failing/skipped/mismatched evidence. Configured live AI remains separate. The optional live GitHub check has its own log.
- Diagnostic run1: `WORK/runs/20261018T044830Z-358ff4ad`. Diagnostic run2: `WORK/runs/20261018T045820Z-f5fd13e9`. Both FAIL, retained. Both deliberately became ineligible for packaging when production fixes changed their frozen source/artifact hashes. Do not cherry-pick their passing scenarios into a final package.
- New readback reconstructs all approved DEX instruction changes at source offsets, canonicalizes the complete DEX (including unaffected methods/classes/fields/annotations/debug/try data), checks exact packaged helper bytes, compares complete resource-table JSON and expected layout/manifest XML, and rejects unexpected archive entries. Positive/negative JVM cases cover combined changes, all3helpers, wrong source/helper address binding, unrelated method/resource/manifest/file changes and unchanged target/replacement elsewhere. The writer now strips signing metadata only, retaining non-signature META-INF application data. Hook retains its separately qualified exact-source route.
- `ModificationStore` no longer mixes JVM monitors with SQLite transactions. Atomic publication and suggestion replacement have a bounded concurrent device test, with complete nonempty suggestion and prepared-output records. Schema remains3; no migration was needed.
- Sandbox cancellation is operation-owned. Queued callers install no cancellation handler; the lock holder cancels its own Binder worker, unbinds and awaits death before handing off. Binder death from cancellation is normalized back to the owning coroutine cancellation. Source capture is now suspendable instead of nesting runBlocking. New concurrent UI/agent/preparation tests preserve originals and prevent queued cancellation killing another task.
- API31 initial cancellation diagnosis: the old harness stopped seeking Cancel before delayed PENDING_USER_ACTION opened the system dialog. Revised explicit Cancel + retained hashes + retry passes on API31 in run2. API35 first Cancel actually reached CANCELLED/status3 but its old semantics-action harness returned false while the dialog closed, causing the scenario to fail. The journey harness now injects real pointer gestures and brings targets into view. Final API31/API35 rerun remains required.
- Session callbacks are transactional, durable and session-ID/terminal-bound. Stale/duplicate callbacks cannot overwrite a newer/finished session. Startup leaves live platform sessions INSTALLING and reconciles absent sessions against installed hashes; unknown ended sessions become PREPARED/FAILED, never inferred user cancellation. Actual force-stop session recovery passed API31 in run2. API35 exposed loss of the old callback while the platform session survived; current resume recommits the sealed permission-pending session with a fresh PendingIntent. Targeted tests currently running: `WORK/target-5584-platform-{create,verify}-20261018.log` and5586 equivalents. Check them before further changes.
- Targeted build10 tests: `target-5584-concurrency-performance-20261018.log` and5586 equivalent. All3concurrency tests pass on both. Performance measured warm startup1063/1145ms, scrolling p95 46.84/51.21ms,196/199frames,100history records, but FAIL because StrictMode found1app main-thread DiskReadViolation each. Current unbuilt UI source moves appmod SharedPreferences initialization/read/write to IO and emits full violation traces on rerun. Do not report performance acceptance yet.
- UI now separates installed-app search from suggestion search, guards asynchronous restoration against newer UI ownership, makes guides/installation permission/expert entry reachable near the top, and summarizes history with focused review actions. Basket/draft, exact actual selection, all mode toggles, real guide steps, installed selection, permission settings/expert navigation, user observations/restoration and history controls are being measured by new journey tests. Some tests are not yet compiled/run; final runtime evidence is required.
- New real PREPARING process-death test is coordinated by the runner: a real prepare call writes PREPARING and queues at a test-only scheduling barrier, host kills the process on the emitted checkpoint, then a separate invocation checks INTERRUPTED/no publication/original hashes/new approval and successful retry. The intentional termination is logged explicitly and is not counted as a normal passing JUnit test. This has not run yet.
- New boundary/storage tests PASS on both SDKs in run2: incomplete/duplicate/mismatched signer/version split sets, conflicts, filesystem failure/no publication, mocked research failures and untrusted responses, stale/duplicate callbacks, real 192MiB remaining-space preparation refusal. Runtime owned hook true/false/true in one process and direct reviewed sharing also PASS there.
- Packaging script has been repaired to select a run manifest, require complete matching measured coverage, preserve `hud-latest-debug.apk`, resolve source paths relative to the BLACKWAVE worktree (including releases/), and write ledgers before checksums. It refuses failed/incomplete evidence. No new HUD release selected yet.
- Benchmark baseline18/18hashes PASS. Preservation ledger: `WORK/benchmark-before-20261018.json` (43selected/historical APK/firmware/operator-record/selection hashes). BLACKWAVE, all firmware and operator records remain unchanged. Final promotion must update HUD only, sync benchmark and verify18hashes from benchmark cwd.

Next: finish targeted session check; build current sources, fix only observed failures, then run the complete task runner against a frozen candidate until all required deterministic scenarios and coverage pass. Verify host contract rejection tests, signature and release packaging; update HUD selection/sync18hashes only after acceptance. Physical phone, manual TalkBack, physical performance, configured live AI and public-distribution licensing remain separate gates.


Updated: 2026-10-17, Australia/Brisbane. Authoritative workspace `/home/parrot/repos/fr3k-hud`. Preserve all dirty work. User authorizes continuing implementation/verification without prioritization checkpoints and explicitly requests this Markdown handoff when context/time limits approach. No physical phone or fleet hardware available. Do not claim ALL release gates complete.

## Read first

- `AGENTS.md` and `/home/parrot/FR3K-BENCHMARK/AGENT-HANDOFF.md`.
- `docs/app-control/app-specific-modification-discovery-plan.md` — original researched plan and historical progress.
- `docs/app-control/APP-MODIFICATION-VERIFICATION.md` — measured scope, failures and boundaries.
- This file and latest evidence logs in `/home/parrot/.cache/hud-appmod` (`WORK`).

This turn focuses on app-specific APK modification discovery. The earlier BLACKWAVE firmware/fleet programme remains separately OPEN. Do not overwrite its worktree, firmware candidates or operator records. No agents spawned; current developer instructions prohibit delegation unless explicitly authorized by user/applicable instructions.

## Implemented

All five closed tools are registered in agent bus and MCP: analyze_apk, suggest_app_mods, preview_app_mods, request_app_mod_preparation, get_app_mod_job. Source capture is immutable and hash/signer/package/version/split-bound. Installed apps and document URI lists use `AppModArtifacts`. Archive retains originals before mutation. Sources, previews, drafts, research, outputs and sessions persist in `ModificationStore` SQLite v3 with explicit1→2→3 migrations and FTS4 evidence search.

`analysis/ApkAccess` reads only passed FileChannels in an isolated UID, rather than reopening `/proc/self/fd`; validates original entry names, ZIP footer/central-directory, actual entry sizes/CRC and signing-block budget. `AppModSandboxService` handles metadata/analyze/patch/validate with granted descriptors, bounded JSON/files and timeout. Application bootstrap returns before HUD credentials initialize in isolated process. Worker cancellation explicitly calls `AppModSandbox.cancel()` (unqualified cancel previously cancelled the caller).

Analysis includes binary manifest/permissions/components, DEX census/technology, exact strings/colours/layout visibility and app-owned constants/helper points. `AppModSuggestions` grounds executable cards in exact evidence; generic ideas/community requests remain NEEDS_ADAPTER. Ranking reserves six slots for string/colour values so helper findings cannot crowd them out. Typed AI suggestions refer only to existing evidence aliases and validated replacements. Public research requires confirmed owner/repo; actual GitHub issue URLs/status/dates/excerpts/hashes, seven-day cache, enhancement-label fallback. AI allowlist summary≤32KiB, no APK/full code/values/private paths. Configured live AI NOT TESTED; mocked provider boundary is tested.

`AppModWorkflow` compiles a closed PatchSpec into canonical preview digest, exact ephemeral120sec single-use UI approval, isolated copy preparation and independent readback. Source-derived DEX opcodes preserve source version. Fixed payload asset built on workstation, no runtime compiler/arbitrary scripts. Six operators: string/colour/layout visibility/DEX constant/helper/hook. Three reviewed helpers: url.clean, preference.persist, hud.share. Exact-source fixture hashes qualify hook and side-by-side cloning; unqualified applications use reviewed same-package path. Hook has sigBypassLevel0, useManager=false. Signed APK outputs and PREPARED receipt now publish in one SQLite transaction.

`AppModInstaller` installs complete sets through Android sessions/user confirmation, rechecks installed signer/version/all hashes, retains outputs after cancellation/failure, distinguishes ORIGINAL_RESTORED from modified installation. No product auto-uninstall. Side-by-side removal opens Android App Info. `AppModDiscoveryActivity` offers document/installed selection, evidence/suggestions/research, basket/drafts, focused exact review, durable jobs/resume, installer/verification/restoration and offline guides. Surface fixes unreadable dark text; explicit-preview initialization race and draft-load/save race fixed. Reopen/resume retains an existing analysis ID/source. Startup recovery cutoff+transaction prevents classifying newly created jobs as interrupted.

`AppModCatalogue` links six operators/three helpers/five tools/controls to three real offline guides. `scripts/generate_appmod_inventory.py` produces `docs/app-control/evidence/appmod-coverage-inventory.json`; source presence explicitly does not imply interaction PASS. Dependency pins JSON + ARSCLib Apache and dexlib BSD licence texts added; existing LSPatch GPL notice retained. Private debug use does not close public distribution gate.

## Successful checks

- `verify-12.log`: latest full `:capability:test :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` **PASS**, 2026-10-17. Includes latest atomic publication, installer error handling, colour/spec bounds, schemaVersion and recovery locking changes. Licence assets packaged. Get exact test counts from XML; do not reuse old counts.
- `acceptance-5584-2.log`, `acceptance-5586-2.log`: **6/6 PASS on API35/API31**. Actual five tools, mocked AI boundary, real UI approval/preparation/signing/Android install/modified activity behavior, real base+feature split install and original restoration, migration preservation/backfill, immutable import/readback/reopen/source drift.
- `acceptance-5584-3.log`, `acceptance-5586-3.log`: **7/7 PASS**, adding expired approval/replay/idempotent output checks. Earlier production checkpoint, not full final-source rerun.
- Actual behavior changed label/playback default/cleaned URL/hidden panel; colour independently read back. Only after actual launch assertions is BEHAVIOR_VERIFIED recorded.
- `journey-5584-4.log`, `journey-5586-4.log`: live GitHub transport **PASS**, 12 real issue records each, but these suites overall failed due picker initialization leaking into later tests. Do not call these whole suites PASS.
- `templates-picker-5586-2.log`: fixed-helper payload tests and exact owned hook preparation pass; installer cancellation passes on API31; whole suite fails picker. API35 cancellation failed with picker still in foreground.
- `git diff --check` PASS after latest edits.

## Active environment and failures to resolve

`/tmp` is a16GiB tmpfs at100%. Do not delete other people's files. Always use task TMPDIR. Both original emulators crashed simultaneously at00:45:28 with bad_function_call; crash writer reported no space in/tmp. Logs retained. Their userdata is intact.

**API35 restarted and booted** on `emulator-5584`, task AVD `codex_appmod_api35_20261016`, unified-exec session48390:
```
TMPDIR=/home/parrot/.cache/hud-appmod/tmp /usr/lib/android-sdk/emulator/emulator -avd codex_appmod_api35_20261016 -port 5584 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect
```
Output `WORK/emulator35-restart.log`. **API31 currently stopped**, task AVD `codex_appmod_api31_20261016`, boot same options on5586 with task TMPDIR. Dedicated image path configured in task AVD: `WORK/sdk31/system-images/android-31/google_apis/x86_64`. Existing SDK emulator works. Original user AVD5582 remains stopped/preserved. Do not wipe any AVD.

**Current outstanding picker test:** `documentPickerImportsWithoutStoragePermission` in AppModJourneyDeviceTest. Initially MediaStore-created files did not appear in Downloads provider. Harness now initializes the owned gallery APK via adb push to `/sdcard/Download/owned-appmod-selection.apk`; product receives a real scoped DocumentsUI URI. Test navigates Show roots → Build.MODEL → Download → exact filename. UiAutomation flags now INCLUDE_NOT_IMPORTANT_VIEWS|REPORT_VIEW_IDS; recursive diagnostic depth30. Last `picker-5584-6.log` and5586 were empty because emulators crashed before execution. **This latest harness initialization has not actually run yet.** A manual adb-pushed `owned-debug.apk` was visible through device storage/Download, proving this route lists APKs. Remove only these task-owned files after testing.

A failed picker leaves DocumentsUI in foreground; one BACK only moves to parent folder. **Fix test teardown** to back out while root package is DocumentsUI (bounded), or finish picker explicitly. Later UI tests must not inherit an open picker. API35 cancellation was affected by this. Could also open focused HUD review before cancellation to ensure HUD foreground.

Changing REQUEST_INSTALL_PACKAGES appops live kills HUD; original combined test crashed by design. Revised cancellation test no longer changes permission. New separate `AppModPermissionDeniedDeviceTest` expects host to set appops deny **before** instrumentation, then checks no installer session and PREPARED output retained. Reset appops allow afterward. This class compiled in latest build but **not run yet**.

New `AppModRestartDeviceTest` has createRestartCheckpoint and verifyRestartCheckpoint; **not run yet**. Invoke separately with actual adb force-stop between (or reboot). It prepares a real output and separately seeds a synthetic in-flight PREPARING job to check startup classification. Do not present this as an interrupted physical/real transfer PASS. Same prepared output hash must survive restart and new approval reuse.

New `AppModTemplateDeviceTest` tests reviewed payload via test-only InMemoryDexClassLoader (production never loads selected code), URL cases/per-key persistence and HUD share intent resolution. Also exact legacy hook preparation/config. API31 passes; repeat on final both SDKs.

## Next steps in order

1. Run latest picker test on restartedAPI35 with fixture initialized, fix real failures and teardown. Run cancellation, template and denied-permission tests with explicit initialization. Preserve failures.
2. Run restart checkpoint→force-stop→verification; ideally emulator reboot too. Extend only material unresolved cases (incomplete split, storage budgets/ambiguous target/schema bounds). Existing source-drift/archive/unknown operator cases already covered.
3. Restart API31 and run final acceptance suite on both with all fixtures initialized, appops allow, and optional live-network test clearly scoped. Main suite classes: AppModJourneyDeviceTest, AppModMigrationDeviceTest, AppModDeviceTest, AppModTemplateDeviceTest. PermissionDenied and Restart must use separate explicit initialization, not ordinary all-class invocation.
4. Run small-screen and200% font real review/install/modified-behavior journey and accessible-control checks; restore settings. Manual TalkBack/physical performance remain NOT_TESTED, not silently PASS. No frame-rate benchmark measured yet.
5. Rerun appropriate JVM/build checks after production changes. Regenerate coverage inventory. Update verification report and this handoff with actual final counts/IDs/hashes.
6. **Package new HUD debug candidate** and refresh benchmark per AGENTS. No new AppMod release selected yet. Source builds are only under app/build. Existing selected HUD is older r3. Preserve BLACKWAVE APK and all16firmware bytes. Use new directory inside BLACKWAVE worktree releases (e.g. releases/hud-appmod-2026-10-17-r1/hud), include validation.json and SHA256SUMS, evidence/source/helper/fixture/dependency manifests. Do not blindly reuse old packaging script which hardcodes old test counts/status.
7. Update only HUD row in `/home/parrot/worktrees/blackwave-unified-20261012/releases/benchmark-selection.json`, run its `scripts/sync_benchmark.py`, verify18hashes **from `/home/parrot/FR3K-BENCHMARK` cwd**, update benchmark handoff preserving old records. APK signature/inventory/package checks required. Include actual measured status only.
8. User wants both APK paths; final point to benchmark HUD and unchanged BLACKWAVE and Markdown report/handoff. Clearly identify missing physical/live-provider/manual gates. Do not claim whole BLACKWAVE+firmware goal complete.

## Commands

Root `/home/parrot/repos/fr3k-hud`; WORK `/home/parrot/.cache/hud-appmod`; ADB `/usr/lib/android-sdk/platform-tools/adb`; BW `/home/parrot/worktrees/blackwave-unified-20261012`.
```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk TMPDIR=$WORK/tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$WORK/tmp ./gradlew :capability:test :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain -Pkotlin.incremental=false
$ADB -s emulator-5584 install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s emulator-5584 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$ADB -s emulator-5584 push app/src/main/assets/app-control/appmod-gallery-fixture.apk /sdcard/Download/owned-appmod-selection.apk
$ADB -s emulator-5584 shell appops set com.mcpintelligence.fr3k.hud REQUEST_INSTALL_PACKAGES allow
$ADB -s emulator-5584 shell am instrument -w -r -e class com.mcpintelligence.fr3k.appcontrol.AppModJourneyDeviceTest#documentPickerImportsWithoutStoragePermission com.mcpintelligence.fr3k.hud.test/androidx.test.runner.AndroidJUnitRunner
```
Use fresh log names for reruns; do not truncate historical evidence. Tasks/tool sessions may expire across context boundaries; inspect logs/processes first. Never reopen private descriptors by path or force Opcodes.forApi35 when rewriting source DEX: both were real bugs already fixed.

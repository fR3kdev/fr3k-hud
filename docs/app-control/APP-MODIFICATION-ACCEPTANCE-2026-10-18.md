# Final app-modification deterministic acceptance — 2026-10-18

Status: **DETERMINISTIC_ACCEPTANCE_PASS_PHYSICAL_UNTESTED**. The continuation's software acceptance, HUD-only packaging/promotion and private recovery publication are complete. The broader firmware programme and physical/public-distribution gates are not closed.

## Exact candidate and evidence

- Complete accepted run: `20261018T121810Z-02213752` (not a diagnostic subset).
- Local run: `/home/parrot/.cache/hud-appmod/runs/20261018T121810Z-02213752`.
- Validator: `scripts/appmod/evidence.py::validate` PASS against current frozen source/artifact hashes, bound logs/XML, dependency pins, helper bytes and derived coverage.
- Product implementation/test checkpoint: `d6f7207fb125e65bfade77d10f448d9df0a01ccb` on `checkpoint/2026-10-18-hud`; subsequent documentation commits do not change the accepted executable or tests.
- HUD0.4.21 / versionCode421, APK139159876bytes, SHA256 `fa73b23d684bbbe828e50ea13c3475b8070850f4b8b077b0b67d33c58d9875e7`.
- Exact test APK SHA256 `45e5f1b8da1c6477f398ddeaed102fdfb3391c5b868266571dd93357e77b23a7`.
- Helper DEX SHA256 `2b958a95ee4377c51e12f4761344772a436ac810fb0a1a5fc32e45a6caf694e7`.
- APKv2 signature verified; debug signing does not establish a production release-signing gate.

## Measured gates

- All58scenario records PASS across API31/API35, totaling78Android test executions. Each device has29scenario records/39test executions. Intentional process-death checkpoints are separate logs, not invented passing tests.
- JVM285executions: capability66, core81, app-debug69, app-release69; zero failures/errors/skips. Release-configuration unit tests do not imply release-signed device testing.
- Host evidence-contract9/9PASS.
- All10assertion-backed coverage areas PASS: source selection; research boundaries; review approval; preparation failure/recovery; six operators/three helpers; exact negative readback; installer recovery; all controls/offline guides; startup/scroll/main-thread I/O; HUD/MCP/BLACKWAVE regression.
- Live GitHub transport1/1PASS per API, separate from deterministic acceptance and from configured live AI.
- Small-screen200%font complete review/install/changed-behaviour journeys, normal/200%/tablet/reduced-motion accessibility, cancellation, split restoration, migrations, concurrency, process/session restart and boundary checks are included in the complete run.
- Runner finished and restored geometry, font, motion and installation permission. Userdata preserved. Only task AVDs5586/API31 and5584/API35 were instrumented; unrelated5592 was not touched.

| Emulator gate | API31 | API35 |
|---|---:|---:|
| Exported launcher cold start |1272ms|1191ms|
| App-mod warm start |1171ms|964ms|
| Scrolling p95 |50.819881ms|50.030106ms|
| Measured frames |186|183|
| Retained history jobs |100|100|
| App-mod main-thread disk violations |0|0|

Timing ran serially after both suites restored settings. Existing100ms emulator scrolling budget retained. This is not physical-phone performance.

## Package and benchmark preservation

Package: `/home/parrot/worktrees/blackwave-unified-20261012/releases/appmod-hud-2026-10-18-r1`.
Both package checksum inventories (`hud` and `evidence`) PASS. Frozen test APK/helper/fixture archive members are individually hash-checked against the accepted manifest.

Benchmark sync: all18selected checksums PASS from `/home/parrot/FR3K-BENCHMARK`. Of43baseline paths, exactly2changed: selection JSON and latest HUD APK. Other41hashes are identical, including firmware, BLACKWAVE, historical APKs and operator records. All17non-HUD selection entries are structurally unchanged. Ledger: `/home/parrot/.cache/hud-appmod/final-preservation-20261018.json`.

Current installable paths:
- `/home/parrot/FR3K-BENCHMARK/hud-latest-debug.apk`
- `/home/parrot/FR3K-BENCHMARK/blackwave-companion-latest-debug.apk` (unchanged SHA256 `9f648fb2759a7f4aa328c5bff6a6926f56931a93754eb1f0d75bb77bab01d4a6`).

## Private recovery publication

Repository: https://github.com/fr3k-d3v/fr3k-ecosystem-checkpoint (private).
Qualified debug release: https://github.com/fr3k-d3v/fr3k-ecosystem-checkpoint/releases/tag/hud-appmod-2026-10-18-r1 .
All7uploaded assets were downloaded again; every size and SHA256 matched local bytes and the remote metadata. Downloaded checksum inventory also PASS. Verification ledger: `/home/parrot/.cache/hud-appmod/qualified-release-remote-verification-20261018.json`.

Assets: qualified package archive; exact frozen test-artifact archive; direct HUD APK; accepted run manifest; preservation ledger; release notes; SHA256SUMS. Download to a new directory, verify SHA256SUMS, then extract package and verify its two internal checksum inventories. Source recovery uses the HUD checkpoint branch. The separate historical release `checkpoint-2026-10-18` and its34verified assets remain intact; do not replace it.

## Failures retained and final correction

Complete run `20261018T112043Z-c63c71f1` finished57/58PASS; installed-controls/API31 failed. Additional telemetry in complete run `20261018T114328Z-b0ad3df0` reproduced the same issue and showed the coordinate tap intended for installed search selecting an app row during asynchronous list reflow: the chooser closed and analysis started. Its API31 scrolling result also exceeded the budget; it was not promoted.

The final harness correction targets only `Find installed app`: act on the exact labelled editable node, refuse unrelated focused/sole-field fallbacks, and read back the requested text. Other field paths and all required assertions remain. Both API installed-control checks pass in the accepted full run. Product APK bytes remain the previously frozen candidate; the final test APK changed.

Diagnostic `20261018T120209Z-78b8c059` was interrupted by the terminal's420second call limit. Both installed-controls checks had passed; it is incomplete, was never promoted, and modified settings were explicitly restored before the next run. Follow-up diagnostic `20261018T120927Z-733e72a0` was not accepted: basket/API35 failed, and isolated performance lacked its prior-suite fixture prerequisite. Failed/incomplete records remain retained. No partial evidence was substituted for the final full PASS.

## Separate unqualified boundaries

Physical phone installation, manual TalkBack speech traversal, physical performance, configured live AI and public-distribution licensing remain NOT TESTED/unqualified. General third-party compatibility is not established by owned fixtures. Original APK archives do not back up private application data. No firmware was flashed, RF transmitted, credential provisioned or security fuse changed. The broader BLACKWAVE/firmware programme retains its prior open work and dirty trees.

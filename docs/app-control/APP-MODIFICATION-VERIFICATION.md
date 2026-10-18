> Current checkpoint: [session 3 continuation](HANDOVER-CONTINUATION-2026-10-18-SESSION-3.md). The complete frozen run `20261018T112043Z-c63c71f1` is active; no new APK is promoted. Historical entries follow.

# App-specific modification discovery — verification record

## 2026-10-18 verification continuation — final acceptance pending

The latest completed build (`correctness-build-20261018-10.log`) passes216debug JVM tests (66capability/81core/69app), plus69app tests in release configuration, counted from XML. Later source changes require rebuilding. Diagnostic run manifests under `/home/parrot/.cache/hud-appmod/runs/20261018T044830Z-358ff4ad` and `20261018T045820Z-f5fd13e9` are FAIL and preserved; changing frozen sources/artifacts correctly prevents their use as a verified release.

New checks found and fixed monitor/SQLite lock inversion, globally owned sandbox cancellation, death/retry Binder races, broad readback that accepted a replacement elsewhere, removal of non-signature META-INF application metadata, installed/suggestion search state collision and main-thread preference access. Exact readback now compares complete approved DEX/resource/layout/manifest/archive semantics and packaged helper bytes. New host validation tests reject missing/failing/skipped/mismatched evidence and source/label inventories masquerading as interaction coverage.

Measured diagnostic passes include API31 explicit installer Cancel plus hash-preserving retry; invalid split/signature/version sets and low-space refusal on bothSDKs; actual owned-hook behavior; stale/duplicate callback refusal; actual scoped picker, denial and checkpoint restart; all3concurrency tests on bothSDKs in targeted build10 runs. API35 force-stop session recovery exposed callback loss and is being retested with a fresh callback on recommit. Main-thread I/O acceptance remains open: targeted warm startup1063/1145ms and scrolling p95 46.84/51.21ms with100history records stayed within the declared emulator timing budgets, but each reported one StrictMode DiskReadViolation. Current UI source moves preference access toIO; final rerun is required.

No new appmod package has been promoted. Benchmark baseline18hashes and a43file preservation ledger are retained. The task-owned runner and packaging script now require one matching frozen run with complete assertion-backed coverage before verified packaging. See AGENT-CONTINUATION.md for current paths and pending tests. Historical failures and earlier scope below remain intact.


Date: 2026-10-17. Workspace: `/home/parrot/repos/fr3k-hud`. Private debug candidate; physical phone unavailable. This record concerns the selected-APK modification feature. It does not close the separate BLACKWAVE firmware/fleet programme.

## Implemented workflow

Phone-owned immutable source capture supports installed applications, document-selected APKs and complete base/split sets. Ordered hashes, signer, package/version and split names bind each analysis, preview, approval and durable job. Originals are retained in the content-addressed archive before mutation. APK parsing and rewriting use a dedicated isolated process with only granted file descriptors; the worker does not initialize HUD credentials or reopen private paths. Entry, expansion, central-directory, result and wall-clock budgets are enforced.

Local evidence includes manifest/components/permissions, DEX census, technology indicators, qualified string/colour resources, identified layout visibility, exact app-owned constants and fixed helper insertion points. A searchable FTS index and persisted drafts accompany migration1→2→3. Reports distinguish observed/inferred/unknown and state coverage limits; cached evidence does not establish runtime behavior.

Five closed tool contracts provide analysis, suggestions, preview, review request and job retrieval. Agent preparation requests open the exact user review. They cannot supply approval. Approval binds canonical preview/source/action/options, expires after120seconds and is consumed once. Preparation stages a copy, independently reads it back, signs it using the phone operational key and publishes hash-verified outputs only after success. A reconnect reuses the prepared output.

The closed language implements string, colour, visibility layout, DEX constant, fixed helper and exact fixture-hook changes. Fixed helpers clean explicit URL tracking keys, remember a returned string by context key or explicitly share returned text to HUD. Context/register/method constraints are checked. The hook and side-by-side clone profiles require exact packaged fixture hashes. Unqualified real applications use the reviewed same-package route; suggestions requiring unavailable adapters stay nonexecutable. No desktop REA/JDK/compiler or arbitrary generated scripts run on the phone.

Public research requires a confirmed owner/repository and retrieves actual issue URLs/status/dates/excerpts/hashes. A seven-day cache remains available offline. AI receives an allowlist-built summary≤32KiB, no APK attachments, values, private paths or full code. Returned ideas must reference existing evidence/request aliases; executable replacements still pass typed local validation and review. Mocked AI boundary success does not establish live provider availability.

Android PackageInstaller handles complete sets and user confirmation. Receipts recheck installed hashes/version/signer. Original restoration has its own status, distinct from modified installation and behavior verification. The original APK archive does not back up private application data. Side-by-side app removal is reviewed through Android settings.

## Measured acceptance

Task-owned API31 and API35 emulators, separate fresh userdata. Initialization installed HUD/test APKs and allowed REQUEST_INSTALL_PACKAGES for the principal successful-install journeys. Test-owned fixture packages and media are the only targets. Host ADB is a development harness, not a runtime dependency.

`/home/parrot/.cache/hud-appmod/acceptance-5584-2.log` and `acceptance-5586-2.log`: **6/6 PASS per Android version**:

- Actual five-tool analysis/suggestion/preview/review-request/job contracts and fake-provider redacted boundary.
- Actual review approval→isolated preparation→phone key signing→Android installer confirmation→modified activity launch. Text, colour/static readback, playback default, URL cleanup and hidden panel checked; only then BEHAVIOR_VERIFIED recorded.
- Real base+assets-only feature split installed as a complete set; installed split/asset marker verified; archived original set restored and exact original identity checked. Signer switch teardown explicitly removed only the owned test package through the harness. Product never automatically uninstalls an app.
- Version1 schema migration preserves an existing analysis and backfills FTS; records survive reopening. This test uses an isolated test database.
- Frozen import, independent manifest readback, forged approval refusal, archive presence and SQLite reopen.
- Distinct gallery suggestions, mock public-request provenance and changed-source refusal.

Principal behavior jobs: API35 `0ac9a896-add6-4d1c-8c6b-64cb8afe56df`; API31 `fc1e21bb-7fad-4b1e-8ab1-6b7ebee8f3c0` were measured in the preceding run. Later logs retain their own job IDs and hashes. Evidence is never inferred from a button click alone.

`verify-5.log`, `verify-6.log`, `verify-7.log`: full applicable JVM/build tasks PASS at those checkpoints. Final package manifest records exact final counts/hashes and subsequent runs; these historical checks do not substitute for a final rerun after changes.

## Failures found and fixed

Historical failing logs remain in the task evidence cache. Direct `/proc/self/fd` reopening was refused in the isolated UID; replaced with positional reads from granted channels. Worker application bootstrap incorrectly matched only a process-name suffix; fixed before credential initialization. Unqualified coroutine cancel cancelled the successful caller; now explicitly targets the worker. Entry compressed/uncompressed lengths and stream mark/reset semantics corrected. Source-derived DEX opcodes preserve the original version; forcing API35 produced an unreadable DEX header and was removed. Malformed symbols leaking planted secrets into AI summaries are excluded. Review text now inherits Surface foreground colours. Requested previews no longer race stale saved previews. XML visibility now writes enum0/1/2 rather than runtime0/4/8; actual launch caught this issue beyond static tests. Ranked helper findings no longer crowd all editable strings/colours out of suggestions.

## Unmeasured boundaries

Physical installation, electrical/radio/firmware reliability, manual TalkBack quality and real-device performance are NOT TESTED. No physical phone/units were supplied. Live AI credentials/provider behavior are NOT TESTED; fake-provider boundary checks are explicit. Broad arbitrary third-party app behavioral compatibility is not claimed. Native/obfuscated/Compose/custom-rendered features without qualified patch targets remain limited/unsupported. Each additional template or clone profile needs its own exact-source and behavior evidence. Accessibility/performance interaction coverage is recorded separately from source inventories; an inventory is not a universal PASS.

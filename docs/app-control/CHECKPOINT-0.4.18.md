# App control implementation checkpoint — 0.4.18

Full objective: **OPEN**. HUD controls only its own test fixture today. It does not modify YouTube or any third-party app, and no ad-removal recipe exists.

## Implemented and exercised

- Local Integrations → APP CONTROL screen: selected-package inspection, clear unsupported states, HUD shell-service status/stop, and an explicitly labelled lab section for the owned fixture. The scrolling root carries the system-bar insets so the last control clears the navigation gesture handle.
- Installed APK version, certificate SHA256 and ordered APK/split SHA256 inspection with streaming reads, cancellation, a 512 MiB budget and package-change detection. Private app data is never read.
- Shared `app.control.inspect` and `app.control.plan` agent tools. The provider schema requires `package` and rejects caller-supplied privilege/runtime claims; shared-session approval still gates writes.
- `app.control.fixture.banner` writes ONLY to the prepared owned fixture and requires visible user approval in the shared session. Immutable recipe/target identity primitives and bounded in-memory single-use approval grants exist; they are not yet a general apply/undo workflow.
- Embedded, source-built, pinned patch engine (LSPatch `0dc50f42503711b14f5e2bb217c2bdd6321ce5be`), built in isolation with SDK 37 / build-tools 37 / NDK 29.0.14206865 / JDK 25 targeting Java 21. HUD's own build stays on SDK 35 + JDK 17.
- On-device preparation runs entirely inside HUD: asset digests verified, graded target signature-checked, output signed with a per-install **non-exportable AndroidKeyStore** entry (no bundled or upstream demo keystore), and the result re-verified with `apksigner` before publication to the FileProvider shares path.
- Fail-closed live control: the installed fixture must be byte-identical to the newest APK this HUD prepared and its signer must match HUD's lab key. Confirmed method changes report the target PID back over an ordered, HUD-signature-protected broadcast.
- Installation of the prepared fixture happens only through a user-reviewed `PackageInstaller` flow from the lab screen; HUD never uninstalls or silently replaces anything (signature change means the old fixture must be removed by the operator).
- HUD-owned AIDL service launched from the installed HUD APK by already-authorised ADB as UID 2000: read-only package metadata plus shutdown. Publication needs DUMP plus a real shell/root caller; calls need HUD's UID, source path and pinned certificate; 30-minute bounded lifetime; no shell strings, APK loading or mutation API.
- Removed fabricated Shizuku IPC success paths, stale-permission resurrection and root-presence privilege claims; unsupported custom transaction numbers are reported as unsupported.

## Real validation

125 JVM tests pass: core 58, app 66, owned patch fixture 1.

On-device run on the disposable API 35 emulator (`sdk_phone64_x86_64-userdebug`, launched read-only), with `org.lsposed.lspatch`, `com.termux.api` and `moe.shizuku.privileged.api` **uninstalled** — companion packages present: NONE:

| Test | Result |
|---|---|
| EmbeddedFixturePatcherDeviceTest | OK (1 test) |
| OwnedFixtureControllerDeviceTest | OK (1 test) |
| FixtureAgentApprovalDeviceTest | OK (1 test) |
| AppInspectionDeviceTest | OK (2 tests) |
| EmbeddedPrivilegeDeviceTest | OK (2 tests) |

Screenshots reviewed: the fixture banner is absent when set false and present when restored, with the same fixture PID as the authenticated broadcast receipt; the app-control screen and its scrolled-bottom state are legible and unclipped. Reproduce with `python3 scripts/device/run_embedded_lab.py --serial <explicit-serial> --apk-dir app/build/outputs/apk --log <summary.json>` — order matters, because preparing republishes the fixture.

APK v2 debug signature verified. Not verified: physical phone, ARM64/OEM behaviour, reboot/wireless-pairing recovery, root (UID 0) bootstrap, live model inference, and anything involving a non-owned target.

## Honest capability boundary

Everything above is *owned-fixture* capability. The gap between "HUD patches its own test app and toggles one of its methods" and "HUD silences ads in the installed YouTube build" is the whole remaining problem, not a detail:

1. Rootless hooking needs the target APK re-signed and reinstalled, which destroys the installed app's signature-protected data unless the operator accepts removal, and needs an exact per-version recipe.
2. Signature bypass exists in the engine but is deliberately not exposed: it would forge another developer's app identity. Not implemented, not planned as a default.
3. Real in-process instrumentation requires the target to be started from the prepared APK. HUD cannot silently mutate a running third-party process from an ordinary app install.
4. No story for integrity-checked apps, Play Integrity, or server-side feature evaluation.

So "full dominion over every app, instantly" is not achievable from an ordinary APK, and this build does not claim it.

## Remaining work, in order

1. Staged, verified preparation of a user-selected APK with an explicit per-app warning and a documented undo (archive original; reinstall needs user confirmation).
2. Recipe model: exactly one recipe per package+versionCode+signer+splits set, each with written qualification evidence, loaded from signed local data — never generated on the fly.
3. A first genuinely useful recipe on a target the operator owns, with real before/after evidence.
4. Wireless ADB pairing UI for the privileged bootstrap, plus reboot recovery.
5. Decide the GPL distribution question deliberately: the engine is GPL-3.0-derived and currently ships as an asset inside the HUD debug build. Review obligations, or move the engine behind an optional component, before any public release.

## Artifact

Release directory: `/home/parrot/worktrees/blackwave-unified-20261012/releases/app-control-2026-10-14/hud/` — `validation.json`, `SHA256SUMS`, `apksigner.txt` and the reviewed screenshots sit beside the selected APK. `SHA256SUMS` is the authority for the paused candidate hash; re-read it rather than trusting a copied digest. Selected benchmark copy: `/home/parrot/FR3K-BENCHMARK/hud-latest-debug.apk`; all benchmark hashes verify. The active selection/sync implementation is `/home/parrot/worktrees/blackwave-unified-20261012` — the original BLACKWAVE repository's selection is stale, so do not run its sync blindly.

Source: commit `HEAD` of `/home/parrot/repos/fr3k-hud` (exact SHA recorded in `validation.json`). Unrelated pre-existing BLACKWAVE work is preserved in the tree, so this is not a clean-tree reproducibility claim. APK binaries stay local; nothing is uploaded.

## Reproduce current checks

From `/home/parrot/repos/fr3k-hud`:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :patch-fixture:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :patch-fixture:assembleDebug --console=plain

Then, on an explicitly selected disposable emulator:

    python3 scripts/device/run_embedded_lab.py --serial <explicit-serial> --apk-dir app/build/outputs/apk --log /home/parrot/.hermes/cache/scratch/hud-embedded-lab-summary.json

Expect `RESULT PASS` with every class reporting `OK (…)` and `companion packages present: NONE` when the manager apps are absent. Require the instrumentation text (`OK (n tests)` and no `FAILURES`) for each class; adb exit status alone does not prove a passing test. The host helper in `scripts/device/test_embedded_privilege.py` starts the privileged service only while its instrumentation process is alive. No automatic reconnect or network ADB listener is enabled.

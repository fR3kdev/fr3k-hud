# App control implementation checkpoint — 0.4.17

Full objective: **OPEN**. This build does not patch third-party apps or remove YouTube ads.

## Implemented and exercised

- Local Integrations → APP CONTROL screen: explicit selected-package inspection, clear unsupported engine states, read-only service status and stop action.
- Installed APK version, certificate SHA256 and ordered APK/split SHA256 inspection, with streaming reads, cancellation, size budget and package-change detection. No private app data is read.
- Shared `app.control.inspect` and `app.control.plan` agent tools. The provider schema explicitly requires `package` and cannot accept caller-supplied privilege/runtime claims. Existing shared-session review remains enabled. AI chat can use a remote provider; the inspection engine itself is local.
- Immutable recipe/target identity primitives and bounded in-memory single-use approval grants. They are not yet a complete apply/undo workflow or a trusted signed recipe ingestion system.
- HUD-owned AIDL service launched from the installed HUD APK by already-authorised ADB as UID2000. No Shizuku manager, LSPatch manager or Termux is needed for this service. Only package metadata inspection and shutdown are exposed; no shell strings, APK install, app code loading or mutation API.
- Provider publication requires OS DUMP permission and an actual shell/root Binder caller. Server calls require the installed HUD UID, source path and pinned certificate. Binder generation/lifecycle are tracked; service expires after 30 minutes or HUD process death. Current bootstrap entry supports shell UID2000 only, not root or secondary users.
- Unsupported custom Shizuku transaction numbers and synthetic success output removed. Root presence no longer implies verified privilege; UI probes only after an explicit tap. Stale permission grants cannot resurrect a dead Binder.

## Real validation

125 JVM tests pass: core 58, app 66, owned patch fixture 1. Four unique API35 emulator tests pass: embedded service/negative publication/shutdown and selected-app shared-bus/UI inspection. Both ATD and standard AOSP emulator runs exercised the service; the ATD profile contained no Shizuku/LSPatch/Termux packages. Standard AOSP had pre-existing companion packages, which the new service did not use. ATD screenshot was black and was rejected as visual evidence; the standard emulator produced a visually reviewed readable dark/magenta screen with actual fixture inspection output.

APK v2 debug signature verified. Physical-phone acceptance, ARM64/OEM behaviour, root bootstrap, reboot/wireless-pairing recovery and live model inference are not claimed. The emulator was launched read-only; no personal phone was connected or modified.

## Artifact

`/home/parrot/repos/fr3k-hud/artifacts/FR3K-HUD-0.4.17.apk`

SHA256: `b55f7804a965b9ec86dbbc168f215e6dc7333fcbabf403284cff9706d6a5f276`

Selected benchmark copy: `/home/parrot/FR3K-BENCHMARK/hud-latest-debug.apk`. All 17 current benchmark hashes pass. The active selection/sync implementation is in `/home/parrot/worktrees/blackwave-unified-20261012`; the original BLACKWAVE repository's selection is stale. Original non-HUD firmware candidates and operator test results were preserved; the previous HUD was archived before refresh.

Release/evidence: `/home/parrot/worktrees/blackwave-unified-20261012/releases/app-control-2026-10-14/hud/`.

Source implementation commit: `1db80f8`. Existing dirty BLACKWAVE changes were preserved and included in compilation, so this is not a clean-tree reproducibility claim. APK binaries remain local, not committed or uploaded.

## Remaining work, in order

1. Finish isolated native LSPatch source qualification; initial source checkout failed because libxposed/api and libxposed/service returned 404. Exact gitlink commits were recovered from JingMatrix mirrors, not substituted with current mirror HEADs.
2. Build the pinned native/loader assets with isolated SDK37/build-tools37, both upstream-required NDK29 revisions and JDK25 targeting Java21, keeping HUD's SDK35/JDK17 build unchanged. Initial Gradle qualification reached the core daemon and failed on its different NDK revision/license lookup. Existing accepted workstation licence files were copied into the isolated SDK; no new licence acceptance was invented.
3. Review GPL distribution obligations before bundling the engine. No GPL engine is included in this APK.
4. Prove manager-free loader operation and true/false/true method behaviour on the owned fixture, including authenticated live control, same-PID evidence and Undo.
5. Add staged/verified APK preparation and user-reviewed PackageInstaller flow, then support version-specific recipes. Wireless ADB pairing UI, target runtime and apply/undo remain absent.
6. Qualify any YouTube recipe independently against exact app/version/splits and real playback behaviour. Do not conflate generic patcher compilation with ad-removal capability.

## Reproduce current checks

From `/home/parrot/repos/fr3k-hud`:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :patch-fixture:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :patch-fixture:assembleDebug --console=plain

On an explicitly selected disposable emulator, install the three generated APKs, then run:

    python3 scripts/device/test_embedded_privilege.py --serial <explicit-serial> --output <evidence-file>
    adb -s <explicit-serial> shell am instrument -w -e class com.mcpintelligence.fr3k.appcontrol.AppInspectionDeviceTest com.mcpintelligence.fr3k.hud.test/androidx.test.runner.AndroidJUnitRunner

Require instrumentation `OK (2 tests)` for each invocation; adb exit status alone does not prove a passing test. The host helper starts the privileged service only while its instrumentation process is alive. No automatic reconnect or network ADB listener is enabled.

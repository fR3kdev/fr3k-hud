# Verification and coverage report — fR3k HUD 0.4.21

Honest status of what has been *measured on device*, what has not, and what cannot be claimed. Every
number below comes from a file in `docs/design/evidence/`. Where something is unverified, this report says
so instead of marking it PASS.

Build: `versionName 0.4.21`, `versionCode 421`. Device: `emulator-5582` (headless x86_64, Android 15,
SwiftShader software rendering). Toolchain: JDK 17, Android SDK build-tools 35.0.1.

## 1. Host-side regression suites

| Suite | Result | Evidence |
|---|---|---|
| `:capability:test`, `:core:testDebugUnitTest`, `:app:testDebugUnitTest`, `:patch-fixture:testDebugUnitTest`, `:protocol:test`, `:transport:test` | **322 tests, 0 failures** (app 103, capability 45, core 155, patch-fixture 1, protocol 4, transport 14) | `docs/design/evidence/build.log` |
| Build | `BUILD SUCCESSFUL` for `:app:assembleDebug` + `:app:assembleDebugAndroidTest` | same |

Limit: host tests prove logic, not UI or hardware behaviour.

## 2. Accessibility, 200% font, reduced motion, performance

`scripts/device/run_accessibility_and_performance_lab.py` → `A11Y_PERF_RESULT PASS`.

| Check | Result | Evidence |
|---|---|---|
| Accessible name + window containment for every rendered clickable control, 10 screens, current scale | `OK (2 tests)` | `a11y-at-current-scale.txt`, `a11y-audit/labelled-and-visible.json` |
| Same audit at **200% font scale** | `OK (2 tests)` | `a11y-at-200-percent.txt` |
| Last control reachable and nothing clipped at the end of the content | `OK (2 tests)` | `a11y-audit/scroll-to-end.json` |
| Floating tools still work with animations disabled (**reduced motion**) | `OK (2 tests)` | `reduced-motion.txt` |
| Cold start | **1569 ms** (`TotalTime=1569ms`) | `a11y-perf.json` |
| Frame timing while scrolling | **252 frames, 110 janky, p90 65 ms** | `a11y-perf.json` |
| Memory footprint | **TOTAL PSS 191052 kB** | `a11y-perf.json` |

Defects this lab found and that are now fixed (each was a real, user-visible problem, not a test artifact):

- Seven clickable rows with no accessible name of their own: Settings `RadioRow`, Ask `ToggleChip`,
  Clipboard `ActionRow`, QuickHud `QuickAction`, Handoff `DeviceRow`, wizard scope row, provider model list.
- Settings' provider-preference row clipped its third option (**OPENROUTER**) at the panel edge at every
  font scale, because it scrolled horizontally inside a fixed-width panel. Now stacked full width.
- One layout defect family at 200% font: a control squeezed into a narrow slot beside a text column had its
  label wrapped vertically until the control was clipped and unnamed — `BlackwaveActivity` header BACK
  button and its OTA / LIVE APPLY `CONFIGURE →` buttons. Now stacked full width.

Honest limits of this section:

- **Frame timing is not a hardware claim.** 110 of 252 frames are janky under SwiftShader software
  rendering on a headless emulator. A 60 Hz device result would need a physical device and a
  release-signed build; this is not that measurement.
- **No TalkBack speech traversal was performed.** TalkBack is not installed, so the claim is "every rendered
  clickable control exposes a name in the accessibility tree", not "a screen-reader user completed each
  task". That distinction matters and is not claimed away.
- **Cold start is a debug build** with the test harness installed alongside.

## 3. Capability planes on device

`scripts/device/run_capability_lab.py --serial emulator-5582` → `CAPABILITY_RESULT PASS`
(`artifacts/capability-lab-evidence.json`, `docs/design/evidence/capability-lab.log`).

| Phase | Result |
|---|---|
| Bridge plane through the app's own MCP surface | **19 checks pass** (`verify_bridge_via_mcp.py`) |
| Embedded patch engine prepares and installs a patched build | `EmbeddedFixturePatcherDeviceTest` `OK (1 test)`; on-device prepared APK hash matches the recorded `sha256` |
| Owned-fixture control after install | `OwnedFixtureControllerDeviceTest` `OK (1 test)` |
| Patch plane | `PatchPlaneDeviceTest` `OK (2 tests)` |
| App inspection | `AppInspectionDeviceTest` `OK (2 tests)` |
| Agent-approved write | `FixtureAgentApprovalDeviceTest` `OK (1 test)` |
| Provider preference takes over without restart | `ProviderPreferenceDeviceTest` `OK (3 tests)` |
| Privileged plane, shell tier | `HUD_PRIVILEGED_READY uid=2000 tier=SHELL protocol=2` |
| Privileged plane, root tier | `HUD_PRIVILEGED_READY uid=0 tier=ROOT protocol=2` |

Companions required: **none** — Shizuku, LSPatch manager and Termux are uninstalled for every run
(`"companion_packages": []`). The lab enables the debug-only fixture mode through the harness test and the
run ends with `CapabilityLabModeDeviceTest#disableFixtures OK (1 test)`, so the device is left in release
mode.

Verified engine limitation (not hidden): LSPatch repackaging **drops non-launcher intent filters**, so a
patched fixture loses its deep links. Confirmed by `aapt2 dump xmltree` of the installed APK.

## 4. Release UI and live integrations

`docs/design/evidence/release-ui-and-live.txt` — the production-mode suite (`RedesignUiDeviceTest`,
`FloatingWorkspaceDeviceTest`, `FloatingSurfaceDeviceTest`, `VisibleToolsDeviceTest`,
`LocationAndTextDeviceTest`, `OpenCodeGoLiveDeviceTest`) with a synthetic emulator GPS fix supplied
every 5 s.

`scripts/device/run_release_ui_and_live_lab.py` instruments **one class per process** (a fresh app process
per class). Final result, machine-readable in `docs/design/evidence/release-ui-and-live.json`:

| Class | Result |
|---|---|
| `RedesignUiDeviceTest` | `OK (6 tests)` |
| `FloatingWorkspaceDeviceTest` | `OK (1 test)` |
| `FloatingSurfaceDeviceTest` | `OK (2 tests)` |
| `VisibleToolsDeviceTest` | `OK (6 tests)` |
| `LocationAndTextDeviceTest` | `OK (3 tests)` |
| `OpenCodeGoLiveDeviceTest` | `OK (2 tests)` |

**20 tests, 0 failures, 0 crashes.** Two earlier failures — a missing `app-control-target-package` input,
and `Process crashed` inside `LocationAndTextDeviceTest` — reproduced **only** inside a single combined
20-test invocation and both passed in isolation (`OK (3 tests)`, 75 s, with no `FATAL EXCEPTION` in
`logcat`). They were cross-class state and resource accumulation, not product defects; running class by
class removed the condition instead of retrying around it. A third failure was a genuine harness bug: the
scroll helper stepped over a one-line guide label between samples, so a present "limits — read this" label
was reported missing (the failure dump proved the limits *body* was in the tree). `find(...)` now takes a
scroll `span` and short-text lookups use a small one.

Live OpenCode Go round trip (real network, real key on the device only): health check and a real
tool-call round trip (`app__control__inspect`) both `OK`. Contract: base
`https://opencode.ai/zen/go/v1`, `Authorization: Bearer`, and `x-opencode-session` (without it the API
answers `400 {"type":"error","error":{"type":"MissingSessionID"}}`).

## 4b. A real third-party app, through the running HUD

`scripts/device/verify_real_app_path.py` → `REAL_APP_RESULT PASS`
(`docs/design/evidence/real-app-path.json`). Every step is the product surface, not a shortcut:

| Check | Result |
|---|---|
| Target is a real, user-installed app (`/data/…`, not a fixture, not a system package) | **com.termux** |
| APP CONTROL reached through the Deck (the activity is not exported) | PASS |
| Package typed into the product's own target field | PASS |
| Product `MAP SELECTED APP HOME` control found and pressed | PASS |
| Mapping outcome reported by the product itself | **"Recorded 8 fingerprinted screen controls"** |
| Capabilities read back over MCP for that app | **49, every one bound to the real installed identity** |
| Fixture capabilities visible in the normal catalogue | **none** |
| Read-only identity of another real app (`com.android.deskclock`) over MCP | versionCode 35, signer `a40da80a…` |
| Journal records the work | PASS |
| Selection removable through the product UI (device left as found) | PASS |

Two boundaries this measurement makes explicit, because they are real and not worked around:

- **Android system packages cannot be mapped.** `BridgeGuard` refuses Android system apps as automation
  targets, so `com.android.deskclock` can be selected and inspected but not mapped. That refusal is a
  boundary, not a bug, and it is why the mapped target here is Termux.
- **A target app's own permission dialog can appear mid-mapping.** Mapping launched Termux for the first
  time and Android asked whether Termux may post notifications. HUD does not click through another app's
  permission dialog on the operator's behalf; the harness declines it and records that it did.

## 5. What is NOT verified, or cannot be claimed

- **Any arbitrary third-party app, anything, instantly.** Not achievable from an ordinary APK and not
  claimed. Everything in the product is bounded by the four planes in `docs/app-control/ROUTING-MATRIX.md`.
- **YouTube ads / live third-party hooking.** Rootless hooking needs re-sign + reinstall against exact
  per-version recipes; signature bypass is deliberately not exposed.
- **Physical hardware, real Bluetooth fleet devices, direct peer transport.** The emulator cannot validate
  them; BLACKWAVE transport rows say "unavailable" with the reason rather than faking a result.
- **Human comprehension study, before/after task time.** No participants; none measured.
- **Release-signed performance.** All timing is a debug build on software rendering.

## 6. Reproducing everything in this report

```bash
cd ~/repos/fr3k-hud
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk
./gradlew :capability:test :core:testDebugUnitTest :app:testDebugUnitTest \
          :patch-fixture:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb -s emulator-5582 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/device/run_accessibility_and_performance_lab.py --serial emulator-5582 \
        --log docs/design/evidence/a11y-perf.json --repo .
python3 scripts/device/run_capability_lab.py --serial emulator-5582 --repo . \
        --log /home/parrot/.hermes/cache/scratch/cap-lab-run.log   # enables, and always leaves, lab mode
python3 scripts/device/run_release_ui_and_live_lab.py --serial emulator-5582 \
        --log docs/design/evidence/release-ui-and-live.txt        # one class per process
python3 scripts/device/verify_real_app_path.py --serial emulator-5582 \
        --log docs/design/evidence/real-app-path.json             # real third-party app, product surface
python3 scripts/release/package_hud_release.py --status <VERDICT> # benchmark candidate + hash re-verification
```

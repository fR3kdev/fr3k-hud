# App control implementation status — 0.4.19

## What this build is

fR3k HUD is a single user-facing controller for inspecting, patching and driving the behaviour of
Android apps, with every engine embedded in the APK, no companion app required, and no root required for
the ordinary planes. `docs/app-control/ROUTING-MATRIX.md` is the supported-behaviour contract;
`docs/app-control/FRONTIER.md` is the research reference. The 0.4.18 checkpoint is preserved verbatim at
`docs/app-control/CHECKPOINT-0.4.18.md`.

## The four planes

| Plane | What it needs | What it can do |
|---|---|---|
| BRIDGE | the accessibility service bound (no root, no companion) | read trees, confident clicks, text entry, screen state assertions, masks, app maps, fused workflows |
| PATCH | the embedded engine (no root, no companion) | prepare, sign, verify and publish a patched build of an owned target, with an undo archive |
| PRIVILEGED | a one-time ADB bootstrap of HUD's own AIDL service (no root) | typed app-op read/set/restore, package metadata, private directory *names* |
| PRIVILEGED + root | the same service started from a root shell | root-gated operations, reported as granted rather than assumed |

Capability records are inventory-bound: nothing is registered without an exact `package + versionCode +
signer + APK hashes`. The step grammar is closed (11 steps; no shell, script, exec or path step), and a
drifted build is refused by naming both the expected and the observed identity.

## Verified on a disposable emulator (`CAPABILITY_RESULT PASS`)

All of the following ran against a real API 35 emulator with `org.lsposed.lspatch`, `com.termux.api` and
`moe.shizuku.privileged.api` **uninstalled** — companion packages present: NONE.

| Check | Evidence |
|---|---|
| Bridge plane, 13 assertions, driven through HUD's own MCP surface | `BRIDGE_RESULT PASS` |
| Ad-skip reaches content | screen state advanced after a fingerprint-confident click |
| Mask darkens the covered region | bounds `[84, 211, 1080, 337]`, measured luma 83.8 -> 0.0 |
| Unmask clears the overlay | overlay count 0 |
| App map -> commands | 3 owned screens compiled into 3 commands |
| Fused workflow across two apps | 2 journal records across `patchfixture` and `patchfixturetwo` |
| Workflow inverse | a real `run_capability` inverse recorded |
| Unknown goal refused | closest matches named, nothing executed |
| MCP surface | 9 closed-schema tools, token required (401 without), localhost only |
| Patch plane | `PatchPlaneDeviceTest` OK (2 tests), including the mismatched-identity refusal |
| On-device prepared APK | hash of the APK pulled back from the device matches the recorded digest |
| Owned-fixture control | `OwnedFixtureControllerDeviceTest` OK (1 test) |
| Inspection | `AppInspectionDeviceTest` OK (2 tests) |
| Agent approval | `FixtureAgentApprovalDeviceTest` OK (1 test) — reject dispatches nothing, approve runs the real hook |
| Privileged, shell tier | `HUD_PRIVILEGED_READY uid=2000 tier=SHELL protocol=2`; root-gated operations refused by tier |
| Privileged, root tier | `HUD_PRIVILEGED_READY uid=0 tier=ROOT protocol=2`; root-gated operations granted |

166 JVM tests pass: capability 38, core 58, app 69, owned patch fixture 1.

Two real defects were found by these tests and fixed rather than worked around: the selector matcher
scored a single declared criterion below its own threshold (so a content-description-only selector could
never match), and the patch engine checked a caller-supplied APK against a hash of that same file instead
of against the identity's recorded hashes, which made the guard vacuous. Both now fail closed.

## Reproduce

    cd /home/parrot/repos/fr3k-hud
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ./gradlew \
      :capability:test :core:testDebugUnitTest :app:testDebugUnitTest :patch-fixture:testDebugUnitTest \
      :app:assembleDebug :app:assembleDebugAndroidTest :patch-fixture:assembleDebug \
      :patch-fixture-two:assembleDebug --console=plain
    python3 scripts/device/run_capability_lab.py --serial <explicit-serial> --repo . \
      --log /home/parrot/.hermes/cache/scratch/cap-lab-summary.json

Expect `CAPABILITY_RESULT PASS` and `companion packages present: NONE`. Require the instrumentation text
(`OK (n tests)` and no `FAILURES`) per class: an adb exit status alone does not prove a passing test.
`adb root` is required for the root tier; without it that phase records `NOT TESTED` instead of passing.

## Environment constraints discovered

- An accessibility service cannot be hosted inside an instrumentation process. Bridge-plane verification
  therefore runs against the normally-running app through its own localhost MCP surface, which is the real
  product path anyway.
- The privileged service must be started by the host (ADB) while the HUD process is alive; it cannot be
  started from instrumentation. The lab starts it and the test waits for it.
- Overlay and node operations must run on the accessibility service's thread. Calling `WindowManager.addView`
  from a worker thread throws "Can't create handler inside thread that has not called Looper.prepare()";
  every bridge entry point now marshals to the main thread.

## Still open, honestly

1. YouTube ad removal is **not implemented** and is not close: it needs a per-version patch set qualified
   on a real device, and a rootless patch means re-signing and reinstalling the target, which destroys its
   signature-protected data. The signature-bypass path exists in the engine and is deliberately not exposed.
2. One genuinely useful recipe on a target the operator owns, with recorded before/after evidence.
3. Wireless-ADB pairing UI for the privileged bootstrap, plus reboot recovery.
4. The GPL-3.0 question: the embedded engine descends from a GPL-3.0 project. For a private build this is
   the operator's call; before any public distribution the obligations must be reviewed deliberately.

## Artifact

Local: `artifacts/FR3K-HUD-0.4.19.apk` with `.sha256` and `.meta.json` beside it, plus
`artifacts/capability-lab-evidence.json`. APK binaries stay local; nothing is uploaded.

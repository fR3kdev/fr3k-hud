# FR3K HUD 0.4.24 — two-stage wireless ADB and Termux copy-command workflow

## Request

Operator already paired HUD with Wireless debugging and wants Termux independently paired, both ready at once, with a copy button for every Termux command needed in the walkthrough.

## Implementation

- Existing HUD ADB identity preserved, never copied into Termux.
- Onboarding CONNECTIONS now has STEP 1/2 HUD and STEP 2/2 TERMUX, with separate readiness flags. HUD readiness is backed by HudPrivilegedBridge service; Termux ADB readiness requires a real Termux RUN_COMMAND invocation returning exit 0 and the Android shell UID 2000 marker. Unreachable Termux bridge is BLOCKED/NOT CONNECTED, not green.
- Commands each have a native Android clipboard button and positive COPIED feedback: dependency install, Termux storage access, helper install, new Termux pairing, status, reconnect.
- Wireless overlay and expanded walkthrough also show Termux's separate pairing procedure and copy buttons.
- HUD bundles the Termux pairing helper as an asset and exports a byte-identical copy into public Android Downloads on operator action. User explicitly installs the script into their Termux private home bin; app does not silently run arbitrary Termux commands.
- Helper validates private/loopback IPv4 and port, requests a NEW six-digit Android pairing code privately via getpass, uses Termux's existing Android ADB key, and verifies Android shell UID 2000. It stores only the private endpoint host and connection port in the local Termux config (mode 0600), never the pairing code.
- Android pairing port is not the same as the connection port. Pair using Android's NEW Pair device with pairing code sheet; maintain HUD's existing pairing. Reconnect after Wireless debugging changes its port.
- No socket exposed to public network, no root access claimed, no existing HUD credential altered.

## Evidence

- On physical OnePlus, Termux android-tools upgraded to 37.0.0p1. The upgrade exposed libprotobuf and libc++ mismatches; updating libprotobuf to 36.2 and libc++ to 30 restored the ADB version command and device listing.
- Physical Termux CLI reports NOT CONNECTED, TERMUX ADB KEY PRESENT, no saved endpoint, no connected devices. Device-specific pairing code and ports have not been supplied; real pairing remains PENDING ANDROID USER ACTION.
- Emulator Gradle core/test/app APK builds successful.
- TermuxCommandCopyDeviceTest confirmed native clipboard read-back with foreground app, and verified the exported file bytes matched the bundled asset exactly.
- Emulator onboarding UI navigated through WELCOME/PERMISSIONS/CONNECTIONS, verified both stage labels, tapped COPY COMMAND and observed COPIED feedback.
- Status labels don't confuse HUD pairing with Termux's separate identity or Termux RUN_COMMAND IPC.
- No credentials, pairing code or private device identifier recorded.

## Outstanding acceptance

- Android operator must display a new pairing sheet while Termux is available (split screen recommended), enter pairing and distinct connection ports when running the command interactively.
- Independently verify Termux ADB status and HUD's bridge simultaneously. Both READY must be observed in the installed 0.4.24 UI before claiming full real-phone acceptance.
- APK signing identity/upgrade must be accepted by the phone. UI start does not prove installed update.

OBSERVE → VERIFY → RECONCILE → IMPROVE → TEST → RECORD.

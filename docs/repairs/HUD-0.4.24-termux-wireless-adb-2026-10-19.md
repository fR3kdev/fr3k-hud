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

## Release and physical transfer checkpoint

- Final signed debug APK uploaded to private FR3K research release hud-0.4.24-termux-adb-rc-20261019.
- APK size 105137862 bytes; SHA-256 d68dd5ffb0aa228f29405d12690f48ba428f83ecc7b72b380f9520060de92f9b.
- On owned OnePlus 7 Pro, authenticated GitHub release download SHA-256 PASS. Termux-to-Android-Downloads copied APK SHA-256 PASS, exact same bytes.
- Android package installer VIEW intent dispatched from Termux. Initial installed package hash still 82bd88b6dcb9313edaf00e413d2bf60ab2931d186fbd540e4da53f36f9df02ac (previous 0.4.23 candidate), so 0.4.24 installation NOT VERIFIED at that check. Do not confuse intent dispatch with installed APK.
- Termux ADB v37 executable works. Separate Termux pairing status remains NOT CONNECTED. HUD pairing was not modified.

## Physical phone follow-up: 0.4.24 installed

- A fresh authorized Desktop Commander read on OnePlus 7 Pro found Android package `com.mcpintelligence.fr3k.hud` installed with SHA-256 `d68dd5ffb0aa228f29405d12690f48ba428f83ecc7b72b380f9520060de92f9b`. This matches the 0.4.24 release candidate exactly. **VERIFIED FACT: 0.4.24 installed.**
- Parrot `apksigner` found identical signing-certificate SHA-256 for 0.4.23 and 0.4.24; no signature mismatch.
- Immediately after upgrade, localhost MCP was unavailable. An explicit broadcast to the app's exported START_MCP receiver completed, and the listener on 127.0.0.1:8791 returned HTTP 401 with token-required. **OBSERVATION:** server responds and enforces a bearer token; authenticated physical-device tool execution is still UNKNOWN.
- Physical Termux Android ADB 37.0.0p1 works, but its own identity remains NOT CONNECTED (no endpoint stored; `adb devices` contains zero connected endpoints). HUD's separate pairing keys/identity have not been modified.
- The native Termux helper's `setup` command was copied to the phone's clipboard, and Android Developer options open was requested. **USER ACTION BLOCKED:** Android must show a fresh Pair device with pairing code sheet, and the Termux helper must receive fresh pairing/connection ports and a six-digit code privately on-device. Do not record the code in GitHub or this chat.
- The source, signed build, and emulator functional tests remain as above. Installed APK byte identity is not evidence that 23 individual UI screens or physical device control all work.

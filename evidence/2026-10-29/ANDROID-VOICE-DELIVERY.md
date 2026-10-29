# Android voice candidate delivery — 2026-10-29

Status: PARTIALLY_VERIFIED. These are feature-branch candidates, not an assertion that every voice interaction, physical device action or newer mainline change is finished.

## Source

- HUD: https://github.com/fR3kdev/fr3k-hud/tree/feat/voice-assist — implementation commit `69c55f96f7f1dd365524d29700757ed5275cbec2`.
- BLACKWAVE: https://github.com/fR3kdev/fr3k-blackwave/tree/feat/voice-bridge — implementation commit `db6794df3ee0a528571dd117119dc9faadb2850a`.
- Both remote branch SHAs read back after push. Companion Actions reports startup_failure with no jobs; local build success is not GitHub CI success. Neither branch was merged.

## Changes

- Initial voice setup offers Groq (recommended rate-limited free tier), OpenCode Go and OpenRouter, with provider-specific signup/key links and guidance. Key entry is masked; storage uses the HUD encrypted secret store.
- Public model discovery is distinct from authenticated inference. Selection is saved only after an actual inference response succeeds. The shared session reads selected provider/model on each turn and retains native tool calls and existing approval gates.
- Groq default: `openai/gpt-oss-120b`. OpenCode Go default: `deepseek-v4.1-flash`. OpenRouter funded default: `deepseek/deepseek-v4.1-flash`; free tier uses a zero-price model, preferring `openrouter/free`. HTTP 402 during setup retries the advertised free router and verifies it before saving. No cross-provider fallback.
- The companion voice card is on the opening Fleet page. One-tap conversation uses a real HUD TTS-completion event before reopening recognition, not answer arrival. Mic mute and typed submission remain available. The welcome uses shared HUD speech without requiring an LLM key.
- AndroidVoice now distinguishes stale/failed TTS callbacks, bounds the actual provider turn, refreshes saved tuning and cancels queued capture/playback on stop. The HUD mute control invokes the persistent microphone guard.
- Optional Hermes-in-Termux panel has explicit consent, signed APT/fingerprint checks, dedicated phone profile configuration, and a live-response verification gate. Generic send controls say agent. Hermes-specific launch appears only after successful verification.
- Xiaozhi endpoint `https://voice.fr3k.dev/xiaozhi/ota/` is live and now handles refusals honestly. A read-only probe (`evidence/2026-10-29/xiaozhi-live-probe.txt`) got HTTP 200 with a binding code, `wss://voice.fr3k.dev/xiaozhi/v1/` and a token; the WSS handshake opens and the server answers an unbound device with `{"type":"stt","text":"请登录控制面板，输入508752，绑定设备"}`. That text used to be swallowed and reported as "Voice channel failed". `VoiceSession` now emits `Event.ServerNotice`, the controller maps it to `ACTIVATION_REQUIRED` and keeps the text, and the panel shows the server's own binding instruction plus the OTA code. A frame with no text still fails without UI copy.
- CameraX upgraded from 1.3.4 to 1.4.2 after the former failed ARM64 ELF 16KB-page alignment. All final ARM64 LOAD alignments and APK ZIP checks pass.

## Artifacts

- `/home/fr3k/FR3K-HUD-0.4.16-voice.apk` — Android 12+ (minSdk 31), SHA256 `481bd5359bf8ee889df3c43dd35350bc098a3f74f31f98bb3c1c085436cba671`.
- `/home/fr3k/FR3K-BLACKWAVE-0.1.0-voice.apk` — Android 8+ (minSdk 26), SHA256 `3f4e81f740350fa5f499e67e2575328f21585f2aee1e66683e043a19f0c1b658`.
- Both signatures: `d763d5f7853f6b1d4ca88d2d0fd21ad53414e9c600e753526dd4110c724894b3`.
- Both install on the test emulator. An older emulator installation had a different signer and could not be updated. Only emulator copies were removed; do not uninstall a real user's app without preserving data and approval.
- Previous root APKs preserved under `/home/fr3k/BLACKWAVE-STANDALONE/dist/voice-previous/`. Local selected copies and manifest are under `work/blackwave/releases/android-voice-2026-10-29/`; historical imported benchmark records remain unchanged.

## Verification and reproduction

- Java 17, Gradle wrapper: HUD `:app:assembleDebug :app:testDebugUnitTest :core:testDebugUnitTest --offline --max-workers=1`; companion `:app:assembleDebug :app:testDebugUnitTest --max-workers=1`. All passed.
- Clean staged-source exports built and tested separately. Missing companion launcher drawable was caught by the export build and added before pushing. Exported-source suites: 183 tests, zero failures; local affected-module suites: 226 tests, zero failures. See `delivery-unit-tests.json`; imported uncommitted tests explain the difference.
- `python scripts/device/test_voice_setup_matrix.py` checks the test driver's override parsing, screenshot dimensions, scroll-container coordinates and tap bounds.
- `python scripts/device/voice_setup_matrix.py --evidence <dir>` drives real UI. `provider-ui-final-confirmed/results.json` records passes at 720x1600@320, 1080x2400@420 and 1440x3120@560: initial voice screen, every provider selected with matching instructions, actual HTTPS invalid-Groq-key rejection, mute/typed input/unmute and optional Hermes setup gating. XML and screenshots preserved.
- `python scripts/device/companion_voice_matrix.py --evidence <dir>` drives the companion. Keep each attempt's results: renderer crashes and assertion failures are not successful matrix runs. Cross-app welcome completion has been observed through real HUD callbacks. The final matrix result is stored separately.
- Emulator must have a real modern framebuffer (`-skin 1080x2400`); a 320x640 physical framebuffer clamps taller overrides. Assert both effective wm size and PNG dimensions. The original indirect SwiftShader renderer exited with code 139 during companion testing; a software-renderer retry is recorded separately. No app-failure conclusion is drawn from a host emulator crash.
- Artifact structure, signing, class presence and native alignment evidence: `delivery-apk-verification-final.json`.

## Remaining real blockers / unverified behavior

1. No valid user provider key was available on the emulator. Positive live-provider model selection and complete transcript → inference/tool action → speech → listen have not been exercised. Controlled HTTP fixtures test those contracts; they are not real-provider results.
2. Official Hermes Termux documentation currently explicitly says its package is broken: https://hermes-agent.nousresearch.com/docs/getting-started/termux . No successful installation is claimed.
3. The existing Termux bridge has no private stdin credential channel. The flow intentionally does not expose the saved key through clipboard, shell arguments, history or shared files. Direct masked entry inside the Hermes wizard remains necessary; fully automatic reuse of the supplied key is NOT implemented.
4. Xiaozhi endpoint configuration remains `https://voice.fr3k.dev/xiaozhi/ota/`, but activation and live Opus/audio interoperability are unverified. No new provisioning request, production deployment, RF transmission or firmware flash was performed in this pass.
5. Physical microphone recognition, audible playback and all device/privileged actions are not comprehensively verified. A synthesis completion is not a claim that audio was heard from a physical speaker. All existing tool approval gates remain.

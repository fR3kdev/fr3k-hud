# Android integration pass — active, not complete

Full requirements: ../ANDROID-TESTING-PASS.md and original attachment. Do not adopt the previous handoff statement that later clusters are omitted. All 13 sections remain in scope.

## Resume state
- Physical OnePlus 7 GM1900 connected over USB, adb serial f4c8d828. HUD 0.4.16/416 installed; Termux RUN_COMMAND granted. BLACKWAVE companion com.fr3k.blackwave.debug installed.
- Preserve dirty working tree and phone data. No clear/uninstall performed.
- Baseline launch and UI hierarchy captured at /home/parrot/FR3K-BENCHMARK/evidence/android-pass-2026-09-09-usb/baseline.xml.
- Prior work: /home/parrot/FR3K-BENCHMARK/AGENT-HANDOFF.md. Prior cluster 4 runtime only partial.

## BUG → ROOT CAUSE → FIX → TEST → RESULT
1. Invalid OpenRouter key validates successfully → health uses public /models → changed health to authenticated /key; missing-key label clarified → actual HTTPS probes using deliberately invalid dummy key: /models 200, /key 401 → root cause reproduced; regression test pending.
2. Default pinned free model instead of free router → literal Llama slug in provider/settings → default changed to openrouter/free (existing explicit selection preserved) → official OpenRouter free-router page verified → build/runtime pending.
3. Free catalogue classification only checks input price → output-priced model could be advertised free → require prompt and completion price zero → regression pending.
4. Provider error might echo API key returned in server error → raw error forwarded to UI → redact active key in ask/health failures → regression pending.

## Confirmed remaining architecture gaps
- AgentToolBus registered tools, but inspected chat/browser chat still invoke standalone Hermes commands. Shared conversation and actual model tool-dispatch need implementation/verification.
- BrowserAgentTool only open/back/forward/reload/URL/title. Missing click, scroll, text, submit, DOM/page state/text/download handoff. Navigation success reported before load outcome.
- TermuxAgentTool calls real bridge but hook writes command only; no stdout/stderr/exit mirrored to visible overlay. Window not shown by hook.
- TermuxHealth Unknown renders OFFLINE; permission-failure claims serviceLive=true without observation; probe exception handling needs review.
- Onboarding previous implementation only welcome/permissions/ready; full provider/tool/BLACKWAVE setup sequence outstanding.
- BLACKWAVE actual companion wizard/integration and real discovery/pairing need audit, not only HUD screens.
- Full diagnostics and cold-start/permission/offline/recovery matrix remain unverified.

## Next actions
Run provider regression tests, build/phone install when ready, refresh benchmark selection/hashes after successful APK per AGENTS.md. Implement shared session/tool architecture and complete real-device tests. No completion claim until all original requirements verified.

### Test execution checkpoint
Focused provider regression launched: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ./gradlew :core:testDebugUnitTest --tests '*OpenRouterProviderTest' --console=plain`. Output `/tmp/fr3k-openrouter-regression.log`; exec session 28614. Last poll: live, core Kotlin compilation reached. Poll before restarting. New test file `core/src/test/java/com/mcpintelligence/fr3k/integrations/openrouter/OpenRouterProviderTest.kt`. No APK built or installed from these new edits yet; benchmark candidate unchanged.

Reference verification: https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key and https://openrouter.ai/openrouter/free/apps . Direct invalid-dummy-key HTTPS probes establish /models=200 versus /key=401.

## Continued implementation checkpoint
- Previous turn classified PROGRESS: reproduced auth bug, edited provider, added tests.
- Provider three focused tests PASSED; subsequent full core + app unit suite PASSED (`/tmp/fr3k-tools-regression.log`, BUILD SUCCESSFUL).
- Browser DOM actions now implemented with JSON-escaped arguments, selector validation, visible-element checks, form validation, text/element extraction. All browser tool calls serialized on Main; load callbacks return observed failure/success, navigation timeout explicit. Compact fixed button sizes actually retained; resize no longer accumulates drag delta and bounds use phone display. New source not yet physically verified.
- Termux bridge busy-spin replaced by cancellable delay and finally cleanup; timeout no longer implies process killed. ResultSlot completion synchronized. Tool cancellation propagates. Terminal tool displays actual command/cwd and mirrors returned stdout/stderr/exit without executing twice. Surface tool registrations removed on service destruction. Unknown Termux state now NOT CHECKED instead of false OFFLINE.
- Added actual-device tests `app/src/androidTest/.../VisibleToolsDeviceTest.kt`: local HTTP fixture drives real WebView; real Termux printf/stderr/exit command checks visible transcript. Test APK not yet built/run.
- Benchmark authoritative selection tool found at `/home/parrot/repos/fr3k-blackwave/scripts/sync_benchmark.py`, selection under that repo releases. This is a separate repo from fr3k-blackwave-tab5-sdr; audit real companion source path before editing.

## Next integration implementation design (not implemented yet)
The shared bus is currently a registry only. Both chat surfaces choose OpenCode before Hermes and never consult OpenRouter or dispatch the tool bus. A tool API alone therefore does not prove agent control.

Implement one application-owned session with a message history and a StateFlow transcript, collected by HUD and browser chat. Use native OpenRouter function calls (safe function names mapped to tool IDs), feed actual tool results back into the same message history, and cap per-turn calls with an explicit continuation message. Preserve assistant explanations before actions. Keep credentials outside messages. Browser page text is untrusted tool output, not a system instruction. Dispatch browser and Termux through the existing bus; register BLACKWAVE read/query tools against the app bridge singleton and retain underlying scope/auth checks. Shell and consequential actions need a reviewable exact command/action request in the app before execution; the session should expose pending approval to both surfaces. Unknown tools and malformed calls return errors, never fabricated success. Model selector must target the provider used by this session (currently it targets OpenCode only).

Onboarding remains WELCOME/PERMISSIONS/READY and a skipped setup is recorded identically to complete. Extend with provider/key/model, Termux probe, browser self-test, BLACKWAVE setup/discovery/link, and full diagnostic status. Persist skipped versus verified steps distinctly and refresh permission state on return (current remembered permission booleans can be stale).

Actual companion source: `/home/parrot/repos/fr3k-blackwave/apps/android-companion`; benchmark validation confirms it. Its BlackwaveRoot currently renders 'Cached/bundled data only · live apply, OTA and identity are disabled'. Do not mistake HUD BlackwaveActivity for this companion. Add a functioning connected setup path in the companion using its real transport/auth contracts. Preserve signed envelopes, replay protection, scope/risk ceilings, and measured capability identity. No RF or firmware flashing performed in this Android pass.

## Live build checkpoint
`./build.sh :app:assembleDebugAndroidTest` started, exec session 72814, `/tmp/fr3k-device-test-build.log`. At 15:56 AEST still live, Gradle PID 29548; jstack confirmed downloads/compiler activity. Do not restart solely for quiet output. Once complete, install both app and test APKs with `adb install -r`, run `com.mcpintelligence.fr3k.hud.test/androidx.test.runner.AndroidJUnitRunner` selecting VisibleToolsDeviceTest. Refresh benchmark after successful new app package; keep historical operator evidence.

## Verified device checkpoint — 2026-09-09
Build session 72814 COMPLETE (exit 0), signed APK sha256 29a233736cc3e9f32d8f49380691840d390b736886bf6025e5252fbe1437fa37. App + instrumentation APK installed with `adb install -r`, both Success. Instrumentation exec session 69862 COMPLETE: OK (2 tests), 1.742 seconds. Browser fixture and real Termux output tests passed on the physical phone. See evidence/android-pass-2026-09-09-usb/visible-tools-instrumentation.txt in benchmark. This verifies tool implementations, not an AI agent tool loop (still absent).

BUG → ROOT CAUSE → FIX → TEST → RESULT: browser could be called off main and reported navigation before load → direct WebView methods with immediate success → serialized Main-thread actions await actual callbacks → device fixture navigation and HTTP404 assertions → PASS. Missing DOM actions → navigation-only wrapper → fixed escaped JS operations → real page click/input/form/text/selector failures → PASS. Terminal tool hid results → command-only hook → show console and mirror real bridge result → actual stdout/stderr/exit7 matched visible transcript → PASS. Bridge busy-spin/callback leaks → yield loop and no finally → delay + finally cleanup → existing parser tests and device execution PASS (timeout-specific stress still pending).

Full 92 host tests PASS before final Android-test dependency additions; final app/test APK compiled successfully. Benchmark packaged under fr3k-blackwave/releases/ecosystem-2026-09-09/android-tools, selection and main handoff updated. Previous cluster APKs retained as historical evidence. Hash check first invoked from wrong cwd, then all 12 verified from benchmark directory.

## Shared agent implementation — source checkpoint (not yet packaged)
Previous turn was PROGRESS: packaged/installed browser+Termux fixes and passed actual-device tests. New edits: core SharedAgentSession owns native conversation/tool history, serialized turns, shared transcript/busy/pending-review StateFlows, explicit action decisions, cancellation without replay, bounded loop/history. OpenRouterProvider implements native chat-completion tool schema and result messages. Both HUD and browser send into the application singleton and collect its transcript; one review panel appears on both surfaces. Model button now opens actual OpenRouter settings instead of changing unrelated OpenCode. New BLACKWAVE GET-only fleet/device/role tool uses existing authenticated bridge; it does not enable firmware/control/RF.
Tests added for exact approval, declined action never executing, cancellation recovery and retained conversation. Build/test launched `/tmp/fr3k-session-regression.log`, exec session 15574; observed Kotlin daemon fallback (known environment issue), not terminal at last check. New changes after launch require another compile if not picked up. Latest installed/benchmark APK is still previous 29a2337… candidate until the next successful package. Native API contract checked against https://openrouter.ai/docs/guides/features/tool-calling .

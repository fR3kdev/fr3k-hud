# FR3K HUD 0.4.22 — repair/evidence checkpoint

Date (Brisbane): 2026-10-19. Source: this repository. DEBUG candidate, not a production release.

## Operator-reported issues

1. MCP screen shows the word token with no value.
2. Chat reply appears to stop and subsequent attempts do not work.
3. App Mod after APK selection has no obvious next step.
4. Initial onboarding omits key integrations and permissions.
5. Model picker shows too few choices.

## Changes

- ModsActivity reads the live in-memory MCP server token after explicit START, instead of trying to read an unwritten mcp.json. Secret not persisted to repo.
- Fr3kChatBubble removes the six-line transcript limit and ellipsis; embeds transcript in a scrollable view. Busy send explains how to stop/retry.
- SharedAgentSession reports rejected concurrent requests; unanswered approvals time out after 120 seconds, fail closed, and unlock state.
- AppModDiscoveryActivity provides next-step instructions, selectable-count and explicit BLOCKED state when no patch recipe is executable. No automatic installation.
- OnboardingActivity now includes WELCOME / PERMISSIONS / CONNECTIONS / AI / READY steps. Checks real overlay, notifications, accessibility bridge, wireless ADB shell, Termux round-trip, camera, microphone, location, Bluetooth, APK install access, usage access and battery optimization. Inline provider selection, encrypted key storage, model picker, catalogue refresh and provider connectivity test. Skips recorded as DEFERRED and permissions refresh on return.
- SettingsActivity adds reopen onboarding and full searchable model dropdown for OpenCode and OpenRouter, replacing three/six options.
- OpenRouterProvider exposes all fetched entries separately from free subset.
- Version 0.4.22 / code 422 and matching build-metadata baseline.
- ChatTranscriptRegressionDeviceTest tests 20-line reply and second turn.

## Evidence

- Core JVM: 81 tests, zero failures/errors.
- App JVM: 69 tests, zero failures/errors.
- Debug APK and androidTest APK: BUILD SUCCESSFUL.
- Android emulator version 0.4.22: START MCP displays a non-empty token, bearer /tools succeeds, 15 tools advertised.
- Android emulator AppModMcpDeviceTest OK (1 test); unapproved preparation rejected.
- Android emulator ChatTranscriptRegressionDeviceTest OK (1 test); twenty lines retained; second reply accepted.
- First-run onboarding emulator navigation: welcome → permissions → connections → AI; inline key/model controls visible.
- SHA-256 APK: eb437fca02df90aa7f0c515f9672f3ef147bab6742522877d497bb2322fa6bf2.
- Debug signing cert SHA-256: a28ac772fc0088cee4c30465382084fe6fa2aa4531b7f5a60624e0f2a33816a4.

## Unknown / blocked

- OnePlus 7 Pro live HUD has not been replaced, so authenticated real phone MCP and live chat/provider behavior still UNKNOWN.
- Android permission prompts and APK replacement require operator approval; do not silently grant access.
- Arbitrary target APK selection does not guarantee a compatible executable recipe. Signer changes and split APKs may block installation.
- Model catalogue membership does not prove chat/tool compatibility and may incur billing. No paid-model tests initiated.
- No API keys, bearer tokens, pairing codes, private telemetry, or personal device details included here.
- This is a privately distributed debug candidate, not a public production release.

OBSERVE → VERIFY → RECONCILE → IMPROVE → TEST → RECORD.

## Walkthrough navigation follow-up

- Every expanded APP MODS guide now ends in a GO TO FEATURE destination button.
- Destinations are resolved from the guide id: import/preview/install -> App Mod; owned-module patch -> exact module screen; wireless setup -> pairing screen; AI -> model/provider settings; agent MCP -> scroll to active MCP controls; app identity/control -> App Control; workflow/capability guides -> direct Workflows tab.
- The Workflows destination is honoured on MainActivity initial launch and onNewIntent.
- GuideNavigationContractTest asserts production procedure destinations plus the lab/capability-guide action labels.
- Android emulator UI smoke test: filtered 'Inspect an installed app' -> expanded walkthrough -> GO TO APP CONTROL -> real App Control screen with INSPECT SELECTED APP and BROWSE INSTALLED APPS visible. PASS.
- APK picker regression: initially BLOCKED when test fixture file was absent from Downloads. After pushing the owned fixture APK to Download, AppModJourneyDeviceTest#documentPickerImportsWithoutStoragePermission passed OK (1 test), confirming the real DocumentsUI import path with fixture.
- Source/build artifact SHA256 (post-navigation): eb437fca02df90aa7f0c515f9672f3ef147bab6742522877d497bb2322fa6bf2

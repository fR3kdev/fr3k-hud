# FR3K HUD 0.4.23 — diagnostic MCP and chat approval repair

Status: DEBUG candidate; compiled and emulator tested, not a verified OnePlus installation.
Date: 2026-10-19 (Australia/Brisbane).

## User observed

- Chat repeatedly requests tool authorization and reaches its 8-round tool budget.
- MCP setup was absent from onboarding.
- The user needs agent-driven screen/control inspection and testing without falsely equating server availability with app correctness.
- A bearer token was visible in a user-provided screenshot; tokens must rotate between server starts.

## Changes

- SharedAgentSession: ALLOW ALL · THIS TASK grants up to 24 subsequent tool dispatches against the registered tool set, with a ten-minute deadline. New turns and cancellation revoke. Decline still fails closed. Identical calls limited to 3 dispatches; 24 provider rounds, 48 tool calls maximum per task. No external Android permission is granted.
- AgentSessionPanel: separate full-width ALLOW ALL · THIS TASK button; ALLOW / DECLINE remain available for each call.
- Fr3kMcpServer: six additional diagnostic tools: list_hud_screens, get_hud_diagnostics, run_hud_selftest, inspect_hud_ui, open_hud_screen, press_hud_control. Also preview_workflow for exact local approval. Total advertised 22 tools.
- HudDiagnosticSurface: 23 internal manifest activities inventoried. 2 require a system trigger; 21 are direct-launch candidates. Read-only redacted clickable-node inspection never returns third-party trees or private text inputs.
- HUD screen navigation/control requires a ten-minute explicitly enabled local diagnostic session; bearer possession alone is insufficient. Sensitive controls and arbitrary shell/programmatic permissions remain excluded.
- User-controlled write approvals: render_plan or preview_workflow creates a pending, exact, short-lived review; the local Agent screen must approve ONCE. Caller-supplied approve=true is not sufficient. External-app exploration fails closed until a matching local review flow exists.
- MCP bearer rotates on each server start; stop revokes diagnostic authority and clears the pending review. Broadcast stop deletes stale private mcp.json.
- Onboarding now includes MCP START / authenticated self-test / reveal / ten-minute diagnostic authorization / revoke / diagnostics screen. Settings can reopen onboarding.
- App version 0.4.23 / code 423.

## Observed evidence

- Gradle: :core:testDebugUnitTest 83/83 passing; :app:testDebugUnitTest 71/71 passing; debug APK and androidTest assemble successful.
- Instrumentation: HudDiagnosticsMcpDeviceTest OK (1); McpServerDeviceTest OK (3), including exact operator review; ChatTranscriptRegressionDeviceTest OK (1).
- Onboarding UI was navigated on emulator through WELCOME → PERMISSIONS → CONNECTIONS → AI → MCP. Tapping START and VERIFY produced HTTP 200, 22 tool definitions.
- HudScreenSweepDeviceTest OK (1). Manifest: 23 registered; 21 direct launches accepted; 19 showed HUD-owned accessibility roots; Deck and Wireless Debug had UNKNOWN tree/package observation during the sweep; share receiver and radial overlay marked BLOCKED_NEEDS_TRIGGER. This is NOT proof of 19 fully functioning screens or every button.
- No live provider billing, hardware operations or arbitrary target apps were exercised. MCP user-phone authenticated validation remains UNKNOWN, even though the 8791 loopback listener previously returned HTTP 401 without a token.
- APK signer verification succeeded.
- SHA-256: 82bd88b6dcb9313edaf00e413d2bf60ab2931d186fbd540e4da53f36f9df02ac

## Limitations and further acceptance

- This is a bounded local diagnostic capability, NOT arbitrary full device control, code self-modification or automatic repair. A developer agent may use evidence to propose repairs, which still require a reviewed source build and real reinstallation.
- Android's package installer, system consent dialogs and third-party app data remain separately controlled.
- UI tree inspections cover accessible nodes only, not every rendered pixel or hidden state. Use screenshots/visual reviews and explicit end-to-end tests for each screen.
- The physical OnePlus still needs installation approval and live validation for 0.4.23. Keep current app data; do not uninstall to circumvent signing.
- GitHub contains source and bounded evidence, not secrets or raw private device telemetry.

Operating loop: OBSERVE → VERIFY → RECONCILE → IMPROVE → TEST → RECORD.

## Private handoff verified (not an installed-phone acceptance test)

- Private release asset FR3K-HUD-0.4.23-debug.apk is uploaded; GitHub asset size 105069104 bytes, SHA-256 82bd88b6dcb9313edaf00e413d2bf60ab2931d186fbd540e4da53f36f9df02ac.
- Authenticated Termux GH download on OnePlus succeeded. SHA-256 comparison PASS against Parrot and private GitHub.
- Copy to Android /storage/emulated/0/Download/FR3K-HUD-0.4.23-debug.apk independently passed SHA-256 comparison.
- Android package-installer VIEW intent accepted (termux-open exit code 0).
- Operator approval and signed update acceptance: UNKNOWN. No uninstall, application-data deletion, or hidden privilege escalation was attempted.
- The previous screenshot contained an old bearer token; the 0.4.23 code now rotates the bearer when the server is restarted. Do not publish the new bearer.

## Phone post-install confirmation (2026-10-19 Brisbane)

Source: authorized Desktop Commander terminal on the OnePlus 7 Pro; user-supplied screenshot with a bearer token was observed in chat, and is NOT copied to this repository.

- Android product: GM1913. Package: com.mcpintelligence.fr3k.hud.
- SHA-256 of the installed Android base APK: 82bd88b6dcb9313edaf00e413d2bf60ab2931d186fbd540e4da53f36f9df02ac.
- SHA-256 of the Downloads candidate: identical. VERIFIED FACT: installed APK bytes match 0.4.23.
- Phone localhost MCP: unauthenticated GET /tools -> HTTP/1.1 401, body contains error token required. The bearer gate rejects requests without a token.
- Operator-provided in-app screenshot: MCP RUNNING on 127.0.0.1:8791, AUTHENTICATED HTTP 200 and 22 tools after tapping Verify. This is an OBSERVATION of the on-device self-test, not an independently verified authenticated tool sweep.
- ADB devices from Termux: zero; run-as: permission denied; root su not available; Android settings query: permission denied. Live external Android UI instrumentation/privileged logs are BLOCKED.
- Individual phone MCP tool calls, UI outcomes across all screens, live AI backend, and all buttons: UNKNOWN on physical device. Emulator observations do not count as phone verification.
- Bearer value withheld from all source files, commands and logs. Token rotation recommended after screenshot disclosure.

Next acceptance: authorize an on-device private test harness or trusted ADB connection, execute 22-tool read-only and separately approved action cases and UI matrix, collect redacted outcomes. An HTTP 200 or activity inventory does not mean all features work.

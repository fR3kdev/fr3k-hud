# Screen specifications

All pages use midnight surfaces, legible explanatory text, scrolling system-bar insets and persistent help. States describe observed backend outcomes, not optimistic button presses. Guide identifiers and required three-step/limits/undo invariants are retained.

| Screen | Purpose and layout | States and copy | Stable semantics / guide |
|---|---|---|---|
| Command Deck | Brand + APP MODS; horizontal destinations; readiness panel; guides CTA; quick actions; floating workspace; cannot-do panel | Initial SETUP → observed READY; missing setup names exact step; service exceptions surface rather than claiming success | deck-guides, deck-tab-*, deck-hud-start; all catalogue guides |
| APP MODS | Title; BRIDGE/SHELL/ROOT state; recheck; search; horizontally scrolling filters; expandable guide cards; MCP and lab links | Filter counts match shown list; no results teaches clearing query; each card names missing requirements | mods-status, mods-recheck, mods-search, mods-filter-*, mods-guide-*, mods-steps-*; all 18 guides |
| Planes | Four independent glass panels; requirements, abilities, undo; lab link; interactive five-stage stack | Bridge/shell/root unavailable until observed; patch engine availability does not imply arbitrary recipe readiness | deck-tab-Planes; mod.inspect.app, mod.modify.own.app, mod.appops and CONNECT guides |
| Workflows | Qualified capability selectors; ordered draft; per-step package/version/signer/fingerprint; limits + inverse; review checkbox; Run/Clear | Empty target explanation; running/done/failed/refused status; clear removes only draft; stale target refuses | deck-tab-Workflows; mod.combine.apps, mod.autorun.taps; capability-specific guides |
| Agent/MCP | Local listener lifecycle; port; deliberate token reveal; ten actual tool descriptions; provider link | Stopped/running/start failure; token hidden initially; stop removes listener | deck-tab-Agent; CONNECT guides; mods-mcp-* remains available |
| Journal | Filter + refresh; operation cards with actual status, duration, identity, inverse | Empty teaches running a reviewed action; no automatic undo when inverse is none | deck-tab-Journal; operation IDs come from journal |
| Tools | Explained and requirement-labelled destination cards, all supporting screens | Hardware/provider requirements shown before opening | deck-tool-0 through deck-tool-12; FeatureGuide on destination |
| Settings/provider | HUD preferences, consent, provider key state, tool-capable models, test, fallback, telemetry/context | Masked/stored/missing key, actual health results, network failure | Existing provider semantics preserved; Settings guide |
| Diagnostics | Full diagnostic; permission grants; metadata; export | PASS/WARN/FAIL with detail; grants use actual Android consent | Diagnostics guide |
| App control | Package input; inspect; bootstrap; owned patch prepare; floating install review; real result output | Preparation progress/errors; install warnings name exact owned target; unknown package refused | Existing inspection/lab controls; mod.inspect.app and mod.modify.own.app |
| Integrations | Actual optional service/companion state; bootstrap/instruction actions | Missing/denied/tier-specific explanations; instruction popup moves/resizes | FloatingPanel; CONNECT guides |
| Automations | Starter trigger/action rules and logs | Fired/skipped/failed counts; inspect-only rule list explained | Automations guide; mod.autorun.taps |
| Ask | Context firewall, prompt, selected provider, response | Empty prompt, busy, response, provider unavailable | Ask guide; sending context has no recall |
| Clipboard | One explicit clipboard read; suggested commands; result | Empty clipboard, classified text, result or failure | Clipboard guide |
| Screenshot | Capture button; system consent; real PNG preview; Save/Share/Delete; unsupported analysis explanation | No capture / cancelled / capturing / saved / failure; image analysis explicitly unavailable | Actual captured screen image description; Screenshot guide |
| Handoff | Context; discovered peers; target/payload review; send | No peers teaches dependency; transport result shown | Handoff guide |
| BLACKWAVE | Companion configuration; device list; health | Unpaired/disconnected/observed health; hardware not emulated | BLACKWAVE guide |
| Developer context | Foreground metadata; copy actions | Missing usage permission explains unknown; no fabricated package | Developer guide |
| Setup | Contextual help; actual permission requests; provider route; skip/complete | Missing permission remains missing after skip; revisit from Tools | Setup guide |
| Chat overlay | Provider/model transcript, shared session, prompt, send, model/TTS, close; title move + corner resize | Provider actual labels, busy state and tool output; no outside-input blockade | Existing transcript/control labels + Move/Resize floating window |
| Mini browser | Address/navigation; real WebView; shared chat; close/geometry | Loading/title/error; scheme and selector refusals maintained | Existing browser control labels + geometry labels |
| Terminal | Real bridge availability/output, input/run, maximise/minimise/restore, close/geometry | Missing Termux explains setup; stdout/stderr/exit are receipts | Existing terminal controls + geometry labels |
| Radial tools | Bounded window: title; scrollable action list; resize footer | Each action opens existing surface; cancel closes only this menu | Move/Resize floating window; no fullscreen touch scrim |
| Review/help popup | Bounded opaque body with header drag, close and resize; content scrolls | App remains interactive outside window; installer requires explicit action | Close floating panel; accessibility geometry actions |

```
┌ fR3k                               APP MODS ↗ ┐
│ Deck  Planes  Workflows  Agent  Journal  Tools │
│ Heading                                      │
│ Explanation                                  │
│ ┌ section · observed state ─────────────────┐ │
│ │ requirement / exact target / evidence     │ │
│ │ [reviewed action]                         │ │
│ └──────────────────────────────────────────┘ │
│ scrollable content clears system navigation  │
└──────────────────────────────────────────────┘
```

Verification limitations must remain explicit in BEFORE-AFTER.md and evidence reports. Static node inspection is not a substitute for TalkBack auditory testing, and local provider fixtures are not proof of subscription availability.

## Release scope update

Current normal UI uses installed-app selection, identity-bound controls/maps, compatible module import, wireless shell and nine production procedures. Fixture walkthroughs are debug instrumentation-only. Obsolete external privilege/patch-manager setup has been removed from Connections. Internal patch-engine attribution remains necessary. Earlier fixture-specific examples in this document describe lab verification, not default release navigation. See CONTINUATION.md for actual completed/pending acceptance and ROUTING-MATRIX.md for current support.

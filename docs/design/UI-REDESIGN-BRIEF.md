# fR3k HUD — UI research brief and full redesign prompt

**Audience:** a research + design agent with no prior context on this codebase.
**Deliverable:** a researched, then fully redesigned, fR3k HUD interface — modern, dark, glass-morphic,
purple/blue, where every capability explains itself as the user explores and every feature has an
integrated walkthrough.
**Repository:** `/home/parrot/repos/fr3k-hud` (Android, Kotlin, Jetpack Compose + classic Views, AGP 8.7.3,
JDK 17, compileSdk 35).
**Baseline to beat:** the current build, `FR3K-HUD-0.4.20`, whose behaviour is verified by a device lab
(`scripts/device/run_capability_lab.py`, 19 bridge checks + 8 instrumented classes, `CAPABILITY_RESULT PASS`).

---

## 1. What fR3k HUD is (do not get this wrong)

fR3k HUD is a **user-empowerment Android control layer**. It is not a pentest suite, not a launcher, not a
chat toy. It gives one person, on their own device, the ability to **inspect, modify, automate and combine
the apps they use**, without root for the ordinary planes and with extra powers unlocked when root exists.

It does this through four **planes**, and every feature belongs to exactly one:

| Plane | What it needs | What it can do |
|---|---|---|
| **BRIDGE** | HUD's own accessibility service (no root, no companion app) | read UI trees, fingerprint-confident taps, text entry, state assertions, screen masks, app maps, fused multi-app workflows |
| **PATCH** | the embedded patch engine (no root, no companion app) | prepare, sign (per-install non-exportable AndroidKeyStore key), verify and publish a modified build of an app you own, with an undo archive |
| **PRIVILEGED (shell tier)** | a one-time ADB bootstrap of HUD's own AIDL service | typed app-op read/set/restore, package metadata, private directory names |
| **PRIVILEGED (root tier)** | the same service started from a root shell | the root-gated operations, reported as actually granted rather than assumed |

Plus supporting surfaces: the HUD overlay family (orb, chat bubble, mini browser, terminal, radial menu),
an on-device **MCP server** (localhost + bearer token, 10 tools) so a user's own agent can drive it, and an
**APP MODS** guide catalogue with **18 guides**.

**Non-negotiable truthfulness.** The product's credibility rests on stating limits in the UI, not hiding
them. "Skip ads that offer a skip control" is true; "remove ads" is not. "Change an app you own" is true;
"modify any app instantly" is not. Any redesign that makes the app *look* more capable than the routing
matrix says is a failed design, however pretty. `docs/app-control/ROUTING-MATRIX.md` is the contract; read
it before designing a single screen.

## 2. Current inventory (design against reality)

- **Screens today:** `ui/MainActivity.kt` (Compose dashboard: header, primary actions, suggestions,
  context, identity, orb panel, MORE SURFACES rows, capabilities, commands, fleet, footer),
  `ui/integrations/IntegrationsActivity.kt` (tier panel), `ui/appcontrol/AppControlActivity.kt` (classic
  Views lab), `ui/mods/ModsActivity.kt` (APP MODS + guides), plus settings, diagnostics, automation,
  blackwave, handoff, dev overlay, clipboard, screenshot, palette, ask-about-this, onboarding, quickhud.
- **Overlays:** orb, chat bubble, mini browser, terminal, radial menu, edge arc.
- **Design tokens today:** `ui/.../Fr3kTheme.kt` — `Fr3kPalette { Bg 0xFF05060A, Surface 0xFF0E1018,
  Border 0xFF1F2A3D, Text 0xFFE6ECF5, TextDim 0xFF8A93A6, Accent 0xFFB829FF, AccentDim 0xFF5A1F8C,
  Ok 0xFF28E0B0, Warn 0xFFFFAA33, Err 0xFFFF5577, Magenta 0xFFFF3FA4 }`, monospace typography, dark-only.
- **Guide model:** `capability/.../ModGuide.kt` (`ModGuide`, `ModCategory`, `ModRequirement`,
  `ModEnvironment`, `ModReadiness`, `ModGuideStatus`) and `app/.../appcontrol/ModGuideCatalogue.kt`
  (10 procedures + one card per registered capability, `toJson` for agents).
- **Guide integrity is enforced by test:** `ModGuideCoverageTest` fails the build if a capability has no
  guide, if a guide references a capability that does not exist, if a guide has fewer than three steps, or
  if a guide omits its limits or undo line. Your redesign must keep that test passing.
- **Verification harness (must keep passing):** `:capability:test`, `:core:testDebugUnitTest`,
  `:app:testDebugUnitTest`, `:patch-fixture:testDebugUnitTest` (198 JVM tests) and
  `scripts/device/run_capability_lab.py` (four phases: MCP bridge plane → patch plane → privileged shell →
  privileged root) plus `scripts/device/verify_bridge_via_mcp.py` (19 checks), which asserts on **on-screen
  evidence** including `content-desc="mods-guide-*"` nodes, guide expansion, and a scrolling list.
- **AI provider:** OpenCode Go (`https://opencode.ai/zen/go/v1`, bearer key + `x-opencode-session` header)
  replaces OpenRouter app-wide when its key is stored. See `docs/app-control/opencode-go-integration.md`.

## 3. Phase 1 — research (do this before drawing anything)

Produce a written pattern study, with sources, screenshots or reproductions, and a per-pattern verdict of
**adopt / adapt / reject** plus the reason.

### 3.1 Primary reference: StrykerOSS — `zalexdev/strykerapp` (GPLv3, ~2 GB, v6.5)

It is a mobile pentest suite with the layout quality you asked to match. Study specifically:

1. **Module grid with per-module capability badges.** Sixteen modules grouped into Wireless, Network, Web,
   Bluetooth, USB/HID and System, with a filter row and a live count ("16 of 16"). Every module card
   carries badges such as *root required*, *adapter required*, *gadget kernel not needed*. This is the
   single strongest idea to steal: **fR3k HUD has the same problem — capability depends on what the device
   can currently do** (bridge bound, shell tier, root tier) — and it should read the same way at a glance.
2. **Dashboard as a state panel, not a menu.** Engine state, mount status, attached adapters and quick
   actions, all live. fR3k's equivalent state is: accessibility service bound, HUD service running, shell
   tier / root tier, MCP surface running, active AI provider, capability count, last journal entry.
3. **A staged mental model.** Its site explains Stage 01 App UI → Stage 02 Engine chroot → Stage 03 rootfs
   → Stage 04 tool process. fR3k needs the same clarity for its stack: **App UI → Plan → Capability →
   Plane (Bridge / Patch / Privileged) → Target app**, so a user understands *where* an action happens.
4. **Single-activity host with drawer navigation** (`MainActivity.java` hosts modules) rather than dozens of
   separate activities, and **first-run installer slides** (`AppIntroActivity`) that set expectations
   before the first action.
5. **Embedded console in context.** Its terminal drops into the chroot from the drawer; terminal output
   lives where the work happens rather than in a separate app.

### 3.2 Also research (find more; the list is a starting point, not a boundary)

- Other feature-dense Android "operator" apps with strong IA: Termux, Tasker, MacroDroid, Automate, Neo
  Launcher/Neo Store style module browsers, SAI (Split APKs Installer), F-Droid/Obtainium style catalogues,
  LADB, Shizuku manager, MiXplorer, Fedilab/Tusky for timeline-density, and any "dashboard + module grid"
  Android app you find with unusually good structure.
- **Progressive disclosure and explain-as-you-go patterns:** onboarding tours that teach inside the real UI
  (not a slideshow then abandonment), inline capability badges, empty states that teach, contextual help
  drawers, "what will happen" pre-flight sheets with an explicit undo. Look at how Stripe/Vercel/Linear
  surface risk and reversibility, and how Android's own permission UIs explain consequence before consent.
- **Glass morphism done well on Android:** real-world performance constraints (blur is expensive; use
  layered translucency + subtle gradients + 1px hairline borders + inner highlight rather than heavy
  realtime blur on low-end devices), contrast requirements for accessibility, and how to keep glass legible
  over arbitrary content. Check Material 3 Expressive and Android 15/16 surface conventions, plus
  `Modifier.graphicsLayer`, `RenderEffect` availability/API level, and battery impact.
- **Dark palettes in purple/blue** with proven contrast: study accessible dark-mode systems, colour-blind
  safe accent pairs, and how to encode *state* (ready / needs setup / blocked / failed) with colour that
  still reads for a deuteranopic user.
- **Motion as explanation:** short, purposeful transitions that show where a thing came from (a plan
  expanding from its trigger; a workflow stepping across two apps; a mask sliding over the blocked region).
  Include reduced-motion behaviour.
- **Agent-facing mirrors:** how other apps expose the same feature set to a local agent (MCP) while keeping
  the UI as the source of truth, so the human sees what the agent is doing.

### 3.3 Research output format

A single markdown report with: (a) a pattern table (pattern, source, screenshot/description, verdict,
reason); (b) an information-architecture comparison of 3–5 apps; (c) a prioritised list of the **12
changes with the largest effect on comprehension** (not on decoration); (d) explicit anti-patterns to avoid
with reasons; (e) a written accessibility and performance budget.

## 4. Phase 2 — redesign from scratch

Redesign the entire app. New navigation model, new home, new everything — but the capability surface,
verification harness and truthfulness rules must survive intact.

### 4.1 Design direction

- **Dark, glass, purple/blue.** Keep the existing spirit (`Bg 0xFF05060A`, accent `0xFFB829FF`, magenta
  `0xFFFF3FA4`) but evolve it: deep blue-violet background, glass panels (`surface @ 60–75% alpha`, hairline
  border, inner top highlight, soft outer shadow), accent gradients for primary actions, and a disciplined
  state palette (ready / needs-setup / blocked / failed / running).
- **Legibility over effect.** Body text must pass WCAG AA on every glass surface, in every state, over any
  backdrop. Test at 200% font scale and with TalkBack.
- **Monospace is the brand voice for technical data; use a display face only for headings/branding.**
- **Density with breathing room:** this is an operator tool; show real state on the first screen, not a
  welcome message.

### 4.2 Required information architecture

Design and specify, screen by screen:

1. **Home / Command Deck** — live state tiles (bridge, service, shell/root tier, MCP, AI provider), the
   four planes as the primary organising metaphor, recent activity from the journal, and the one-tap
   actions a user actually repeats.
2. **Capabilities / APP MODS** — the module grid: every capability and every procedure as a card, grouped
   by category (INSPECT / MODIFY / AUTOMATE / COMBINE / CONNECT), with **capability badges** showing
   requirements and live readiness, filters, search, and a count that matches the catalogue. Tapping opens
   the full walkthrough: what it does, what it runs on, numbered steps, the phrase that runs it, what
   actually executes, **limits**, **undo**.
3. **Planes** — one surface per plane (Bridge, Patch, Privileged shell, Privileged root) explaining what it
   is, what it needs, what it unlocks, and what is verified — with the plane's own live status and the
   routes into its guides.
4. **A staged stack view** — the App UI → Plan → Capability → Plane → Target model from §3.1.3, made visual
   and interactive so the user sees exactly what will be touched before they touch it.
5. **Workflows / Combinator** — build a fused multi-app flow: pick capabilities, see each step's own target
   identity, see the inverse that undoes it, run it, and watch it advance step by step.
6. **Agent / MCP** — start/stop the localhost surface, show the port and token, list the 10 tools with the
   same plain-language explanations, and show what the agent has done recently.
7. **Connect / Provider** — OpenCode Go setup with key state, endpoint, model list (only the models that can
   carry tool calls), a test button, and a plain explanation of what changes when a key is stored.
8. **Journal / Evidence** — every recorded operation with its real inverse, filterable, so trust is earned
   by showing receipts.
9. **Settings, Diagnostics, About** — including a "what this build cannot do" section that is as prominent
   as what it can.
10. **Overlays** (orb, chat bubble, mini browser, terminal, radial menu) restyled to match, with the chat
    bubble naming the active provider and model, and every overlay action traceable to a capability.

### 4.3 Explain-as-you-explore, mandatory

- Every feature entry point carries a one-line plain-English explanation of what it does **and a badge for
  what it requires**, before any tap.
- Every action sheet shows what will happen, to which exact target (package + version + signer), what it
  costs, and how to undo it — *before* it runs.
- First-run teaches inside the real UI: point at the accessibility switch, the ADB bootstrap, the provider
  key, and the owned fixture, and let the user complete each step in place.
- A persistent "why is this here?" affordance on every panel that surfaces the relevant guide.
- When something is unavailable, say exactly why and the exact step that would make it available.

### 4.4 Constraints the redesign must respect

- **Do not weaken `ModGuide`**: keep at least 3 steps per guide, keep the limits and undo lines, and keep
  `ModGuideCoverageTest` green (it will fail the build otherwise).
- **Keep the capability API honest:** no screen may imply third-party live hooking, ad removal, premium
  unlocking, DRM circumvention, or signature forgery. `docs/app-control/ROUTING-MATRIX.md` and the security
  doctrine in `CLAUDE.md` are binding.
- **Keep the device lab green:** `scripts/device/run_capability_lab.py` must still pass, and
  `scripts/device/verify_bridge_via_mcp.py` asserts on `content-desc` nodes — provide stable test tags for
  the redesigned guide cards, status chips, filters, MCP panel and chat transcript.
- **Android conventions from this repo:** apply window insets to the *scrolling root* (`clipToPadding =
  false`) so the last control clears the navigation gesture handle; `Log` tags ≤ 7 characters; HUD service
  must use `startForegroundService`; activities that are not exported are reached through the dashboard's
  intents (`--ez open_mods true` works, and it must work from `onNewIntent` too).
- **Sizes/performance:** the app ships on mid-range devices; cap blur work, avoid per-frame allocations,
  and keep cold start and scroll smooth. State your performance budget and how you measured it.
- **Accessibility:** TalkBack labels for every control, 48dp minimum touch targets, focus order, contrast
  AA, reduced-motion support, no colour-only state encoding.

### 4.5 Deliverables

1. `docs/design/RESEARCH.md` — Phase 1 report (§3.3 format) with sources.
2. `docs/design/DESIGN-SYSTEM.md` — tokens (colour ramps incl. glass layers, elevation/hairline, spacing
   scale, radius scale, type ramp with sizes/weights/line-heights, iconography, motion durations/easings,
   state colours with contrast ratios), plus do/don't examples.
3. `docs/design/IA-AND-FLOWS.md` — full information architecture, navigation map, and the key user journeys
   (first run; skip-an-ad; change an app you own; automate a chore; fuse two apps; grant a shell tier;
   unlock root; point at OpenCode Go; hand it to your agent) as annotated flows.
4. `docs/design/SCREEN-SPECS.md` — every screen: purpose, layout (wireframe or ASCII), states (empty,
   loading, ready, needs-setup, blocked, failed, done), copy, test tags, and the exact guide(s) it surfaces.
5. `docs/design/MOTION.md` — motion spec incl. reduced-motion.
6. **Implemented Compose code** for the redesign, keeping the verification suite green, with the exact
   commands and their observed output as evidence.
7. `docs/design/BEFORE-AFTER.md` — side-by-side screenshots, plus the measured effect (task time,
   taps-to-first-action, comprehension probes).

### 4.6 Build and verify loop (use exactly this)

    cd /home/parrot/repos/fr3k-hud
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ./gradlew \
      :capability:test :core:testDebugUnitTest :app:testDebugUnitTest :patch-fixture:testDebugUnitTest \
      :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    python3 scripts/device/run_capability_lab.py --serial <explicit-emulator-serial> --repo . \
      --log /home/parrot/.hermes/cache/scratch/cap-lab-summary.json

Expected: `BUILD SUCCESSFUL`, 198 JVM tests with zero failures, then `CAPABILITY_RESULT PASS` with
`companion packages present: NONE`. An adb exit status alone proves nothing: require the instrumentation
text (`OK (n tests)`, no `FAILURES`) and the JSON evidence file.

### 4.7 Acceptance criteria for the redesign

1. A newcomer can, without documentation, answer "what can this do?", "what does it need?", "is it ready on
   my device?" and "how do I undo it?" within 30 seconds of opening the app.
2. Every one of the 18 guides is reachable in at most two taps from home, and every capability the app can
   execute is reachable in at most three, with its own explanation visible before it runs.
3. No screen claims a capability the routing matrix does not support; the "cannot do" statements are visible
   without hunting.
4. All JVM tests and the full device lab pass unchanged, including the guide-catalogue assertions and the
   on-screen `content-desc` checks.
5. Contrast AA verified on every glass surface; TalkBack traversal of home, guide card and one full
   walkthrough completed without an unlabelled control; reduced-motion honoured.
6. Frame timings during scroll and guide expansion measured and reported on the emulator, with no dropped
   frame storms.
7. The result looks like one product: consistent glass, consistent state language, consistent motion, in
   dark purple/blue — and it still tells the truth.

## 5. Anti-patterns (explicitly rejected)

- Marketing gloss over the limits: implying "modify any app" or "remove ads" anywhere in the UI.
- Hiding setup requirements behind a generic "unavailable" state instead of naming the exact step.
- Decorative blur that costs frames on mid-range hardware, or glass so transparent that body text fails
  contrast.
- Onboarding that teaches nothing and is never seen again; help that lives only in a separate document.
- A guide without a walkthrough, limits or undo — and any capability not covered by a guide.
- Replacing the verified capability surface with something prettier that the lab can no longer verify.

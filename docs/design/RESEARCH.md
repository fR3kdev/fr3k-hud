# Pattern study — 14 October 2026

Research precedes implementation. Sources were opened on this date; descriptions below are pattern reproductions, not claims of hands-on testing of the reference apps. No upstream UI code is copied. Stryker's README currently says 6.0: the brief's 6.5 is not treated as verified. Its JavaScript-only site did not expose the staged graphic to the research reader.

| Pattern | Primary source | Description / reproduction | Verdict | Reason |
|---|---|---|---|---|
| Live operator dashboard | [Stryker source](https://github.com/zalexdev/strykerapp) | `Engine status / hardware / quick actions` | Adapt | HUD shows bridge binding, owned service, shell/root, MCP and provider rather than radio tools. |
| Module requirements beside module | [Stryker source](https://github.com/zalexdev/strykerapp) | `Module → required hardware → action` | Adapt | Use capability requirements and observed readiness; never imply root by installation alone. |
| In-context console | [Stryker source](https://github.com/zalexdev/strykerapp) | Drawer terminal uses the same engine | Adapt | Floating terminal keeps shared agent receipts visible; Termux dependency must be stated. |
| Explicit startup tiers | [Shizuku manual](https://shizuku.rikka.app/guide/setup/) | Root and ADB startup are separate instructions | Adopt | Shell and root require independently observed grants; explain restart limitations. HUD uses its own service. |
| Context → task → action | [Tasker guide](https://tasker.joaoapps.com/userguide/en/) | Profiles, tasks and scenes form distinct navigation domains | Adapt | Separate triggers from reviewable app-bound steps. Avoid presenting unrelated device commands as app capabilities. |
| Flow with inspectable blocks | [Automate documentation](https://llamalab.com/automate/doc/index.html) | Flows, building blocks, examples and in-app help | Adopt | Every workflow step exposes its target and inverse. |
| Source-aware catalogue | [Obtainium](https://github.com/ImranR98/Obtainium) | App list associates updates with their source | Adapt | Associate each capability with its bound package, version, signer and APK identity. |
| 48dp interaction regions | [Android Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) | Small glyph inside a larger hit region | Adopt | Close, filter and resize controls need comfortable hit areas and labels. |
| Scalable text and contrast | [Android accessibility](https://developer.android.com/guide/topics/ui/accessibility/apps) | sp text, text contrast, descriptive controls | Adopt | Use words with state colours; reserve mono for technical data. |
| Layered glass | [Compose graphics](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) | Gradient, clipped layer, border and shadow | Adapt | Simulate depth with opaque legible floors rather than continuously blurring other apps. |
| Purposeful motion | [Material motion](https://m3.material.io/styles/motion/overview/how-it-works) | State changes maintain spatial continuity | Adapt | Prefer immediate expansion for operational evidence and reduced-motion compatibility. |
| Non-modal floating windows | [Android WindowManager](https://developer.android.com/reference/android/view/WindowManager.LayoutParams) | Bounded window with NOT_TOUCH_MODAL, independent geometry | Adopt | Outside taps must reach the underlying app; full-screen transparent roots still intercept them. |
| Human/agent feature parity | Existing HUD MCP `toolList` and guide catalogue | Same registry and journal → human cards / ten MCP tools | Adopt | Derive lists from the actual backend instead of a second marketing inventory. |
| Single giant menu | Existing HUD dashboard | Every panel stacks ahead of the useful task | Reject | Navigation cost grows with unrelated inventory; give persistent task destinations. |

## Information architecture comparison

| App | Primary organisation | Setup model | Evidence/help | HUD consequence |
|---|---|---|---|---|
| Stryker | Dashboard plus modules | Engine and hardware | In-context terminal | State first; modules carry requirements. |
| Shizuku | Service lifecycle | ADB versus root | Startup instructions | Explicitly name shell and root planes. |
| Tasker | Profiles, tasks, scenes | Context/action permissions | Indexed guide and run log | Separate automation triggers from capability plans. |
| Automate | Flow and blocks | Per-block prerequisites | In-app documentation | Ordered steps with inspectable targets. |
| Obtainium | Source-bound app catalogue | Source configuration | Per-app source context | Searchable catalogue with identity and readiness. |

## Twelve highest-impact comprehension changes, in order

1. Replace the unqualified “online” badge with observed bridge/service/tier states.
2. Place all guides one tap from home and count the actual filtered results.
3. Keep requirements, limits and undo visible before execution.
4. Split the four planes into individually explained cards.
5. Show App UI → Plan → Capability → Plane → Target as selectable explanations.
6. Put source package/version/signer into plan review.
7. Present recorded inverses beside journal operations.
8. Explain missing setup with the specific permission/service step.
9. Derive the MCP tool list from the server and label its local-only boundary.
10. Explain each supporting surface before opening it, including dependency and recovery.
11. Provide bounded, movable, resizable windows and outside-tap pass-through.
12. Increase technical text size and use flexible wrapping at large font scale.

## Anti-patterns

Never equate “installed” with “granted”, or “tap a skip control” with ad removal. Never market the owned fixture as a generic third-party recipe. Never hide irreversible installation data loss in decorative copy. Never give a floating panel a full-screen touch root. Never use dark purple body text, colour alone for state, or realtime backdrop blur as the only source of legibility. Never claim emulator success verifies missing hardware or paid endpoints.

## Accessibility and performance budget

Text contrast ≥4.5:1 (large text ≥3:1), active controls ≥48dp, sp typography, wrapping instead of fixed text heights, persistent textual state. Verify at font_scale=2 and inspect accessibility nodes; distinguish node inspection from actual TalkBack speech/traversal. Glass uses a dark opaque floor with static gradients and a 1dp border; no RenderEffect or external backdrop sampling. No perpetual animation in the deck. Target 60Hz scroll with p95 frame time ≤32ms and no sustained >100ms storm; measure `dumpsys gfxinfo` on the explicitly named emulator, not as physical-device certification. Cold-start target <2s, reported via `am start -W`. Provider and registry IO should run off the UI thread. Test reports record failures and unavailable dependencies separately.

## Wireless debugging and the release catalogue (2026-10-14 scope update)

Adopt on-device pairing, adapting [Android's documented Wireless debugging flow](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi) to a persistent movable HUD panel. Pairing and connection ports are separate. A successful pair is not a successful shell bootstrap: verify the HUD Binder UID and tier. [LibADB Android](https://github.com/MuntashirAkon/libadb-android) supplies pairing/TLS and recommends custom Conscrypt to avoid hidden-API bypass. The implementation pins libadb3.1.1 and Conscrypt2.5.3. Keep the private identity encrypted, omit PINs from evidence, and disconnect ADB after bootstrap.

Latest product direction removes obsolete external privilege/patch-manager setup and demo-first catalogue entries. Normal APP MODS shows real installed-app selection, screen mapping, module import, workflows, wireless shell, root, provider and MCP journeys. Disposable fixture capabilities belong only to an explicitly enabled instrumentation lab. Android permission prompts, exact target identities and verified shell/root tiers remain actual prerequisites. This is an implementation direction, not a claim of completed end-to-end acceptance; see CONTINUATION.md and verification evidence.

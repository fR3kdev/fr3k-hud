# What fR3k HUD can change, and how

This is the contract between the product and the person using it. If a behaviour is not on this
page, it is not supported, and no claim about it should be made.

| What you ask for | Plane | Reinstall the target? | Root? | Status in 0.4.19 |
|---|---|---|---|---|
| Inspect an installed app's version, signer and APK hashes | read-only inspection | No | No | Verified on device |
| Patch an app you installed with its own bundled patch payload | PATCH | Yes, reviewed installer | No | Verified end-to-end on the owned second target (2 device tests) |
| Undo a patch by reinstalling the archived original | PATCH | Yes, reviewed installer | No | Archive + verification verified; reinstall is operator-reviewed |
| Skip an ad that exposes a skip control | BRIDGE | No | No | Verified: the click is fingerprint-confident and the screen changes |
| Cosmetically mask an on-screen control | BRIDGE | No | No | Verified: masked region measured 83.8 luma -> 0.0 |
| Fuse two or more apps into one button-press flow | BRIDGE + workflows | No | No | Verified: 2 capabilities across 2 packages in one run |
| Record what an app looks like and turn its screens into callable commands | BRIDGE + app maps | No | No | Verified: 3 screens -> 3 commands for the owned fixture |
| Change a per-app app-op (WAKE_LOCK, RUN_IN_BACKGROUND, POST_NOTIFICATION, ...) | PRIVILEGED | No | No | Verified: read/set/read-back/restore at the shell tier |
| List an app's private directory names (never file contents) | PRIVILEGED | No | **Yes** | Shell tier refuses with a tier message; root tier grants it. Both verified on an emulator |
| Disable or enable one component of one package | PRIVILEGED | No | **Yes** | Implemented, root-gated |
| Change a plain-English goal into a plan you review before it runs | intent compiler | No | No | Verified: unknown goals are refused with the closest matches |
| Drive any of the above from the user's own agent stack (MCP) | MCP | No | No | Verified: localhost only, token required, 10 closed-schema tools |
| Read the walkthrough for any capability, as a person or as an agent | APP MODS / MCP `get_guides` | No | No | Verified: 18 guides, every registered capability covered, 0 uncovered |
| See which capabilities this device can actually run right now | APP MODS status panel | No | No | Verified: BRIDGE / SHELL / ROOT chips reflect the live service tiers |
| Point the app at an OpenCode Go subscription, replacing OpenRouter everywhere | AI provider | No | No | Verified live: endpoint, bearer and `x-opencode-session` header, real tool-calling round trip |
| Remove ads from the YouTube app | PATCH | Yes | No | **NOT IMPLEMENTED.** Needs a per-version patch set and a device qualification run |
| Unlock premium features, bypass DRM, spoof Play Integrity, forge a signature | — | — | — | **Refused permanently.** The registry cannot even construct such a capability |

## Non-negotiables baked into the code

1. No capability is registered unless it is bound to an exact `package + versionCode + signer + APK hashes`.
2. A plan or workflow is refused, naming both expected and observed identities, when the build has drifted.
3. The step grammar is closed: launch, wait_for, click, set_text, assert_present, assert_absent, mask,
   unmask, observe, broadcast, privileged. There is no shell, script, exec or path step.
4. A selector is refused when it is ambiguous, and password fields are never evidence.
5. System surfaces (SystemUI, Settings, Play Services, the installer, HUD itself) are never automated.
6. Destructive controls (buy, pay, subscribe, send, delete, uninstall, call) are refused unless the step
   was explicitly approved for that run.
7. Every mutation writes an evidence record with a real inverse, and a recorded operation that cannot be
   reversed is never presented as undoable.
8. Nothing leaves the device: no APK upload, no patched-APK redistribution, no screen content egress.

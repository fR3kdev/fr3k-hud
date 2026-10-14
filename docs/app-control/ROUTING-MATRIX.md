# What fR3k HUD can change, and how

This is the contract between the product and the person using it. If a behaviour is not on this
page, it is not supported, and no claim about it should be made.

| What you ask for | Plane | Reinstall? | Root? | Current implementation / evidence |
|---|---|---|---|---|
| Inspect an installed app's version, signer and APK hashes | inspection | No | No | Any selected installed package; inspection device tests PASS |
| Launch and change app-op modes for a selected installed app | BRIDGE / PRIVILEGED | No | No |41actions per selected app (launch +8operations ×5read/write modes); real Termux read/write/restore and guides PASS |
| Start HUD shell without a computer | Wireless debugging | No | No | Real Android pairing, bootstrap, UID2000, shutdown/reconnect PASS; floating panel content actions need final UI acceptance |
| Map accessible app home controls into fingerprinted actions | BRIDGE | No | No | Implemented for selected installed packages; generic third-party map UI execution still pending verification |
| Patch an installed single-APK target with an imported compatible module | PATCH | Yes, reviewed installer | No | SAF import/stage/sign/verify/archive/review implemented; embedded engine device tests PASS; new generic import UI needs acceptance |
| Restore the archived original APK | PATCH | Yes, reviewed installer | No | Hash verification + reviewed installer implemented; changing signing identity may require uninstall and lose data; no automatic uninstall |
| Combine selected capabilities into reviewed multi-app workflows | BRIDGE / PRIVILEGED | No | Depends on action | Exact identity binding, progress and journal; fused bridge lab PASS |
| Record a genuine app-op inverse | PRIVILEGED | No | No | Actual previous mode recorded/restored; unset override is default, unknown prior mode refuses mutation; regression tests PASS |
| List private directory names or toggle components | PRIVILEGED | No | Yes | Root-gated service APIs; shell refusal/root grants tested; complete release UI exposure remains outstanding |
| Convert a goal into a reviewable registered plan | intent compiler | No | No | Unknown/ambiguous goals refused; MCP lab PASS |
| Drive registered actions from a user's agent | MCP | No | Depends on action | Localhost + bearer token,10closed-schema tools;19MCP checks PASS |
| Read every registered action's guide | APP MODS / MCP | No | No |9production procedure guides + one guide per selected action; explicit fixture lab has18guides |
| Send questions/text through chosen provider | AI | No | No | Provider preference/routing and consent implemented; live text/UI sweep in progress |
| Replace pixels with masks or press a fingerprinted skip control | BRIDGE | No | No | Real masks/skip controls verified against disposable lab targets; no universal ad-removal claim |

Normal production surfaces have no fixture LAB buttons or external Shizuku/LSPatch-manager/Morphe/Vector setup. The internal embedded patch engine remains an implementation dependency, not an external manager requirement. Disposable fixtures are enabled only by explicit debug instrumentation and disabled after the capability lab. Original per-version recipe support is not implied by arbitrary module import. Android permissions, actual shell/root UID, signer checks, system installer consent and credential-field protection remain platform or identity boundaries.

**Current candidate is IN PROGRESS**, not full release acceptance.208JVM tests, real wireless/selected-app2tests and full capability lab PASS. New release UI/generic mapping/module-import/accessibility/performance acceptance and hardware-dependent cases remain open; see [continuation checkpoint](../design/CONTINUATION.md). Physical-phone readiness cannot be inferred from emulator results.

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

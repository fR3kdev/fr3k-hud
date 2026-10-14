# Frontier reference (research sweep, 2026-10-14)

What exists in the world, what we took from it, and what we deliberately did not. Keep pins when
anything is vendored.

## Patching
- `ReVanced/revanced-patcher` (GPL-3.0) - embeddable bytecode/resource patching library.
- `MorpheApp/morphe-manager` (GPL-3.0) - on-device, no-root YouTube/YT Music/Reddit patching. Proof the
  mechanic works inside a normal Android app, and it supports a data-preserving module install path.
- `MorpheApp/morphe-patches`, `Jman-Github/Patch-Bundle-Registry`, `Jman-Github/Universal-ReVanced-Manager`.
- `LSPosed/LSPatch` - archived upstream; the embedded engine in this repo descends from a maintained fork.

## Runtime hooks
- `libxposed/api` (`io.github.libxposed:api:102.0.0`, Apache-2.0) - the modern, type-safe replacement for
  the legacy XposedBridge API. This is where the live module ecosystem already is.

## Deterministic automation and agents
- arXiv 2609.35671 PhoneCLI - compiles an app's navigation into callable commands offline, no
  instrumentation, no training. The shape of our app-map -> command compiler.
- arXiv 2609.34139 AnyAppBench - cross-app generalisation fails badly; argues for per-app, version-bound
  capability records instead of one general model.
- arXiv 2609.30887 - app-native deeplinks beat blind tapping for cross-app transitions.
- arXiv 2610.11707 DeltaReplay - task-relative memory reuse, the repair strategy when an app updates.
- arXiv 2604.17817 - text-only accessibility perception performs comparably to screenshots at a fraction
  of the cost: tree first, pixels only when needed.
- arXiv 2608.24898 - MCP as the transport/schema layer for accessibility trees.
- `JackRushante/argus` (29 stars) - "the LLM compiles, a deterministic engine executes". Our split too.

## Near-zero-star material worth reading
- `wavexzore/wave-toolbox` (0 stars) - feature-module plugin boundary; zero INTERNET permission.
- `muhib-karim/Rambler-Bangla` (0 stars) - "fail-closed, fingerprint-guarded" patcher. We adopted the
  discipline and the naming.
- The 0-2 star accessibility ad-skipper family (`anar-bastanov/ad-skipper-android` and five siblings) -
  the primitive is proven, the version binding and verification are what is missing. That gap is the point.
- `yutakobayashidev/droidperm`, `xykal/AppPerms`, `1zumiii/AppOpsNext` - AppOps as declarative policy.
- `yuuki324/HideAccessibility` - a libxposed module that hides accessibility from selected apps; relevant
  when a target detects automation.
- `pluginslab/android-agent-bridge`, `zephyr7030/DroidBridge` - prior art for exposing a phone over MCP.

## Not adopted
- Signature bypass, premium unlocking, DRM circumvention, Play Integrity spoofing: refused by doctrine and
  unreachable from the registry API.
- Distributing patched third-party APKs: never. Patching happens on the operator's device, from the
  operator's own installed copy.

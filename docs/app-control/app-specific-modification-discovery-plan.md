# App-specific modification discovery and execution

**Status:** researched implementation plan  
**Date:** 2026-10-16  
**Target:** fR3k HUD APP MODS  
**Distribution assumption:** private personal use first

## Summary

Add **Find improvements** to APP MODS. After selecting an APK or installed app, guide the user through:

**Choose app → Analyse → Explore suggestions → Select changes → Review → Prepare modified copy → Install → Verify or restore**

Suggestions combine the selected app's actual features, public feature requests, compatible patch recipes and improvements inferred from its purpose. Every suggestion explains what changes, why it applies, whether HUD can execute it, and how to undo it.

Confirmed defaults:

- **Phone-only:** analysis, patch preparation, signing and installation require no desktop, root, ADB or companion app.
- **Broad suggestions:** include usability, privacy, accessibility, media controls, automation, integrations and app-local capability expansion.
- **Recipes plus reviewed generation:** generate new combinations of supported patch operations. Complex new algorithms remain labelled as requiring another adapter.
- **Public research with local APKs:** APKs and full decompiled code stay on the phone. The configured AI provider receives bounded, redacted feature summaries.
- **Installation:** prefer side-by-side builds only when the exact recipe supports them; otherwise explain replacement and data limitations.
- **Private use first:** record dependency licences and provenance. Public distribution is a separate release gate.

This feature belongs in HUD. Preserve its existing BLACKWAVE integration and its independent authority.

## Research findings and adoption decisions

### Existing implementation

The current [selected-app patch screen](../../app/src/main/java/com/mcpintelligence/fr3k/ui/appcontrol/SelectedAppPatchActivity.kt) accepts an installed package and a user-supplied Xposed module. It prepares and signs a copy, but does not discover app features, research requests or generate modifications.

Reuse its signing and installer foundations, while addressing these gaps:

- Target selection must support imported APKs as well as installed apps.
- Preparation state must survive navigation and process recreation.
- Archive originals **before** patching and retain them until explicit deletion. Current pruning can remove an original after its package is uninstalled.
- Require exact compatibility for executable recipes. The existing Morphe catalogue permits empty fingerprints and cannot be execution authority.
- Keep APK signature verification separate from evidence that a modified feature works.

### Tooling

| Source | Decision |
|---|---|
| Desktop REA checkout, commit `edf46248f2a44b4e5776518489fee09d0fc4516b` | Adapt evidence references, targeted inspection, overload handling, budgets and explicit unsupported results. Its Android provider requires a full JDK and headless JADX, so do not bundle or launch it on the phone. [Pinned REA Android provider](https://github.com/morluto/rea/blob/edf46248f2a44b4e5776518489fee09d0fc4516b/docs/android-analysis.md) |
| ARSCLib | Qualify and pin **1.4.0** for binary manifest/resource inspection and editing. It documents Android support; the published POM identifies Apache-2.0 licensing. [Upstream](https://github.com/REAndroid/ARSCLib), [versioned POM](https://repo.maven.apache.org/maven2/io/github/reandroid/ARSCLib/1.4.0/ARSCLib-1.4.0.pom) |
| Google smali/dexlib2 | Qualify and pin **3.0.10** for DEX indexing, disassembly and controlled rewriting. The published POM identifies BSD-3-Clause licensing. [Versioned POM](https://dl.google.com/dl/android/maven2/com/android/tools/smali/smali-dexlib2/3.0.10/smali-dexlib2-3.0.10.pom) |
| Existing embedded LSPatch engine | Retain for qualified hook-based recipes. Generated patches use prebuilt, reviewed hook templates, never arbitrary downloaded modules. Record GPL obligations. [Upstream](https://github.com/LSPosed/LSPatch) |
| ReVanced Patcher | Use as a design reference for modular patches and compatibility matching; do not add another patch engine in this version. It supports bytecode/resource patches but adds a GPL dependency and another toolchain. [Upstream](https://github.com/ReVanced/revanced-patcher) |
| JADX and desktop native-analysis tools | Do not require them in the phone workflow. Java decompilation is unnecessary for reliable DEX matching, and decompiled Java is not a rebuildable source tree. [JADX](https://github.com/skylot/jadx) |

Before adopting a dependency, record artifact SHA256, dependency locks, transitive licences and Android qualification results. If a tool fails qualification, disable that adapter with an exact reason; do not silently fall back to desktop execution.

### Real requests to seed the research catalogue

These show real user demand; they do not establish compatibility with an arbitrary APK.

| Request | Suggestion pattern |
|---|---|
| [NewPipe: keep controls available during background playback](https://github.com/TeamNewPipe/NewPipe/issues/13461) | Accessible playback speed and queue controls. |
| [NewPipe: interoperable subscription export](https://github.com/TeamNewPipe/NewPipe/issues/8075) | Export or integration improvements tied to existing data formats. |
| [AntennaPod: per-podcast silence settings](https://github.com/AntennaPod/AntennaPod/issues/3542) | Context-specific preferences instead of a single global setting. |
| [Fossify Gallery: edit and display EXIF comments](https://github.com/FossifyOrg/Gallery/issues/390) | Metadata editing, annotation and viewing improvements. |

Preserve issue dates, status, maintainer reasoning and implementation references. A closed request does not prove the feature shipped; an open request does not prove the selected APK lacks it.

## Architecture and interfaces

### Local analysis and feature map

Create an app-modification domain module with Android adapters for import, parsing, persistence, patching and installation.

Import through Android's document picker or installed-app picker. Copy selected bytes into immutable private storage, then record:

- Package, version, signing certificates and APK hashes.
- Base/split relationships, SDK requirements and native ABIs.
- Manifest components, permissions, deep links and providers.
- Resources, settings labels, DEX classes/methods, call references and relevant API use.
- Technology indicators such as Java/Kotlin, Compose, WebView, Flutter, Unity and native libraries.

Build a searchable **FeatureMap** whose findings reference exact resource IDs or DEX descriptors and instruction locations. Distinguish observed facts, inferred purpose and unknown behavior. Obfuscated names, missing strings or decompilation failures must not establish that a feature is absent.

Run parsers and transformations in an unexported isolated Android service. Android documents that isolated services have no permissions of their own; pass only job-scoped file descriptors and bounded metadata. Keep networking, signing keys and installation in the trusted coordinator. [Android service isolation](https://developer.android.com/guide/topics/manifest/service-element)

Initial limits:

- 512 MiB total compressed input; 10,000 ZIP entries.
- 2 GiB total expanded data; 64 MiB per entry requiring full parsing.
- One active analysis/preparation worker.
- Bounded method-level analysis instead of whole-app decompilation.
- Explicit partial results when limits are reached.

Reject traversal paths, conflicting duplicate entries, malformed archives and inconsistent split sets. Unsupported native, Flutter or Unity internals receive metadata-level analysis and an explicit adapter limitation.

### Suggestion and research pipeline

Generate candidates from three separately labelled sources:

1. **Compatible recipe:** an executable recipe matches this exact artifact.
2. **Community request:** a linked public request maps to features observed in this APK.
3. **Purpose-based idea:** HUD infers an improvement and states its reasoning.

Match recipes locally first. Research verified publisher repositories through public GitHub issue/PR APIs, with optional public web search through the configured provider. If the repository association is uncertain, ask the user to confirm it before treating it as upstream for the selected app.

Cache research for seven days, retain source URLs and retrieval dates, and provide manual refresh. Network failure must preserve local suggestions.

Send AI only a redacted structured summary, capped at 32 KiB. Exclude APK bytes, full methods, credentials, private files and sensitive constants. Treat APK strings and retrieved pages as untrusted source material, never as executable instructions.

Return up to 20 candidates and show the top eight initially. Rank exact compatibility first, then feature relevance, user-goal fit, supporting requests and effort/risk. Popularity must not override incompatibility.

Each card contains:

- Proposed change and expected benefit.
- Evidence from this APK and any public request.
- Execution route: APK patch, HUD integration or guided setting change.
- Status: **Ready**, **Can generate**, **Needs adapter**, **Already available** or **Unsupported**.
- Compatibility limits, permissions, installation consequences and recovery.
- Separate confidence in relevance and confidence in execution.

### Domain and agent contracts

Add versioned types:

- `ArtifactIdentity`: immutable APK-set identity and source provenance.
- `AnalysisReport` and `FeatureEvidence`: observed facts, references and coverage limits.
- `ModificationSuggestion`: provenance, applicability, route and readiness.
- `PatchSpec`: selected changes, typed parameters, exact preconditions, dependencies and conflicts.
- `ModificationJob`: durable stage, artifact references, verification results and installer status.

Persist these in a dedicated HUD SQLite database with explicit migrations. Do not alter BLACKWAVE's database or wire contracts.

Expose equivalent bounded tools to HUD's agent and authenticated MCP surface:

- `analyze_apk(artifact_id, depth)`
- `suggest_app_mods(analysis_id, goal?, research_mode)`
- `preview_app_mods(analysis_id, selections, installation_mode)`
- `request_app_mod_preparation(preview_id)`
- `get_app_mod_job(job_id)`

Tools use artifact IDs, never arbitrary filesystem paths. Preparation requests open the actual review UI; an agent-supplied `approve=true` cannot authorize patching or installation.

## Implementation sequence and user workflow

### A. Durable foundations

Qualify the libraries on API31 and API35, including isolated-service file access, cancellation and malformed-input handling.

Introduce content-addressed source archives and durable jobs. Replace automatic archive pruning with explicit deletion. Preserve legacy records and keep manual module import as an expert option.

On process death, retain completed artifacts and mark the interrupted stage. Resume from verified checkpoints; discard incomplete outputs. Approval grants expire on process death.

### B. Analysis and suggestion screens

Integrate **Find improvements** into APP MODS and the existing patch flow.

Provide APK/installed-app selection; analysis progress and cancellation; an understandable feature summary; searchable suggestion cards with provenance/readiness filters; “What would you like this app to do?”; and a selection basket showing dependencies and conflicts.

Offline operation offers compatible recipes and locally inferred ideas. AI and public research enhance discovery but are not required for supported patch execution.

### C. Bounded generation and preparation

Implement a closed patch language supporting:

- Exact resource text, colour and layout-attribute changes.
- Typed DEX constant replacement with expected original values.
- Calls to precompiled helper templates at qualified insertion points.
- Qualified hook templates with declarative configuration.

Initial helper templates cover URL cleanup, preference persistence and supported HUD share/integration actions. Existing deep links or settings should be offered through the appropriate route when they already solve the request.

Generated specifications may reference only validated evidence and implemented operators. Resolve exact targets locally; require expected match counts and original-byte/resource preconditions. Never compile arbitrary model-generated Java, run shell commands or execute downloaded patch scripts.

Apply selected changes as one transaction to a staged copy. If any change fails, publish no partially modified APK. Reverify hashes immediately before execution.

The preparation review binds approval to the source identity, complete patch specification, options and installation strategy. Any change invalidates approval.

### D. Signing, installation and recovery

Align and sign outputs on the phone using the existing non-exportable operational signing key. Verify output signatures, manifest changes, DEX structure and expected differences before publication.

Support split-set analysis from the start. Permit modification only when the selected engine supports the complete set; never silently discard splits. Install supported sets using `PackageInstaller.Session`, which supports multiple split packages. [Android installation sessions](https://developer.android.com/reference/kotlin/android/content/pm/PackageInstaller.Session)

Offer side-by-side builds only for recipes that explicitly handle package references, provider authorities, permissions and other identity-sensitive fields. Otherwise produce a same-package candidate and explain the signing conflict before opening the installer. Android requires compatible signing identity for updates. [Android app signing](https://developer.android.com/studio/publish/app-signing)

Never uninstall automatically or claim an APK archive backs up private app data.

After installation, show distinct results:

- **Prepared:** static checks passed.
- **Installed:** Android confirmed the expected artifact.
- **Behavior verified:** a declared feature test passed.
- **User confirmed:** manually observed, recorded separately.

Retain originals, patch specifications, output hashes and results. Restore through a reviewed Android installation flow, with the same signing/data limitations explained.

### E. Guides and delivery

Every executable operator and recipe needs an offline guide, expected observable result, failure diagnosis and undo instructions. Generate coverage inventories from the same registry.

Package the updated HUD candidate with source hashes, licences, dependency locks, analysis fixtures and verification reports. Refresh the benchmark selection and handoff while preserving existing BLACKWAVE artifacts and operator records.

## Acceptance tests and release boundaries

The feature is complete when these journeys pass:

- Import an uninstalled APK and select an installed app; obtain correctly bound identities and feature maps.
- Analyse two different app types and produce meaningfully different suggestions.
- Trace each community-derived suggestion to a retrieved request; distinguish purpose-based ideas and already-present features.
- Run without internet or AI and still prepare supported local recipes.
- Select compatible changes, reject conflicts, preview exact effects and prepare a verified output entirely on the phone.
- Generate and execute a previously absent bounded specification against an owned fixture.
- Refuse changed APKs, ambiguous matches, unsupported operators, incomplete splits and model-invented evidence.
- Survive rotation, navigation, process death, cancellation and storage exhaustion without losing originals or publishing partial output.
- Verify side-by-side and same-package installation paths, signing-conflict handling, installer cancellation and restoration.
- Verify ZIP traversal, oversized entries, malicious research text and forged agent approvals cannot escape the job boundary.
- Verify neither APK bytes nor full decompiled code enter research or AI requests.
- Exercise TalkBack, 200% text, small screens and every published control/guide.

Use the existing owned fixtures, extended with media and gallery behaviors, for deterministic patch tests. Add read-only analysis fixtures for obfuscated, multidex, split, Compose and native-heavy APKs. A recipe becomes **Ready** only with exact compatibility and behavior evidence.

All runtime analysis and preparation remain phone-only; development builds may use this workstation. Server-controlled entitlements, unavailable services and missing hardware remain external prerequisites. Complex suggestions beyond the supported patch language stay visible with their exact missing adapter.

No physical phone is currently available. Emulator results can establish app behavior; physical installation, performance and recovery remain explicitly untested until measured.

## Implementation progress (2026-10-16)

Phase A foundations implemented and verified on this workstation (no emulator/phone needed for this slice):

- Versioned domain types in the `capability` module under `com.mcpintelligence.fr3k.capability.appmod`:
  `ArtifactIdentity`, `AnalysisReport` + `FeatureEvidence`/`EvidenceReference`/`TechnologyIndicator`,
  `ModificationSuggestion` (provenance/route/readiness + split relevance/execution confidence),
  `PatchSpec` (closed `PatchChange` language + `PatchPrecondition`), `ModificationJob` (durable stage
  + verification + installer status). Pure JVM with `toJson`/`fromJson`; covered by `AppModTypesTest`.
- `SuggestionRanker` implements the ranking rule (executable first; popularity never overrides
  incompatibility; readiness tier > relevance > goal fit > requests > effort/risk). Covered by
  `SuggestionRankerTest`.
- `OriginalArchive` refactored to a content-addressed object store with explicit deletion only; the
  automatic `prune()` is removed (`delete(identity)`, `list()`, `contains()` added). Signatures used
  by `LspatchPatchEngine`, `EmbeddedFixturePatchEngine`, `UndoController` and the device test are preserved.
- `ModificationStore` added (dedicated SQLite `appmod.db`, explicit versioned migrations) persisting
  analyses, suggestions, patch specs and durable jobs, with `markInterruptedOnStartup()` for
  process-death resume. Separate from BLACKWAVE's database.

Verified: `:capability:test` green, `:app:testDebugUnitTest` green (all suites, including the new
analysis test), `:app:assembleDebug` green (full APK packages).

### Progress update (2026-10-16, continued)

- `ModificationStore` is now live: `Fr3kApplication.modificationStore` exposes it and
  `markInterruptedOnStartup()` runs on a background thread at process start, so an in-flight job is
  marked INTERRUPTED after process death while a completed artifact is left untouched.
- `AndroidIdentityReader.artifactOf(context, pkg)` captures an installed package as an
  `ArtifactIdentity(SourceKind.INSTALLED)` — the Phase B target-selection entry point.
- Pinned analysis libraries integrated (build green on this workstation):
  - `io.github.reandroid:ARSCLib:1.4.0` — Apache-2.0; jar SHA256
    `0cc95b160ba4c731a5cadb455f32b79f4e64b4160bc5ec3e5183ea3676b385ce`. 1.4.0 is the refactored
    `com.reandroid` namespace and bundles two tiny Android framework interface stubs
    (`android/util/AttributeSet`, `android/content/res/XmlResourceParser`) for JVM-only use; they are
    shadowed by the framework at runtime and `assembleDebug` passes with them bundled.
  - `com.android.tools.smali:smali-dexlib2:3.0.10` — BSD-3-Clause; jar SHA256
    `688dee6a46962a4dd1774b64be926534b0b6504bfd5d5c9f89c0f5af284f50c1`. Pulls Guava 31.1-android;
    resolved by excluding the resolved Guava graph (guava/failureaccess/jsr305/error_prone/j2objc/
    checker-qual) so the pinned lspatch engine's bundled guava 32.0.1-jre stays the single provider.
- `ApkAnalyzer` (pure JVM, `appcontrol/analysis/`) produces an `AnalysisReport` from an APK set:
  dexlib2 DEX census (class/method/field counts, multidex), technology indicators (Kotlin, Compose,
  WebView, Flutter, Unity, native, obfuscation heuristic), and per-finding evidence. `ApkAnalyzerTest`
  runs it against the committed `owned-fixture.apk`. Manifest/resource extraction (ARSCLib) is still
  reported as a coverage limit.
- `AnalyzeApkTool` (`app.control.analyze`, capability `app.control.read`) is registered on the agent
  tool bus: package -> ArtifactIdentity -> ApkAnalyzer -> AnalysisReport persisted to ModificationStore,
  returning `analysisId`. First of the five plan tool contracts, for the installed-app path.
- `SuggestionGenerator` (pure JVM, offline) emits PURPOSE_BASED suggestions bound to an analysis and
  never claims READY or a compatible recipe. `SuggestAppModsTool` (`app.control.suggest`) and
  `GetAppModJobTool` (`app.control.job`) are registered with matching closed schema capabilities
  (`app.control.suggest` -> analysis_id+goal, `app.control.job` -> job_id). Three of the five tool
  contracts are now wired; ranking is `SuggestionRanker`.
- `ManifestAnalyzer` (ARSCLib) extracts the binary AndroidManifest.xml — package, version, min/target
  SDK, main activity, application class, declared permissions and components (activity/service/
  receiver/provider) — and `ApkAnalyzer` now emits `manifest`, `permissions` and `components` evidence
  from it, replacing the earlier "manifest not extracted" coverage limit.

Remaining (deferred): ARSCLib resource-table/string extraction (settings labels etc.), the isolated
analysis service wrapper and FeatureMap persistence, the GitHub/AI research pipeline
with the 32 KiB redacted summary, the analysis/suggestion/preview screens, the closed patch language
executor (resource/DEX rewriting), split-set install via `PackageInstaller.Session`,
guides/coverage inventories, on-device (emulator) qualification of isolated-service file access, and
the acceptance suite.

### Implementation update — 2026-10-17

The earlier deferred list above is historical. Resource/DEX extraction, isolated descriptor worker, FTS migrations, community/AI boundary, all five tools, exact preview/approval, closed executor, real split-set installation/restoration, phone UI, persisted selections and offline guides are implemented. Actual modified-app and split-set journeys passed on API31 and API35 (six tests per emulator at this checkpoint). See [verification record](APP-MODIFICATION-VERIFICATION.md) for scope, retained failures and measured gates. Additional interruption/approval/accessibility checks and final packaging are underway; physical and live-provider gates must retain explicit untested status. No prioritization checkpoint is required to continue the authorized implementation.

# Embedded LSPatch decision

Status: SOURCE_VERIFIED / BUILD_TOOLCHAIN_BLOCKED / DEVICE_UNTESTED.

Reviewed source: JingMatrix/LSPatch 0dc50f42503711b14f5e2bb217c2bdd6321ce5be.
Vector/core gitlink: e00c5c5038bbcc14e0b382ab893301d6993e6989. `.gitmodules` names JingMatrix/LSPosed as core origin; do not silently substitute Vector HEAD.

## Actual engine interface

`patch/src/main/java/org/lsposed/patch/ApkPatcher.java` exposes constructor `(Logger, PatchSpec)` and `List<File> patch() throws IOException`. `PatchSpec.builder()` has `apk(File)`, `apks(List<File>)`, `outputDir(File)`, `useManager(boolean)`, `module(File)`, `modules(List<File>)`, `sigBypassLevel(int)` and `build()`. It makes defensive collection copies. Manager mode and embedded modules are mutually exclusive.

Manager-independent mode is real in source: useManager=false; embed the approved module. `meta-loader/.../LSPAppComponentFactoryStub.java` loads dex/native assets from the target archive in the embed branch. `patch-loader/.../LSPApplication.java` uses LocalApplicationService rather than RemoteApplicationService for that branch. `PatchConfig.java` documents that integrated apps never bind a manager. This source evidence does NOT prove runtime acceptance on our target.

The embedded payload requires generated metaloader dex, loader dex and liblspatch.so assets for supported ABIs. ApkPatcher explicitly fails if the metaloader is missing. A patch Java JAR alone is not sufficient. The original APK is nested in the output; this does not preserve Android's original install signer.

Safety configuration: signature bypass level 0 only, no DocumentsProvider/private-data exposure, no added permissions or debug overrides by default. Do not import signature spoofing, cloaking, syscall bypass or anti-tamper evasion features from upstream into HUD. No built-in publicly known keystore for production targets.

## Measured local toolchain mismatch

Pinned upstream build.gradle.kts requests Java 21, compile SDK 37, build tools 37.0.0, NDK 29.0.13113456, CMake 3.29.8+ and C++23. Local /usr/lib/android-sdk inspection found platform android-35, build tools 35.0.1/36.0.0, no NDK and no SDK CMake. JDK17 and JDK25 are present, JDK21 absent. The HUD build remains AGP8.7.3/JDK17/SDK35. Do not upgrade it wholesale just to import this engine.

Next qualification requires an isolated upstream toolchain and pinned native/submodule build; then publish locally verified loader assets and dependency provenance into a deliberately licensed integration. No downloaded unverified binaries or placeholder .so files are acceptable.

No LICENSE/COPYING file was found at HUD repository root. GPLv3 upstream integration/distribution therefore remains an explicit licence decision, not assumed permission to redistribute combined code. No third-party source was vendored in this pass.

Sources: https://github.com/JingMatrix/LSPatch/tree/0dc50f42503711b14f5e2bb217c2bdd6321ce5be

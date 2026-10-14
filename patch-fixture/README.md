# Owned Android patch fixture

Package: `com.mcpintelligence.fr3k.patchfixture`.

This module is the disposable target for qualifying the embedded app-control engines.
It has no INTERNET permission, accounts, billing or advertisement SDK. The banner
is an owned local TextView. `DemoFeatures.showBanner()` is the deterministic method
to instrument. The refresh button re-evaluates that method and the screen displays
the current PID for subsequent same-process verification.

The initial application intentionally has no toggle IPC, loaded hook runtime or
embedded patch engine. Its unit test proves only that the unmodified method returns
true. Do not report the baseline fixture as live-hook support or ad removal.

Build/test from the repository root:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ./gradlew :patch-fixture:testDebugUnitTest :patch-fixture:assembleDebug --console=plain

Later engine validation must show original banner visible, prepared-runtime
handshake, banner hidden and restored using the same target PID, and rejection of
unauthorised/replayed control messages. Device runtime evidence is required in
addition to compilation. Use an explicitly selected disposable device/emulator;
never infer that an existing personal application can safely be replaced.

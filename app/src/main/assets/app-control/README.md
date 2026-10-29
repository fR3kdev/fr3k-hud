# App-mod fixtures

The `.apk` files here are generated, not source. They are ignored by Git because they are build
outputs of the in-repo fixture modules, and a missing one is what made the app-mod tests fail on a
fresh checkout with `FileNotFoundException`.

Regenerate them on any machine with an Android SDK and JDK 17:

    python3 scripts/build_appmod_fixtures.py          # :patch-fixture and :patch-fixture-two
    python3 scripts/build_owned_fixture_assets.py     # owned hook module (needs the Xposed API jar)

`scripts/build_owned_fixture_assets.py` downloads nothing itself: run
`scripts/qualify_lspatch_fixture.py --lspatch-jar <engine.jar> --output <dir>` once if the pinned
Xposed API jar is absent. The LSPatch engine jar is not in this repository, so the patched
qualification APK can only be produced where that engine is available.

`appmod-feature-split.apk.idsig` is committed: it belongs to the split fixture the device tests
verify and is not reproducible from the modules alone.

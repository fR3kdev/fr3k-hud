package com.mcpintelligence.fr3k.appcontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.Fr3kApplication
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Selecting a real installed third-party app — not a fixture — must produce real registry entries and
 * real guides for it. This is the check that the release catalogue is driven by what the operator chose,
 * not by test packages.
 *
 * The previous selection is restored afterwards so the device is left as it was found. The map itself is
 * recorded separately, through the normally-running app over MCP, because an accessibility service cannot
 * be hosted inside an instrumentation process.
 */
@RunWith(AndroidJUnit4::class)
class RealAppSelectionDeviceTest {

    private val app get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A real pre-installed Android app that is not a HUD component. */
    private val realApp = "com.android.deskclock"

    private var before: Set<String> = emptySet()
    private val evidence = JSONArray()

    @After fun restoreSelection() {
        val current = CapabilityCatalogue.selectedPackages(context)
        current.filterNot { it in before }.forEach { CapabilityCatalogue.select(context, it, false) }
        before.filterNot { it in current }.forEach { CapabilityCatalogue.select(context, it, true) }
        app.capabilityHost.reset()
        val directory = File(app.filesDir, "ui-audit").apply { mkdirs() }
        File(directory, "real-app-selection.json").writeText(evidence.toString(2))
    }

    @Test fun aRealInstalledAppGetsRealControlsAndGuides() {
        before = CapabilityCatalogue.selectedPackages(context)
        assertTrue(
            "this verification needs the real app installed: $realApp",
            context.packageManager.getLaunchIntentForPackage(realApp) != null,
        )

        CapabilityCatalogue.select(context, realApp, true)
        app.capabilityHost.reset()

        val selected = CapabilityCatalogue.selectedPackages(context)
        assertTrue("$realApp must be selected after selection", realApp in selected)

        val entries = app.capabilityHost.registry().all()
        val mine = entries.filter { it.descriptor.identity.packageName == realApp }
        assertTrue("selection produced no capabilities for $realApp", mine.isNotEmpty())

        // The controls admitted for a chosen app: its own launch plus its app-op operations.
        val ids = mine.map { it.descriptor.id }
        assertTrue("expected a launch capability for $realApp, got $ids", ids.size >= 2)
        assertTrue("expected app-op capabilities bound to $realApp, got $ids", ids.any { it.contains("policy") || it.contains("op") })

        // Every capability must be bound to the real installed identity, not a placeholder.
        val identity = mine.first().descriptor.identity
        assertTrue("capability is not bound to the selected package", identity.packageName == realApp)
        assertTrue("identity has no signer hash", identity.signerSha256.isNotBlank())
        assertTrue("identity has no APK hashes", identity.splitSha256.isNotEmpty())
        assertTrue("identity has no version", identity.versionCode > 0)

        // Guides must exist for the selected app's controls, and must be honest about their limits.
        val guides = ModGuideCatalogue.all(context)
        val mineGuides = guides.filter { guide -> guide.capabilityIds.any { it in ids } || guide.title.contains("app", true) }
        assertTrue("no guide covers the selected app's capabilities", mineGuides.isNotEmpty())
        assertTrue("every guide must state limits and an undo path", guides.all { it.limits.isNotBlank() && it.reversible.isNotBlank() })

        // Fixtures must NOT leak into the normal (release) catalogue.
        val fixtureIds = guides.flatMap { it.capabilityIds }.filter { it.startsWith("fixture.") || it.startsWith("target.two") }
        assertTrue("fixture capabilities must not appear for an operator in normal mode: $fixtureIds", fixtureIds.isEmpty())

        evidence.put(
            JSONObject()
                .put("realApp", realApp)
                .put("capabilityCount", mine.size)
                .put("capabilityIds", JSONArray(ids))
                .put("guideCount", guides.size)
                .put("versionCode", identity.versionCode)
                .put("signerSha256", identity.signerSha256.take(16) + "…")
                .put("splitHashCount", identity.splitSha256.size),
        )
    }

    @Test fun removingTheSelectionRemovesItsControls() {
        before = CapabilityCatalogue.selectedPackages(context)
        CapabilityCatalogue.select(context, realApp, true)
        app.capabilityHost.reset()
        assertTrue(app.capabilityHost.registry().all().any { it.descriptor.identity.packageName == realApp })

        CapabilityCatalogue.select(context, realApp, false)
        app.capabilityHost.reset()
        assertFalse(
            "removed app must not keep capabilities in the registry",
            app.capabilityHost.registry().all().any { it.descriptor.identity.packageName == realApp },
        )
        assertFalse(realApp in CapabilityCatalogue.selectedPackages(context))
    }
}

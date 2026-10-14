package com.mcpintelligence.fr3k.appcontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class EmbeddedFixturePatcherDeviceTest {
    @Test fun hudPreparesOwnedFixtureEntirelyOnDeviceWithoutManager() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prepared = EmbeddedFixturePatchEngine(context).prepare()
        assertTrue(prepared.apk.isFile)
        assertTrue(prepared.sha256.matches(Regex("[0-9a-f]{64}")))
        ZipFile(prepared.apk).use { zip ->
            val config = JSONObject(zip.getInputStream(zip.getEntry("assets/lspatch/config.json")).bufferedReader().use { it.readText() })
            assertFalse(config.getBoolean("useManager"))
            assertEquals(0, config.getInt("sigBypassLevel"))
            assertNotNull(zip.getEntry("assets/lspatch/loader.dex"))
            assertNotNull(zip.getEntry("assets/lspatch/so/x86_64/liblspatch.so"))
        }
        android.util.Log.i("FR3KPT", "Prepared owned fixture on device sha256=${prepared.sha256}")
    }
}

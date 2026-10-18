package com.mcpintelligence.fr3k.appcontrol

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.mcpintelligence.fr3k.capability.AppIdentity
import com.mcpintelligence.fr3k.capability.appmod.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AppModMigrationDeviceTest {
    @Test fun versionOneMigrationPreservesAnalysisAndBackfillsSearch() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        }
        val report = AnalysisReport("preserved", ArtifactIdentity(AppIdentity("org.example.owned", 1, "a".repeat(64), listOf("b".repeat(64))), SourceKind.IMPORTED_APK, 1, "owned migration fixture"),
            1, emptySet(), listOf(FeatureEvidence("caption", EvidenceKind.OBSERVED, "gallery caption", EvidenceReference.Resource("7f010001@"))), true, emptyList())
        try {
            ModificationStore(context).use { store ->
                store.saveAnalysis(report)
                store.writableDatabase.apply {
                    execSQL("DROP TABLE feature_search")
                    execSQL("DROP TABLE record")
                    version = 1
                }
            }
            ModificationStore(context).use { migrated ->
                assertEquals(3, migrated.readableDatabase.version)
                assertEquals(report, migrated.getAnalysis("preserved"))
                assertEquals(listOf("caption"), migrated.searchFeatures("preserved", "gallery").map { it.evidenceId })
                migrated.putRecord("draft", "preserved", org.json.JSONObject().put("value", "retained"))
            }
            ModificationStore(context).use { assertEquals("retained", it.record("draft", "preserved")!!.getString("value")) }
        } finally { root.deleteRecursively() }
    }
}

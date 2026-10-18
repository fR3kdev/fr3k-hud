package com.mcpintelligence.fr3k.appcontrol

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mcpintelligence.fr3k.capability.appmod.AnalysisReport
import com.mcpintelligence.fr3k.capability.appmod.InstallationStatus
import com.mcpintelligence.fr3k.capability.appmod.ModificationJob
import com.mcpintelligence.fr3k.capability.appmod.ModificationStage
import com.mcpintelligence.fr3k.capability.appmod.ModificationSuggestion
import com.mcpintelligence.fr3k.capability.appmod.PatchSpec
import com.mcpintelligence.fr3k.capability.appmod.VerificationStatus
import org.json.JSONObject

/**
 * Dedicated HUD SQLite store for the app-modification domain. It is a separate database with its
 * own explicit migrations and shares no schema or wire contract with BLACKWAVE. The payload column
 * is the single source of truth (a JSON blob); the remaining columns are a query index only.
 */
class ModificationStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        createRecords(db)
        createFeatures(db)
        db.execSQL(
            """
            CREATE TABLE analysis (
                analysis_id TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                analyzed_at INTEGER NOT NULL,
                completed INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE suggestion (
                suggestion_id TEXT PRIMARY KEY,
                analysis_id TEXT NOT NULL,
                provenance TEXT NOT NULL,
                readiness TEXT NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE patch_spec (
                spec_id TEXT PRIMARY KEY,
                fingerprint TEXT NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE job (
                job_id TEXT PRIMARY KEY,
                package_name TEXT,
                stage TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                verification TEXT NOT NULL,
                installer_status TEXT NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (version in oldVersion + 1..newVersion) {
            MIGRATIONS[version]?.invoke(db)
                ?: error("no migration registered from version ${version - 1} to $version")
        }
    }

    /** SQLite owns serialization; never acquire a JVM monitor while a transaction is held. */
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    fun saveAnalysis(report: AnalysisReport) {
        val values = ContentValues().apply {
            put("analysis_id", report.analysisId)
            put("package_name", report.artifact.packageName)
            put("fingerprint", report.artifact.fingerprint)
            put("analyzed_at", report.analyzedAtMillis)
            put("completed", if (report.completed) 1 else 0)
            put("payload", report.toJson().toString())
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.insertWithOnConflict("analysis", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            writeFeatures(db, report)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Publish only a complete, independently verified output set and its receipt together. */
    fun publishPrepared(job: ModificationJob, outputs: JSONObject) {
        require(job.stage == ModificationStage.PREPARED && job.outputSha256 != null && job.outputApkPath != null)
        val artifact = requireNotNull(job.artifact)
        require(job.previewId == job.jobId && job.patchSpec?.identityFingerprint == artifact.fingerprint)
        val files = outputs.getJSONArray("files"); val hashes = outputs.getJSONArray("hashes")
        require(files.length() == artifact.splitSha256.size && hashes.length() == files.length())
        require(files.getString(0) == job.outputApkPath && hashes.getString(0) == job.outputSha256)
        require((0 until hashes.length()).all { hashes.getString(it).matches(Regex("[0-9a-f]{64}")) && files.getString(it).isNotBlank() })
        val db = writableDatabase
        db.beginTransaction()
        try {
            putRecord("outputs", job.jobId, outputs)
            saveJob(job)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun getAnalysis(analysisId: String): AnalysisReport? {
        readableDatabase.query("analysis", null, "analysis_id = ?", arrayOf(analysisId), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) AnalysisReport.fromJson(JSONObject(cursor.payload())) else null
        }
    }

    fun saveSuggestion(suggestion: ModificationSuggestion) {
        val values = ContentValues().apply {
            put("suggestion_id", suggestion.suggestionId)
            put("analysis_id", suggestion.analysisId)
            put("provenance", suggestion.provenance.name)
            put("readiness", suggestion.readiness.name)
            put("payload", suggestion.toJson().toString())
        }
        check(writableDatabase.insertWithOnConflict("suggestion", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    fun putRecord(kind: String, id: String, payload: JSONObject) {
        require(kind.matches(Regex("[a-z_]+")) && id.length in 1..200)
        val values = ContentValues().apply { put("kind", kind); put("record_id", id); put("payload", payload.toString()) }
        check(writableDatabase.insertWithOnConflict("record", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    fun record(kind: String, id: String): JSONObject? = readableDatabase.query("record", arrayOf("payload"),
        "kind = ? AND record_id = ?", arrayOf(kind, id), null, null, null).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun analyses(): List<AnalysisReport> = readableDatabase.query("analysis", arrayOf("payload"), null, null, null, null,
        "analyzed_at DESC", "100").use { c -> buildList { while (c.moveToNext()) add(AnalysisReport.fromJson(JSONObject(c.getString(0)))) } }
    fun searchFeatures(analysisId: String, query: String): List<com.mcpintelligence.fr3k.capability.appmod.FeatureEvidence> {
        val words = Regex("[A-Za-z0-9_]+").findAll(query.take(200)).take(12).map { "\"${it.value}\"" }.toList()
        val sql = "SELECT payload FROM feature_search WHERE analysis_id = ?" + if (words.isEmpty()) " LIMIT 100" else " AND feature_search MATCH ? LIMIT 100"
        val args = if (words.isEmpty()) arrayOf(analysisId) else arrayOf(analysisId, words.joinToString(" AND "))
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(com.mcpintelligence.fr3k.capability.appmod.FeatureEvidence.fromJson(JSONObject(c.getString(0)))) } }
    }
    fun replaceSuggestions(analysisId: String, values: List<ModificationSuggestion>) {
        val db = writableDatabase
        db.beginTransaction()
        try { db.delete("suggestion", "analysis_id = ?", arrayOf(analysisId)); values.forEach(::saveSuggestion)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun getSuggestionsForAnalysis(analysisId: String): List<ModificationSuggestion> {
        readableDatabase.query("suggestion", null, "analysis_id = ?", arrayOf(analysisId), null, null, null).use { cursor ->
            val out = ArrayList<ModificationSuggestion>()
            while (cursor.moveToNext()) out.add(ModificationSuggestion.fromJson(JSONObject(cursor.payload())))
            return out
        }
    }

    fun saveSpec(spec: PatchSpec) {
        val values = ContentValues().apply {
            put("spec_id", spec.specId)
            put("fingerprint", spec.identityFingerprint)
            put("payload", spec.toJson().toString())
        }
        check(writableDatabase.insertWithOnConflict("patch_spec", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    fun getSpec(specId: String): PatchSpec? {
        readableDatabase.query("patch_spec", null, "spec_id = ?", arrayOf(specId), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) PatchSpec.fromJson(JSONObject(cursor.payload())) else null
        }
    }

    fun saveJob(job: ModificationJob) {
        val values = ContentValues().apply {
            put("job_id", job.jobId)
            put("package_name", job.artifact?.packageName)
            put("stage", job.stage.name)
            put("created_at", job.createdAtMillis)
            put("updated_at", job.updatedAtMillis)
            put("verification", job.verification.name)
            put("installer_status", job.installerStatus.name)
            put("payload", job.toJson().toString())
        }
        check(writableDatabase.insertWithOnConflict("job", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    fun getJob(jobId: String): ModificationJob? {
        readableDatabase.query("job", null, "job_id = ?", arrayOf(jobId), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) ModificationJob.fromJson(JSONObject(cursor.payload())) else null
        }
    }

    fun listJobs(): List<ModificationJob> {
        readableDatabase.query("job", null, null, null, null, null, "created_at DESC").use { cursor ->
            val out = ArrayList<ModificationJob>()
            while (cursor.moveToNext()) out.add(ModificationJob.fromJson(JSONObject(cursor.payload())))
            return out
        }
    }

    /**
     * Called once at startup. Any job still in an in-flight stage is marked INTERRUPTED so the next
     * launch resumes from a verified checkpoint instead of treating a half-finished stage as done.
     * Jobs that already hold a completed artifact (PREPARED/INSTALLED) are left untouched.
     */
    fun markInterruptedOnStartup(startedBeforeMillis: Long = System.currentTimeMillis()): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
        val inFlight = setOf(
            ModificationStage.QUEUED, ModificationStage.ANALYZING, ModificationStage.SUGGESTING,
            ModificationStage.SELECTING, ModificationStage.PREVIEWING, ModificationStage.PREPARING,
            ModificationStage.VERIFYING,
        )
        val placeholders = inFlight.joinToString(",") { "?" }
        val toInterrupt = ArrayList<ModificationJob>()
        db.query(
            "job", arrayOf("payload"), "stage IN ($placeholders) AND updated_at < ?", (inFlight.map { it.name } + startedBeforeMillis.toString()).toTypedArray(),
            null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                toInterrupt.add(ModificationJob.fromJson(JSONObject(cursor.payload())))
            }
        }
        val now = System.currentTimeMillis()
        toInterrupt.forEach { job ->
            saveJob(job.copy(stage = ModificationStage.INTERRUPTED, updatedAtMillis = now))
        }
        db.setTransactionSuccessful()
        return toInterrupt.size
        } finally { db.endTransaction() }
    }

    private fun Cursor.payload(): String = getString(getColumnIndexOrThrow("payload"))

    companion object {
        const val DATABASE_NAME = "appmod.db"
        const val DATABASE_VERSION = 3

        private fun createRecords(db: SQLiteDatabase) = db.execSQL("CREATE TABLE record (kind TEXT NOT NULL, record_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind, record_id))")
        private fun createFeatures(db: SQLiteDatabase) = db.execSQL("CREATE VIRTUAL TABLE feature_search USING fts4(analysis_id, evidence_id, payload, feature, notindexed=analysis_id, notindexed=evidence_id, notindexed=payload)")
        private fun writeFeatures(db: SQLiteDatabase, report: AnalysisReport) {
            db.delete("feature_search", "analysis_id = ?", arrayOf(report.analysisId))
            report.evidence.forEach { evidence -> db.execSQL("INSERT INTO feature_search(analysis_id, evidence_id, payload, feature) VALUES(?, ?, ?, ?)",
                arrayOf(report.analysisId, evidence.evidenceId, evidence.toJson().toString(), evidence.feature)) }
        }

        /** Version -> migration applied to bring a schema from `version - 1` to `version`. */
        val MIGRATIONS: Map<Int, (SQLiteDatabase) -> Unit> = mapOf(
            2 to { db -> createRecords(db) },
            3 to { db -> createFeatures(db)
                db.query("analysis", arrayOf("payload"), null, null, null, null, null).use { c -> while (c.moveToNext()) writeFeatures(db, AnalysisReport.fromJson(JSONObject(c.getString(0)))) }
            },
        )
    }
}

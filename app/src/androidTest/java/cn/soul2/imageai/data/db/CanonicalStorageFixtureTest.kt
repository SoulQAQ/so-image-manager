package cn.soul2.imageai.data.db

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.soul2.imageai.analysis.ActivationResult
import cn.soul2.imageai.analysis.CanonicalAnalysisDraft
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.analysis.CanonicalTermInput
import cn.soul2.imageai.analysis.CorrectionCommand
import cn.soul2.imageai.analysis.CorrectionResult
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanonicalStorageFixtureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: AppDatabase? = null

    @After
    fun cleanUp() {
        database?.close()
        database = null
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun fifteenThousandCanonicalRowsStayBoundedAndSupportRerunCorrectionAndDetailRead() =
        runBlocking {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                .build()
                .also { database = it }
            (1L..ROW_COUNT).chunked(IMAGE_BATCH_SIZE).forEach { ids ->
                db.imageDao().upsert(ids.map(::image))
            }
            val mediaBaselineBytes = databaseBytes(db)

            val fixtureMillis = measureElapsed { seedCanonicalFixture(db) }
            val activeCanonicalBytes = databaseBytes(db)
            val repository = CanonicalMetadataRepository(db)
            val sampleIds = (SAMPLE_INTERVAL.toLong()..ROW_COUNT step SAMPLE_INTERVAL.toLong())
                .toList()
            val rerunMillis = measureElapsed {
                sampleIds.forEach { imageId ->
                    assertTrue(repository.activateAnalysis(rerunDraft(imageId)) is ActivationResult.Activated)
                }
            }
            val withHistoryBytes = databaseBytes(db)
            val estimatedHistoryBytes = db.analysisDao()
                .getInactiveRetentionCandidates()
                .sumOf { candidate -> candidate.estimatedBytes }
            val measuredHistoryBytes = (withHistoryBytes - activeCanonicalBytes).coerceAtLeast(0L)
            assertTrue(
                "Retention estimate $estimatedHistoryBytes is below measured history growth " +
                    "$measuredHistoryBytes",
                estimatedHistoryBytes >= measuredHistoryBytes,
            )
            val correctionMillis = measureElapsed {
                sampleIds.forEach { imageId ->
                    assertTrue(
                        repository.applyCorrection(
                            imageId,
                            CorrectionCommand.AddTerm(AnalysisTermKind.TAG, "user-$imageId"),
                            nowEpochMillis = 40L + imageId,
                        ) is CorrectionResult.Applied,
                    )
                }
            }
            val detailReadMillis = measureElapsed {
                sampleIds.forEach { imageId ->
                    assertNotNull(repository.getEffectiveSnapshot(imageId))
                }
            }

            val finalDatabaseBytes = databaseBytes(db)
            val totalBytesPerImage = finalDatabaseBytes / ROW_COUNT
            val canonicalDeltaBytes = (activeCanonicalBytes - mediaBaselineBytes).coerceAtLeast(0L)
            val canonicalBytesPerImage = canonicalDeltaBytes / ROW_COUNT

            assertEquals(ROW_COUNT, queryLong(db, "SELECT COUNT(*) FROM image"))
            assertEquals(ROW_COUNT, queryLong(db, "SELECT COUNT(*) FROM active_image_analysis"))
            assertTrue(
                "Phase 2B canonical delta exceeds 2.5 KiB/image: " +
                    "$canonicalBytesPerImage bytes/image",
                canonicalBytesPerImage <= MAX_CANONICAL_BYTES_PER_IMAGE,
            )
            assertTrue(
                "Phase 2B media plus canonical exceeds 4 KiB/image: " +
                    "$totalBytesPerImage bytes/image",
                totalBytesPerImage <= MAX_PHASE_2B_BYTES_PER_IMAGE,
            )
            println(
                "CANONICAL_15K fixtureMs=$fixtureMillis rerunMs=$rerunMillis " +
                    "correctionMs=$correctionMillis detailReadMs=$detailReadMillis " +
                    "mediaBaselineBytes=$mediaBaselineBytes activeCanonicalBytes=$activeCanonicalBytes " +
                    "finalDatabaseBytes=$finalDatabaseBytes canonicalBytesPerImage=$canonicalBytesPerImage " +
                    "totalBytesPerImage=$totalBytesPerImage estimatedHistoryBytes=$estimatedHistoryBytes " +
                    "measuredHistoryBytes=$measuredHistoryBytes",
            )
        }

    private fun seedCanonicalFixture(database: AppDatabase) {
        val sql = database.openHelper.writableDatabase
        val analysis = sql.compileStatement(
            "INSERT INTO image_analysis (analysis_id, image_local_id, schema_version, caption, " +
                "extension_json, content_hash, provider_profile_id, model_profile_id, " +
                "protocol_definition_id, prompt_template_id, created_at_epoch_millis, " +
                "completed_at_epoch_millis) VALUES (?, ?, 1, ?, ?, ?, 'fixture-provider', " +
                "'fixture-model', 'fixture-protocol', 'fixture-prompt', ?, ?)",
        )
        val term = sql.compileStatement(
            "INSERT INTO analysis_term " +
                "(analysis_id, kind, normalized_key, display_value, confidence) " +
                "VALUES (?, ?, ?, ?, 0.8)",
        )
        val active = sql.compileStatement(
            "INSERT INTO active_image_analysis (image_local_id, analysis_id) VALUES (?, ?)",
        )
        val correction = sql.compileStatement(
            "INSERT INTO image_user_correction " +
                "(image_local_id, caption_mode, caption_value, revision, updated_at_epoch_millis) " +
                "VALUES (?, 'SET', ?, 1, ?)",
        )
        val override = sql.compileStatement(
            "INSERT INTO user_term_override " +
                "(image_local_id, kind, normalized_key, action, display_value, revision, " +
                "updated_at_epoch_millis) VALUES (?, 'TAG', ?, 'ADD', ?, 1, ?)",
        )
        val metadata = sql.compileStatement(
            "INSERT INTO effective_image_metadata " +
                "(image_local_id, caption, caption_source, projection_generation, " +
                "updated_at_epoch_millis) VALUES (?, ?, ?, 1, ?)",
        )
        val effectiveTerm = sql.compileStatement(
            "INSERT INTO effective_image_term " +
                "(image_local_id, kind, normalized_key, display_value, source, confidence, " +
                "source_analysis_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
        )

        sql.beginTransaction()
        try {
            (1L..ROW_COUNT).forEach { imageId ->
                val analysisId = analysisId(imageId)
                val caption = caption(imageId, rerun = false)
                val extensionJson = "{\"fixture\":true,\"group\":${imageId % 20L}}"
                val contentHash = imageId.toString(16).uppercase(Locale.ROOT).padStart(64, '0')
                bindAndExecute(
                    analysis,
                    analysisId,
                    imageId,
                    caption,
                    extensionJson,
                    contentHash,
                    imageId,
                    imageId,
                )
                bindAndExecute(active, imageId, analysisId)
                val userCorrected = imageId % CORRECTION_INTERVAL == 0L
                val effectiveCaption = if (userCorrected) "User caption $imageId" else caption
                if (userCorrected) {
                    bindAndExecute(correction, imageId, effectiveCaption, imageId)
                    bindAndExecute(
                        override,
                        imageId,
                        "favorite-$imageId",
                        "Favorite $imageId",
                        imageId,
                    )
                }
                bindAndExecute(
                    metadata,
                    imageId,
                    effectiveCaption,
                    if (userCorrected) "USER" else "AI",
                    imageId,
                )
                listOf(
                    "TAG" to tagValues(imageId, rerun = false),
                    "CATEGORY" to categoryValues(imageId, rerun = false),
                    "SEARCH_TOKEN" to tokenValues(imageId, rerun = false),
                ).forEach { (kind, values) ->
                    values.forEach { value ->
                        bindAndExecute(term, analysisId, kind, value, value)
                        bindAndExecute(
                            effectiveTerm,
                            imageId,
                            kind,
                            value,
                            value,
                            "AI",
                            0.8,
                            analysisId,
                        )
                    }
                }
                if (userCorrected) {
                    bindAndExecute(
                        effectiveTerm,
                        imageId,
                        "TAG",
                        "favorite-$imageId",
                        "Favorite $imageId",
                        "USER",
                        null,
                        null,
                    )
                }
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    private fun bindAndExecute(statement: androidx.sqlite.db.SupportSQLiteStatement, vararg values: Any?) {
        statement.clearBindings()
        values.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> statement.bindNull(position)
                is String -> statement.bindString(position, value)
                is Long -> statement.bindLong(position, value)
                is Int -> statement.bindLong(position, value.toLong())
                is Double -> statement.bindDouble(position, value)
                else -> error("Unsupported fixture bind type: ${value::class.java.name}")
            }
        }
        statement.executeInsert()
    }

    private fun rerunDraft(imageId: Long) = CanonicalAnalysisDraft(
        analysisId = analysisId(imageId + ROW_COUNT),
        imageLocalId = imageId,
        schemaVersion = 1,
        caption = caption(imageId, rerun = true),
        tags = tagValues(imageId, rerun = true).map(::CanonicalTermInput),
        categories = categoryValues(imageId, rerun = true).map(::CanonicalTermInput),
        searchTokens = tokenValues(imageId, rerun = true),
        extensionJson = "{\"fixture\":true,\"group\":${imageId % 20L}}",
        providerProfileId = "fixture-provider",
        modelProfileId = "fixture-model",
        protocolDefinitionId = "fixture-protocol",
        promptTemplateId = "fixture-prompt",
        createdAtEpochMillis = 20L + imageId,
        completedAtEpochMillis = 30L + imageId,
    )

    private fun image(id: Long) = ImageEntity(
        localId = id,
        volumeName = "external_primary",
        mediaStoreId = id,
        contentUri = "content://media/external_primary/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        width = 4_032,
        height = 3_024,
        sizeBytes = 4L * 1_024L * 1_024L,
        capturedAtEpochMillis = id * 1_000L,
        addedAtEpochMillis = id * 1_000L,
        modifiedAtEpochMillis = id * 1_000L,
        sortTimeEpochMillis = id * 1_000L,
        bucketId = 1L,
        bucketName = "SoIM fixture",
        isFavorite = false,
        quickFingerprint = "canonical-$id",
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )

    private fun analysisId(id: Long): String = String.format(
        Locale.ROOT,
        "123e4567-e89b-12d3-a456-%012d",
        id,
    )

    private fun caption(imageId: Long, rerun: Boolean): String {
        val prefix = if (rerun) "重跑" else "初次"
        val repetitions = if (imageId % 10L == 0L) 80 else 8 + (imageId % 13L).toInt()
        return List(repetitions) { index ->
            "$prefix 图片${imageId % 100L} 场景细节$index"
        }.joinToString(" ")
    }

    private fun tagValues(imageId: Long, rerun: Boolean): List<String> =
        fixtureValues("tag", imageId, rerun, 4 + (imageId % 21L).toInt())

    private fun categoryValues(imageId: Long, rerun: Boolean): List<String> =
        fixtureValues("category", imageId, rerun, 1 + (imageId % 4L).toInt())

    private fun tokenValues(imageId: Long, rerun: Boolean): List<String> =
        fixtureValues("token", imageId, rerun, 4 + (imageId % 29L).toInt())

    private fun fixtureValues(
        kind: String,
        imageId: Long,
        rerun: Boolean,
        count: Int,
    ): List<String> {
        val generation = if (rerun) "rerun" else "initial"
        return List(count) { index -> "$kind-$generation-${imageId % 100L}-$index" }
    }

    private fun queryLong(database: AppDatabase, sql: String): Long =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun databaseBytes(database: AppDatabase): Long {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        return queryLong(database, "PRAGMA page_count") * queryLong(database, "PRAGMA page_size")
    }

    private suspend inline fun measureElapsed(crossinline block: suspend () -> Unit): Long {
        val started = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - started
    }

    private companion object {
        const val DATABASE_NAME = "phase-2b-canonical-15k.db"
        const val ROW_COUNT = 15_000L
        const val IMAGE_BATCH_SIZE = 500
        const val CORRECTION_INTERVAL = 10L
        const val SAMPLE_INTERVAL = 100
        const val MAX_CANONICAL_BYTES_PER_IMAGE = 2_560L
        const val MAX_PHASE_2B_BYTES_PER_IMAGE = 4L * 1_024L
    }
}

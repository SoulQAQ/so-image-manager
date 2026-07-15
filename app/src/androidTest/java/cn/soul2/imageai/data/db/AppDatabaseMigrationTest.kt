package cn.soul2.imageai.data.db

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_1_2
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_2_3
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @After
    fun cleanUpDatabases() {
        context.deleteDatabase(TEST_DATABASE)
        context.deleteDatabase(FRESH_DATABASE)
        context.deleteDatabase(LEGACY_DATABASE)
    }

    @Test
    fun migrationOneToTwoPreservesSettingsAndEnforcesExternalIdentity() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                "INSERT INTO app_setting (`key`, value_json, updated_at_epoch_millis) " +
                    "VALUES ('appearance.theme', '\"system\"', 1720598400000)",
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            MIGRATION_1_2,
        ).use { database ->
            database.query(
                "SELECT value_json FROM app_setting WHERE `key` = 'appearance.theme'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("\"system\"", cursor.getString(0))
            }

            insertImage(database, mediaStoreId = 42L, contentUri = "content://media/42")
            try {
                insertImage(database, mediaStoreId = 42L, contentUri = "content://media/duplicate")
                throw AssertionError("Duplicate MediaStore identity must be rejected")
            } catch (_: SQLiteConstraintException) {
                // Expected: (volume_name, media_store_id) is the external identity.
            }
        }
    }

    @Test
    fun migrationTwoToThreePreservesMediaAndEnforcesSameImageActiveAnalysis() {
        migrationHelper.createDatabase(TEST_DATABASE, 2).apply {
            execSQL(
                "INSERT INTO app_setting (`key`, value_json, updated_at_epoch_millis) " +
                    "VALUES ('appearance.theme', '\"system\"', 1720598400000)",
            )
            insertImage(this, mediaStoreId = 41L, contentUri = "content://media/41")
            insertImage(this, mediaStoreId = 42L, contentUri = "content://media/42")
            insertImage(
                this,
                mediaStoreId = 43L,
                contentUri = "content://media/43",
                availability = "PERMISSION_REVOKED",
            )
            execSQL(
                "INSERT INTO media_sync_checkpoint " +
                    "(volume_name, generation, media_store_version, " +
                    "full_scan_cursor_modified_at_epoch_millis, full_scan_cursor_media_store_id, " +
                    "incremental_high_water_modified_at_epoch_millis, " +
                    "incremental_high_water_media_store_id, completed_at_epoch_millis, " +
                    "full_reconciliation_at_epoch_millis) " +
                    "VALUES ('external', 7, 'v7', 100, 41, 200, 42, 300, 400)",
            )
            execSQL(
                "INSERT INTO media_sync_run " +
                    "(mode, state, current_volume_name, discovered_count, indexed_count, " +
                    "unavailable_count, error_code, error_message, started_at_epoch_millis, " +
                    "updated_at_epoch_millis, completed_at_epoch_millis) " +
                    "VALUES ('RECONCILE', 'COMPLETED', 'external', 3, 2, 1, NULL, NULL, " +
                    "100, 200, 300)",
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            3,
            true,
            MIGRATION_2_3,
        ).use { database ->
            assertEquals(3, queryCount(database, "image"))
            assertEquals(1, queryCount(database, "app_setting"))
            assertEquals(1, queryCount(database, "media_sync_checkpoint"))
            assertEquals(1, queryCount(database, "media_sync_run"))
            database.query(
                "SELECT availability FROM image WHERE media_store_id = 43",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PERMISSION_REVOKED", cursor.getString(0))
            }
            val firstImageId = queryImageLocalId(database, 41L)
            val secondImageId = queryImageLocalId(database, 42L)
            insertAnalysis(database, ANALYSIS_ID, firstImageId)

            try {
                database.execSQL(
                    "INSERT INTO active_image_analysis (image_local_id, analysis_id) VALUES (?, ?)",
                    arrayOf(secondImageId, ANALYSIS_ID),
                )
                throw AssertionError("An analysis cannot become active for another image")
            } catch (_: SQLiteConstraintException) {
                // Expected: the composite FK binds the pointer to the analysis image.
            }

            database.execSQL(
                "INSERT INTO active_image_analysis (image_local_id, analysis_id) VALUES (?, ?)",
                arrayOf(firstImageId, ANALYSIS_ID),
            )
            assertEquals(1, queryCount(database, "active_image_analysis"))
        }
    }

    @Test
    fun migrationOneToThreeRunsTheFullChainAndMatchesFreshCascadeBehavior() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                "INSERT INTO app_setting (`key`, value_json, updated_at_epoch_millis) " +
                    "VALUES ('appearance.theme', '\"system\"', 1720598400000)",
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            3,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
        ).use { migrated ->
            assertEquals(1, queryCount(migrated, "app_setting"))
            assertCanonicalGraphCascadesWithImage(migrated, mediaStoreId = 101L)
        }

        migrationHelper.createDatabase(FRESH_DATABASE, 3).use { fresh ->
            assertCanonicalGraphCascadesWithImage(fresh, mediaStoreId = 202L)
        }
    }

    @Test
    fun legacyCleanupDeletesOnlyLegacyDatabaseAndWritesMarker() = runBlocking {
        context.openOrCreateDatabase(LEGACY_DATABASE, Context.MODE_PRIVATE, null).close()
        val protectedDatabase = context.getDatabasePath("protected.db").apply {
            parentFile?.mkdirs()
            createNewFile()
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        try {
            AppDatabaseFactory.cleanupLegacyDatabaseIfNeeded(context, database)

            assertFalse(context.getDatabasePath(LEGACY_DATABASE).exists())
            assertTrue(protectedDatabase.exists())
            assertNotNull(
                database.appSettingDao().getByKey("maintenance.legacy_database_cleaned"),
            )
        } finally {
            database.close()
            protectedDatabase.delete()
        }
    }

    @Test
    fun legacyCleanupMarkerSkipsDeletion() = runBlocking {
        val legacyFile = temporaryLegacyFile().apply { createNewFile() }
        val cleanupContext = LegacyCleanupContext(context, legacyFile, deleteResult = false)
        val database = openInMemoryDatabase()
        database.appSettingDao().upsert(
            AppSettingEntity(LEGACY_MARKER, "true", 1L),
        )

        try {
            AppDatabaseFactory.cleanupLegacyDatabaseIfNeeded(cleanupContext, database)

            assertTrue(legacyFile.exists())
            assertEquals(0, cleanupContext.deleteCalls)
        } finally {
            database.close()
            legacyFile.delete()
        }
    }

    @Test
    fun legacyCleanupWritesMarkerWhenDatabaseIsAlreadyAbsent() = runBlocking {
        val legacyFile = temporaryLegacyFile()
        val cleanupContext = LegacyCleanupContext(context, legacyFile, deleteResult = false)
        val database = openInMemoryDatabase()

        try {
            AppDatabaseFactory.cleanupLegacyDatabaseIfNeeded(cleanupContext, database)

            assertEquals(0, cleanupContext.deleteCalls)
            assertNotNull(database.appSettingDao().getByKey(LEGACY_MARKER))
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyCleanupFailedDeleteThrowsWithoutWritingMarker() = runBlocking {
        val legacyFile = temporaryLegacyFile().apply { createNewFile() }
        val cleanupContext = LegacyCleanupContext(context, legacyFile, deleteResult = false)
        val database = openInMemoryDatabase()

        try {
            assertCleanupFails(cleanupContext, database)

            assertTrue(legacyFile.exists())
            assertNull(database.appSettingDao().getByKey(LEGACY_MARKER))
        } finally {
            database.close()
            legacyFile.delete()
        }
    }

    @Test
    fun legacyCleanupDeleteExceptionLeavesMarkerAbsent() = runBlocking {
        val legacyFile = temporaryLegacyFile().apply { createNewFile() }
        val cleanupContext = LegacyCleanupContext(
            context,
            legacyFile,
            deleteFailure = IllegalStateException("delete failed"),
        )
        val database = openInMemoryDatabase()

        try {
            assertCleanupFails(cleanupContext, database)

            assertTrue(legacyFile.exists())
            assertNull(database.appSettingDao().getByKey(LEGACY_MARKER))
        } finally {
            database.close()
            legacyFile.delete()
        }
    }

    private fun openInMemoryDatabase(): AppDatabase = Room.inMemoryDatabaseBuilder(
        context,
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun temporaryLegacyFile(): File = File(
        context.cacheDir,
        "legacy-cleanup-${System.nanoTime()}.db",
    )

    private suspend fun assertCleanupFails(
        cleanupContext: Context,
        database: AppDatabase,
    ) {
        var failure: Exception? = null
        try {
            AppDatabaseFactory.cleanupLegacyDatabaseIfNeeded(cleanupContext, database)
        } catch (error: Exception) {
            failure = error
        }
        assertNotNull("Expected cleanup failure", failure)
    }

    private fun insertImage(
        database: SupportSQLiteDatabase,
        mediaStoreId: Long,
        contentUri: String,
        availability: String = "AVAILABLE",
    ) {
        database.execSQL(
            """
                INSERT INTO image (
                    volume_name, media_store_id, content_uri, display_name, mime_type,
                    width, height, size_bytes, captured_at_epoch_millis,
                    added_at_epoch_millis, modified_at_epoch_millis, sort_time_epoch_millis,
                    bucket_id, bucket_name, is_favorite, quick_fingerprint, availability,
                    last_seen_sync_run_id, missing_candidate_since_epoch_millis,
                    missing_observation_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "external",
                mediaStoreId,
                contentUri,
                "photo.jpg",
                "image/jpeg",
                1920,
                1080,
                1_024L,
                null,
                100L,
                200L,
                200L,
                null,
                null,
                0,
                "1024:200",
                availability,
                null,
                null,
                0,
            ),
        )
    }

    private fun insertAnalysis(
        database: SupportSQLiteDatabase,
        analysisId: String,
        imageLocalId: Long,
    ) {
        database.execSQL(
            """
            INSERT INTO image_analysis (
                analysis_id, image_local_id, schema_version, caption, extension_json,
                content_hash, provider_profile_id, model_profile_id,
                protocol_definition_id, prompt_template_id,
                created_at_epoch_millis, completed_at_epoch_millis
            ) VALUES (?, ?, 1, 'caption', NULL, 'HASH', 'provider', 'model',
                'protocol', 'prompt', 10, 20)
            """.trimIndent(),
            arrayOf(analysisId, imageLocalId),
        )
    }

    private fun assertCanonicalGraphCascadesWithImage(
        database: SupportSQLiteDatabase,
        mediaStoreId: Long,
    ) {
        insertImage(
            database,
            mediaStoreId = mediaStoreId,
            contentUri = "content://media/$mediaStoreId",
        )
        val imageLocalId = queryImageLocalId(database, mediaStoreId)
        val analysisId = "123e4567-e89b-12d3-a456-${mediaStoreId.toString().padStart(12, '0')}"
        insertAnalysis(database, analysisId, imageLocalId)
        database.execSQL(
            "INSERT INTO analysis_term " +
                "(analysis_id, kind, normalized_key, display_value, confidence) " +
                "VALUES (?, 'TAG', 'cat', 'Cat', 0.8)",
            arrayOf(analysisId),
        )
        database.execSQL(
            "INSERT INTO active_image_analysis (image_local_id, analysis_id) VALUES (?, ?)",
            arrayOf(imageLocalId, analysisId),
        )
        database.execSQL(
            "INSERT INTO image_user_correction " +
                "(image_local_id, caption_mode, caption_value, revision, updated_at_epoch_millis) " +
                "VALUES (?, 'SET', 'User caption', 1, 30)",
            arrayOf(imageLocalId),
        )
        database.execSQL(
            "INSERT INTO user_term_override " +
                "(image_local_id, kind, normalized_key, action, display_value, revision, " +
                "updated_at_epoch_millis) VALUES (?, 'TAG', 'favorite', 'ADD', 'Favorite', 1, 30)",
            arrayOf(imageLocalId),
        )
        database.execSQL(
            "INSERT INTO effective_image_metadata " +
                "(image_local_id, caption, caption_source, projection_generation, " +
                "updated_at_epoch_millis) VALUES (?, 'User caption', 'USER', 1, 30)",
            arrayOf(imageLocalId),
        )
        database.execSQL(
            "INSERT INTO effective_image_term " +
                "(image_local_id, kind, normalized_key, display_value, source, confidence, " +
                "source_analysis_id) VALUES (?, 'TAG', 'cat', 'Cat', 'AI', 0.8, ?)",
            arrayOf(imageLocalId, analysisId),
        )
        database.execSQL(
            "INSERT INTO analysis_activation_diagnostic " +
                "(analysis_id, code, detail, updated_at_epoch_millis) " +
                "VALUES (?, 'SEARCH_INDEX_LIMIT', 'fixture', 30)",
            arrayOf(analysisId),
        )

        database.execSQL("DELETE FROM image WHERE local_id = ?", arrayOf(imageLocalId))

        listOf(
            "image_analysis",
            "analysis_term",
            "active_image_analysis",
            "image_user_correction",
            "user_term_override",
            "effective_image_metadata",
            "effective_image_term",
            "analysis_activation_diagnostic",
        ).forEach { table -> assertEquals("Orphaned rows in $table", 0, queryCount(database, table)) }
    }

    private fun queryCount(database: SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun queryImageLocalId(database: SupportSQLiteDatabase, mediaStoreId: Long): Long =
        database.query(
            "SELECT local_id FROM image WHERE media_store_id = ?",
            arrayOf(mediaStoreId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        const val TEST_DATABASE = "task-2-migration-test.db"
        const val FRESH_DATABASE = "task-2-fresh-schema-test.db"
        const val LEGACY_DATABASE = "image_ai.db"
        const val LEGACY_MARKER = "maintenance.legacy_database_cleaned"
        const val ANALYSIS_ID = "123e4567-e89b-12d3-a456-426614174000"
    }

    private class LegacyCleanupContext(
        base: Context,
        private val legacyFile: File,
        private val deleteResult: Boolean = true,
        private val deleteFailure: RuntimeException? = null,
    ) : ContextWrapper(base) {
        var deleteCalls: Int = 0
            private set

        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File =
            if (name == LEGACY_DATABASE) legacyFile else super.getDatabasePath(name)

        override fun deleteDatabase(name: String): Boolean {
            check(name == LEGACY_DATABASE) { "Unexpected database deletion: $name" }
            deleteCalls++
            deleteFailure?.let { throw it }
            if (deleteResult) legacyFile.delete()
            return deleteResult
        }
    }
}

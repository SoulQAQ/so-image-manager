package cn.soul2.imageai.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_1_2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun insertImage(
        database: SupportSQLiteDatabase,
        mediaStoreId: Long,
        contentUri: String,
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
                "AVAILABLE",
                null,
                null,
                0,
            ),
        )
    }

    private companion object {
        const val TEST_DATABASE = "task-2-migration-test.db"
        const val LEGACY_DATABASE = "image_ai.db"
    }
}

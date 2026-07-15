package cn.soul2.imageai.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.soul2.imageai.data.db.AppDatabaseMigrations.MIGRATION_3_4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchForeignKeyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @After
    fun cleanUp() {
        context.deleteDatabase(MIGRATED_DATABASE)
    }

    @Test
    fun cleanDatabaseWriterCleanupPreservesSharedLexicalRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            seedSearchGraph(sqlite)

            database.searchIndexDao().deleteImageIndex(FIRST_IMAGE_ID)
            sqlite.execSQL("DELETE FROM image WHERE local_id = ?", arrayOf(FIRST_IMAGE_ID))

            assertEquals(0, count(sqlite, "search_document", "rowid = $FIRST_IMAGE_ID"))
            assertEquals(0, countFtsRow(sqlite, FIRST_IMAGE_ID))
            assertEquals(0, count(sqlite, "image_search_term", "image_local_id = $FIRST_IMAGE_ID"))
            assertEquals(0, count(sqlite, "search_source_chunk", "image_local_id = $FIRST_IMAGE_ID"))
            assertEquals(0, count(sqlite, "search_text_alias_chunk", "image_local_id = $FIRST_IMAGE_ID"))
            assertEquals(2, count(sqlite, "search_gram"))
            assertEquals(1, count(sqlite, "search_term"))
            assertEquals(1, count(sqlite, "search_term_alias"))
            assertEquals(1, count(sqlite, "image_search_term", "image_local_id = $SECOND_IMAGE_ID"))
            assertEquals(0, database.searchIndexDao().deleteOrphanGrams())
        } finally {
            database.close()
        }
    }

    @Test
    fun migratedDatabaseCascadesImageRowsAndMaintenanceRemovesOnlyOrphanGrams() = runBlocking {
        migrationHelper.createDatabase(MIGRATED_DATABASE, 3).close()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, MIGRATED_DATABASE)
            .addMigrations(MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            seedSearchGraph(sqlite)

            sqlite.execSQL("DELETE FROM image WHERE local_id = ?", arrayOf(FIRST_IMAGE_ID))

            assertEquals(0, count(sqlite, "search_document", "rowid = $FIRST_IMAGE_ID"))
            assertEquals(0, countFtsRow(sqlite, FIRST_IMAGE_ID))
            assertEquals(0, count(sqlite, "image_search_term", "image_local_id = $FIRST_IMAGE_ID"))
            assertEquals(0, count(sqlite, "search_source_chunk", "image_local_id = $FIRST_IMAGE_ID"))
            assertEquals(0, count(sqlite, "search_text_alias_chunk", "image_local_id = $FIRST_IMAGE_ID"))
            assertEquals(4, count(sqlite, "search_gram"))
            assertEquals(2, database.searchIndexDao().deleteOrphanGrams())
            assertEquals(2, count(sqlite, "search_gram"))
            assertEquals(1, count(sqlite, "search_term"))
            assertEquals(1, count(sqlite, "search_term_alias"))
            assertEquals(1, count(sqlite, "image_search_term", "image_local_id = $SECOND_IMAGE_ID"))
        } finally {
            database.close()
        }
    }

    private fun seedSearchGraph(database: SupportSQLiteDatabase) {
        insertImage(database, FIRST_IMAGE_ID, 501L)
        insertImage(database, SECOND_IMAGE_ID, 502L)
        listOf(FIRST_IMAGE_ID, SECOND_IMAGE_ID).forEach { imageId ->
            database.execSQL(
                """
                INSERT INTO search_document (
                    rowid, file_name, album, caption, tags, categories, search_tokens, media_text
                ) VALUES (?, ?, 'Album', 'Caption', 'Cat', 'Travel', 'Night', '1920 1080')
                """.trimIndent(),
                arrayOf(imageId, "photo-$imageId.jpg"),
            )
        }
        database.execSQL(
            "INSERT INTO search_term (term_id, normalized_key, display_value, unit_type) " +
                "VALUES (10, 'cat', 'Cat', 'LATIN_DIGIT')",
        )
        database.execSQL(
            "INSERT INTO search_term_alias (alias_id, term_id, alias_type, alias_text) " +
                "VALUES (20, 10, 'FULL', 'mao')",
        )
        listOf(FIRST_IMAGE_ID, SECOND_IMAGE_ID).forEach { imageId ->
            database.execSQL(
                "INSERT INTO image_search_term " +
                    "(image_local_id, term_id, field_mask, ownership, weight) " +
                    "VALUES (?, 10, 8, 'AI', 500)",
                arrayOf(imageId),
            )
        }
        database.execSQL(
            "INSERT INTO search_source_chunk " +
                "(chunk_id, image_local_id, field, ordinal, normalized_text) " +
                "VALUES (30, ?, 'CAPTION', 0, 'caption')",
            arrayOf(FIRST_IMAGE_ID),
        )
        database.execSQL(
            "INSERT INTO search_text_alias_chunk " +
                "(alias_chunk_id, image_local_id, field, alias_type, ordinal, alias_text) " +
                "VALUES (40, ?, 'CAPTION', 'FULL', 0, 'caption')",
            arrayOf(FIRST_IMAGE_ID),
        )
        listOf(
            arrayOf("cat", "TERM", 10),
            arrayOf("mao", "TERM_ALIAS", 20),
            arrayOf("cap", "SOURCE_CHUNK", 30),
            arrayOf("apt", "TEXT_ALIAS_CHUNK", 40),
        ).forEach { values ->
            database.execSQL(
                "INSERT INTO search_gram (gram, owner_type, owner_id) VALUES (?, ?, ?)",
                values,
            )
        }
    }

    private fun insertImage(database: SupportSQLiteDatabase, localId: Long, mediaStoreId: Long) {
        database.execSQL(
            """
            INSERT INTO image (
                local_id, volume_name, media_store_id, content_uri, display_name, mime_type,
                width, height, size_bytes, captured_at_epoch_millis, added_at_epoch_millis,
                modified_at_epoch_millis, sort_time_epoch_millis, bucket_id, bucket_name,
                is_favorite, quick_fingerprint, availability, last_seen_sync_run_id,
                missing_candidate_since_epoch_millis, missing_observation_count
            ) VALUES (?, 'external', ?, ?, 'photo.jpg', 'image/jpeg', 1920, 1080, 1024,
                NULL, 100, 200, 200, NULL, NULL, 0, '1024:200', 'AVAILABLE', NULL, NULL, 0)
            """.trimIndent(),
            arrayOf(localId, mediaStoreId, "content://media/$mediaStoreId"),
        )
    }

    private fun count(
        database: SupportSQLiteDatabase,
        table: String,
        where: String? = null,
    ): Int = database.query(
        "SELECT COUNT(*) FROM `$table`${where?.let { " WHERE $it" }.orEmpty()}",
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun countFtsRow(database: SupportSQLiteDatabase, rowId: Long): Int =
        database.query("SELECT COUNT(*) FROM search_document_fts WHERE rowid = $rowId").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val MIGRATED_DATABASE = "task-2-search-fk-migrated.db"
        const val FIRST_IMAGE_ID = 1L
        const val SECOND_IMAGE_ID = 2L
    }
}

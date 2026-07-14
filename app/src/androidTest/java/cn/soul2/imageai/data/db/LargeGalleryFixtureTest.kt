package cn.soul2.imageai.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeGalleryFixtureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: AppDatabase? = null

    @After
    fun cleanUpDatabase() {
        database?.close()
        database = null
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun fifteenThousandMetadataRowsAreIdempotentOrderedAndPersistentWithoutImageCopies() =
        runBlocking {
            val images = (1L..ROW_COUNT.toLong()).map(::image)
            val firstDatabase = openDatabase()

            firstDatabase.imageDao().upsert(images)
            firstDatabase.imageDao().upsert(
                images.map { image -> image.copy(displayName = "updated-${image.mediaStoreId}.jpg") },
            )

            assertEquals(ROW_COUNT, firstDatabase.imageDao().observeAvailableCount().first())
            val firstPage = loadPage(firstDatabase.imageDao().pagingRecent(), loadSize = 120)
            assertEquals(
                (ROW_COUNT downTo ROW_COUNT - 119).map(Int::toLong),
                firstPage.data.map(ImageEntity::mediaStoreId),
            )
            assertTrue(firstPage.data.all { image -> image.displayName.startsWith("updated-") })
            assertImageTableContainsNoBlobColumns(firstDatabase)

            firstDatabase.close()
            database = null

            val reopened = openDatabase()
            assertEquals(ROW_COUNT, reopened.imageDao().observeAvailableCount().first())
            assertImageTableContainsNoBlobColumns(reopened)
            reopened.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()

            val declaredImageBytes = ROW_COUNT.toLong() * DECLARED_IMAGE_SIZE_BYTES
            val databaseBytes = context.getDatabasePath(DATABASE_NAME).length()
            assertTrue("Fixture unexpectedly stores image payload bytes", databaseBytes > 0L)
            assertTrue(
                "Metadata database is too large to be payload-free: $databaseBytes bytes",
                databaseBytes < declaredImageBytes / 1_000L,
            )
        }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        DATABASE_NAME,
    ).build().also { database = it }

    private suspend fun loadPage(
        source: PagingSource<Int, ImageEntity>,
        loadSize: Int,
    ): PagingSource.LoadResult.Page<Int, ImageEntity> {
        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = loadSize,
                placeholdersEnabled = false,
            ),
        )
        assertTrue(result is PagingSource.LoadResult.Page)
        @Suppress("UNCHECKED_CAST")
        return result as PagingSource.LoadResult.Page<Int, ImageEntity>
    }

    private fun assertImageTableContainsNoBlobColumns(database: AppDatabase) {
        database.openHelper.readableDatabase.query("PRAGMA table_info(`image`)").use { cursor ->
            val typeColumn = cursor.getColumnIndexOrThrow("type")
            val columnTypes = buildList {
                while (cursor.moveToNext()) add(cursor.getString(typeColumn))
            }
            assertFalse(columnTypes.any { type -> type.equals("BLOB", ignoreCase = true) })
        }
    }

    private fun image(id: Long) = ImageEntity(
        volumeName = "external_primary",
        mediaStoreId = id,
        contentUri = "content://media/external_primary/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        width = 4_032,
        height = 3_024,
        sizeBytes = DECLARED_IMAGE_SIZE_BYTES,
        capturedAtEpochMillis = id * 1_000L,
        addedAtEpochMillis = id * 1_000L,
        modifiedAtEpochMillis = id * 1_000L,
        sortTimeEpochMillis = id * 1_000L,
        bucketId = 1L,
        bucketName = "SoIM fixture",
        isFavorite = false,
        quickFingerprint = "metadata-only-$id",
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )

    private companion object {
        const val DATABASE_NAME = "task-6-large-gallery.db"
        const val ROW_COUNT = 15_000
        const val DECLARED_IMAGE_SIZE_BYTES = 4L * 1_024L * 1_024L
    }
}

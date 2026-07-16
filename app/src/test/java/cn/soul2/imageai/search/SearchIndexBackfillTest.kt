package cn.soul2.imageai.search

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.MediaSyncCheckpointEntity
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchIndexBackfillTest {
    private lateinit var database: AppDatabase
    private lateinit var backfill: SearchIndexBackfill
    private lateinit var repository: ImageSearchRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val writer = RoomSearchProjectionWriter(database.searchIndexDao())
        backfill = SearchIndexBackfill(database, writer)
        repository = RoomImageSearchRepository(database.searchIndexDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun startupBackfillMakesUnanalyzedMediaFilenameAndAlbumSearchable() = runBlocking {
        database.imageDao().upsert(listOf(image(displayName = "Holiday Photo.jpg")))

        assertEquals(1, backfill.backfillMissing())
        val result = repository.search(SearchRequest("holiday", 1)).last()

        assertEquals(IMAGE_ID, result.items.first().imageLocalId)
        assertTrue(result.items.first().field in setOf(SearchField.FILE_NAME, SearchField.ALBUM))
        assertEquals(0, backfill.backfillMissing())
    }

    @Test
    fun mediaCommitMarksOldDocumentDirtyAndReindexPublishesNewMetadata() = runBlocking {
        database.imageDao().upsert(listOf(image(displayName = "Old Name.jpg")))
        backfill.backfillMissing()
        assertEquals(1, repository.search(SearchRequest("old", 1)).last().items.size)

        val persisted = database.mediaSyncDao().commitBatch(
            images = listOf(image(localId = 0, displayName = "New Name.jpg")),
            checkpoint = MediaSyncCheckpointEntity(
                volumeName = "external",
                generation = null,
                mediaStoreVersion = null,
                fullScanCursorModifiedAtEpochMillis = null,
                fullScanCursorMediaStoreId = null,
                incrementalHighWaterModifiedAtEpochMillis = null,
                incrementalHighWaterMediaStoreId = null,
                completedAtEpochMillis = null,
                fullReconciliationAtEpochMillis = null,
            ),
            run = MediaSyncRunEntity(
                runId = 1,
                mode = "INCREMENTAL",
                state = "RUNNING",
                currentVolumeName = "external",
                discoveredCount = 1,
                indexedCount = 1,
                unavailableCount = 0,
                errorCode = null,
                errorMessage = null,
                startedAtEpochMillis = 1,
                updatedAtEpochMillis = 2,
                completedAtEpochMillis = null,
            ),
        )
        assertEquals(0, rawCount("search_document"))

        backfill.reindexCommitted(persisted)

        assertTrue(repository.search(SearchRequest("old", 2)).last().items.isEmpty())
        assertEquals(IMAGE_ID, repository.search(SearchRequest("new", 3)).last().items.first().imageLocalId)
    }

    private fun rawCount(table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun image(
        localId: Long = IMAGE_ID,
        displayName: String,
    ) = ImageEntity(
        localId = localId,
        volumeName = "external",
        mediaStoreId = 101,
        contentUri = "content://media/101",
        displayName = displayName,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        sizeBytes = 1_000,
        capturedAtEpochMillis = null,
        addedAtEpochMillis = 1,
        modifiedAtEpochMillis = 2,
        sortTimeEpochMillis = 2,
        bucketId = 1,
        bucketName = "Trips",
        isFavorite = false,
        quickFingerprint = "1000:2",
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )

    private companion object {
        const val IMAGE_ID = 1L
    }
}

package cn.soul2.imageai.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GalleryReadStatsTest {
    private lateinit var database: AppDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun unavailableCountTracksEveryNonAvailableClassification() = runTest {
        database.imageDao().upsert(
            listOf(
                image(1L, ImageAvailability.AVAILABLE),
                image(2L, ImageAvailability.PERMISSION_REVOKED),
                image(3L, ImageAvailability.SELECTION_REMOVED),
                image(4L, ImageAvailability.VOLUME_UNMOUNTED),
                image(5L, ImageAvailability.MEDIA_MISSING),
                image(6L, ImageAvailability.TRANSIENT_IO),
            ),
        )

        assertEquals(5, database.imageDao().observeUnavailableCount().first())
    }

    @Test
    fun latestCompletionSurvivesNewerActiveRun() = runTest {
        database.mediaSyncDao().upsertRun(syncRun(1L, "SUCCEEDED", completedAt = 300L))
        database.mediaSyncDao().upsertRun(syncRun(2L, "RUNNING", completedAt = null))

        assertEquals(300L, database.mediaSyncDao().observeLastCompletedAt().first())
    }

    private fun image(id: Long, availability: ImageAvailability) = ImageEntity(
        localId = id,
        volumeName = "external_primary",
        mediaStoreId = id,
        contentUri = "content://media/external_primary/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        sizeBytes = 1_024L,
        capturedAtEpochMillis = id,
        addedAtEpochMillis = id,
        modifiedAtEpochMillis = id,
        sortTimeEpochMillis = id,
        bucketId = 1L,
        bucketName = "相机",
        isFavorite = false,
        quickFingerprint = "fingerprint-$id",
        availability = availability,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )

    private fun syncRun(runId: Long, state: String, completedAt: Long?) = MediaSyncRunEntity(
        runId = runId,
        mode = "INCREMENTAL",
        state = state,
        currentVolumeName = null,
        discoveredCount = 1,
        indexedCount = 1,
        unavailableCount = 0,
        errorCode = null,
        errorMessage = null,
        startedAtEpochMillis = 100L,
        updatedAtEpochMillis = completedAt ?: 400L,
        completedAtEpochMillis = completedAt,
    )
}

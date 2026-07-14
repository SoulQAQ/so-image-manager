package cn.soul2.imageai.data.db

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.MediaSyncCheckpointEntity
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaIndexDaoTest {
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
    fun imageDaoRepeatedScanKeepsLocalIdAndLastDuplicateWins() = runBlocking {
        val dao = database.imageDao()
        dao.upsert(listOf(image(displayName = "first")))
        val original = loadAll(dao.pagingAll()).single()

        dao.upsert(
            listOf(
                image(displayName = "second"),
                image(displayName = "last"),
            ),
        )

        val stored = loadAll(dao.pagingAll()).single()
        assertEquals(original.localId, stored.localId)
        assertEquals("last", stored.displayName)
        assertEquals("fingerprint-last", stored.quickFingerprint)
    }

    @Test
    fun mediaSyncCommitBatchUpdatesImageCheckpointAndRunTogether() = runBlocking {
        val dao = database.mediaSyncDao()
        dao.commitBatch(
            images = listOf(image(displayName = "first")),
            checkpoint = checkpoint(generation = 1L),
            run = run(state = "RUNNING"),
        )
        val original = loadAll(database.imageDao().pagingAll()).single()

        dao.commitBatch(
            images = listOf(
                image(displayName = "second"),
                image(displayName = "last"),
            ),
            checkpoint = checkpoint(generation = 2L),
            run = run(state = "COMPLETED"),
        )

        val stored = loadAll(database.imageDao().pagingAll()).single()
        assertEquals(original.localId, stored.localId)
        assertEquals("last", stored.displayName)
        assertEquals(2L, dao.getCheckpoint(VOLUME)?.generation)
        assertEquals("COMPLETED", dao.observeCurrentRun().first()?.state)
    }

    @Test
    fun commitBatchRollsBackImageAndCheckpointWhenRunWriteFails() = runBlocking {
        val dao = database.mediaSyncDao()
        dao.commitBatch(emptyList(), checkpoint(generation = 1L), run(state = "RUNNING"))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_sync_run
            BEFORE INSERT ON media_sync_run
            BEGIN
                SELECT RAISE(ABORT, 'run write rejected');
            END
            """.trimIndent(),
        )

        assertFails {
            dao.commitBatch(
                images = listOf(image(displayName = "must-roll-back")),
                checkpoint = checkpoint(generation = 2L),
                run = run(runId = 2L, state = "COMPLETED"),
            )
        }

        assertTrue(loadAll(database.imageDao().pagingAll()).isEmpty())
        assertEquals(1L, dao.getCheckpoint(VOLUME)?.generation)
        assertEquals(RUN_ID, dao.observeCurrentRun().first()?.runId)
    }

    @Test
    fun imageWriteFailureEscapesWithoutAdvancingCheckpointOrRun() = runBlocking {
        val dao = database.mediaSyncDao()
        dao.commitBatch(emptyList(), checkpoint(generation = 1L), run(state = "RUNNING"))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_image
            BEFORE INSERT ON image
            BEGIN
                SELECT RAISE(ABORT, 'image write rejected');
            END
            """.trimIndent(),
        )

        assertFails {
            dao.commitBatch(
                images = listOf(image(displayName = "rejected")),
                checkpoint = checkpoint(generation = 2L),
                run = run(state = "COMPLETED"),
            )
        }

        assertTrue(loadAll(database.imageDao().pagingAll()).isEmpty())
        assertEquals(1L, dao.getCheckpoint(VOLUME)?.generation)
        assertEquals("RUNNING", dao.observeCurrentRun().first()?.state)
    }

    @Test
    fun pagingSourcesKeepCrossVolumeTiesStableAcrossPages() = runBlocking {
        database.imageDao().upsert(
            listOf(
                image(volumeName = "volume-a", displayName = "a"),
                image(volumeName = "volume-b", displayName = "b"),
            ),
        )

        listOf(database.imageDao().pagingAll(), database.imageDao().pagingRecent())
            .forEach { source ->
                val first = loadPage(source, key = null)
                val second = loadPage(source, key = requireNotNull(first.nextKey))
                val volumes = first.data.plus(second.data).map(ImageEntity::volumeName)

                assertEquals(listOf("volume-b", "volume-a"), volumes)
                assertEquals(2, volumes.distinct().size)
            }
    }

    private suspend fun loadAll(
        source: PagingSource<Int, ImageEntity>,
    ): List<ImageEntity> = loadPage(source, key = null, loadSize = 20).data

    private suspend fun loadPage(
        source: PagingSource<Int, ImageEntity>,
        key: Int?,
        loadSize: Int = 1,
    ): PagingSource.LoadResult.Page<Int, ImageEntity> {
        val params: PagingSource.LoadParams<Int> = if (key == null) {
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = loadSize,
                placeholdersEnabled = false,
            )
        } else {
            PagingSource.LoadParams.Append(
                key = key,
                loadSize = loadSize,
                placeholdersEnabled = false,
            )
        }
        val result = source.load(params)
        assertTrue(result is PagingSource.LoadResult.Page)
        @Suppress("UNCHECKED_CAST")
        return result as PagingSource.LoadResult.Page<Int, ImageEntity>
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failure: Exception? = null
        try {
            block()
        } catch (error: Exception) {
            failure = error
        }
        assertTrue("Expected database write to fail", failure != null)
    }

    private fun image(
        volumeName: String = VOLUME,
        mediaStoreId: Long = MEDIA_STORE_ID,
        displayName: String,
    ) = ImageEntity(
        volumeName = volumeName,
        mediaStoreId = mediaStoreId,
        contentUri = "content://media/$volumeName/$mediaStoreId",
        displayName = displayName,
        mimeType = "image/jpeg",
        width = 1920,
        height = 1080,
        sizeBytes = 1_024L,
        capturedAtEpochMillis = null,
        addedAtEpochMillis = SORT_TIME,
        modifiedAtEpochMillis = SORT_TIME,
        sortTimeEpochMillis = SORT_TIME,
        bucketId = null,
        bucketName = null,
        isFavorite = false,
        quickFingerprint = "fingerprint-$displayName",
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = RUN_ID,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )

    private fun checkpoint(generation: Long) = MediaSyncCheckpointEntity(
        volumeName = VOLUME,
        generation = generation,
        mediaStoreVersion = "version-$generation",
        fullScanCursorModifiedAtEpochMillis = SORT_TIME,
        fullScanCursorMediaStoreId = MEDIA_STORE_ID,
        incrementalHighWaterModifiedAtEpochMillis = SORT_TIME,
        incrementalHighWaterMediaStoreId = MEDIA_STORE_ID,
        completedAtEpochMillis = SORT_TIME,
        fullReconciliationAtEpochMillis = null,
    )

    private fun run(
        runId: Long = RUN_ID,
        state: String,
    ) = MediaSyncRunEntity(
        runId = runId,
        mode = "INCREMENTAL",
        state = state,
        currentVolumeName = VOLUME,
        discoveredCount = 1,
        indexedCount = 1,
        unavailableCount = 0,
        errorCode = null,
        errorMessage = null,
        startedAtEpochMillis = SORT_TIME,
        updatedAtEpochMillis = SORT_TIME,
        completedAtEpochMillis = if (state == "COMPLETED") SORT_TIME else null,
    )

    private companion object {
        const val VOLUME = "external"
        const val MEDIA_STORE_ID = 42L
        const val RUN_ID = 1L
        const val SORT_TIME = 200L
    }
}

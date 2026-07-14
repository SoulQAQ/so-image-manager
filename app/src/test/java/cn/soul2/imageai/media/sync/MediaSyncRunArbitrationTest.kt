package cn.soul2.imageai.media.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.store.MediaStoreCursor
import cn.soul2.imageai.media.store.MediaStoreGateway
import cn.soul2.imageai.media.store.MediaStoreImage
import cn.soul2.imageai.media.store.MediaStorePage
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaSyncRunArbitrationTest {
    private lateinit var database: AppDatabase
    private lateinit var store: RoomMediaSyncStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomMediaSyncStore(database.mediaSyncDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun runningRunWinsWhileRequestedModesAreDurablyQueuedAndDeduplicated() = runTest {
        val reconciliation = requireNotNull(
            store.enqueueAndClaimRun(SyncMode.RECONCILE, nowEpochMillis = 1_000L),
        )
        assertEquals(SyncRunState.RUNNING, reconciliation.state)

        assertEquals(
            reconciliation.runId,
            store.enqueueAndClaimRun(SyncMode.INCREMENTAL, 2_000L)?.runId,
        )
        assertEquals(
            reconciliation.runId,
            store.enqueueAndClaimRun(SyncMode.INCREMENTAL, 3_000L)?.runId,
        )
        assertEquals(
            reconciliation.runId,
            store.enqueueAndClaimRun(SyncMode.RECONCILE, 4_000L)?.runId,
        )
        assertEquals(
            listOf(
                RunState(SyncMode.RECONCILE, SyncRunState.RUNNING),
                RunState(SyncMode.INCREMENTAL, SyncRunState.QUEUED),
                RunState(SyncMode.RECONCILE, SyncRunState.QUEUED),
            ),
            runStates(),
        )

        store.updateRun(reconciliation.succeeded(5_000L))
        val incremental = requireNotNull(store.enqueueAndClaimRun(null, 6_000L))
        assertEquals(SyncMode.INCREMENTAL, incremental.mode)
        assertEquals(1, runStates().count { it.state == SyncRunState.RUNNING })

        store.updateRun(incremental.succeeded(7_000L))
        val queuedReconciliation = requireNotNull(store.enqueueAndClaimRun(null, 8_000L))
        assertEquals(SyncMode.RECONCILE, queuedReconciliation.mode)
        assertEquals(1, runStates().count { it.state == SyncRunState.RUNNING })

        store.updateRun(queuedReconciliation.succeeded(9_000L))
        assertNull(store.enqueueAndClaimRun(null, 10_000L))
        assertEquals(0, runStates().count { it.state == SyncRunState.RUNNING })
    }

    @Test
    fun retryDoesNotResumePausedReconciliationSupersededByNewerSuccessfulRun() = runTest {
        val seedRun = store.startRun(SyncMode.INITIAL, 100L)
        store.commitBatch(
            images = listOf(image(1L)),
            checkpoint = checkpoint(generation = 1L, completedAtEpochMillis = 100L),
            run = seedRun,
        )
        store.finishRun(seedRun, setOf(VOLUME), GalleryAccessState.Full, 150L)

        val staleReconciliation = store.startRun(SyncMode.RECONCILE, 200L)
        store.updateRun(staleReconciliation.pausedError(250L, IOException("paused")))

        val newerIncremental = store.startRun(SyncMode.INCREMENTAL, 300L)
        val newerProgress = newerIncremental.withPage(VOLUME, imageCount = 0, 400L)
        store.commitBatch(
            images = emptyList(),
            checkpoint = checkpoint(generation = 77L, completedAtEpochMillis = 400L),
            run = newerProgress,
        )
        store.finishRun(newerProgress, setOf(VOLUME), GalleryAccessState.Full, 450L)

        val gateway = EmptyGateway(observedGeneration = 77L)
        val engine = MediaSyncEngine(
            gateway = gateway,
            store = store,
            permissionSource = MediaSyncPermissionSource { GalleryAccessState.Full },
            clock = SyncClock { 500L },
        )

        val result = engine.retryPausedSlice()

        assertNull(result)
        assertEquals(0, gateway.readCount)
        assertEquals(SyncRunState.PAUSED_ERROR, runState(staleReconciliation.runId))
        assertEquals(SyncRunState.SUCCEEDED, runState(newerIncremental.runId))
        assertEquals(77L, store.checkpoint(VOLUME)?.generation)
        assertEquals(400L, store.checkpoint(VOLUME)?.completedAtEpochMillis)
        assertNull(missingCandidateSince(1L))
    }

    @Test
    fun retryReturnsExistingRunningRunAndPreservesPausedAndQueuedRows() = runTest {
        val paused = store.startRun(SyncMode.RECONCILE, 1_000L)
        store.updateRun(paused.pausedError(1_100L, IOException("paused")))
        val running = store.startRun(SyncMode.INITIAL, 1_200L)
        assertEquals(
            running.runId,
            store.enqueueAndClaimRun(SyncMode.INCREMENTAL, 1_300L)?.runId,
        )

        val claimed = requireNotNull(store.claimRetryRun(1_400L))

        assertEquals(running.runId, claimed.runId)
        assertEquals(
            listOf(
                RunState(SyncMode.RECONCILE, SyncRunState.PAUSED_ERROR),
                RunState(SyncMode.INITIAL, SyncRunState.RUNNING),
                RunState(SyncMode.INCREMENTAL, SyncRunState.QUEUED),
            ),
            runStates(),
        )
    }

    @Test
    fun retryResumesLatestPausedRunWhenItHasNotBeenSuperseded() = runTest {
        val paused = store.startRun(SyncMode.RECONCILE, 2_000L)
        store.updateRun(paused.pausedError(2_100L, IOException("paused")))

        val claimed = requireNotNull(store.claimRetryRun(2_200L))

        assertEquals(paused.runId, claimed.runId)
        assertEquals(SyncRunState.RUNNING, claimed.state)
        assertNull(claimed.errorCode)
        assertNull(claimed.errorMessage)
        assertEquals(1, runStates().count { it.state == SyncRunState.RUNNING })
    }

    @Test
    fun completedRetryLeavesQueuedModeForTheGenericCoordinator() = runTest {
        val initial = requireNotNull(
            store.enqueueAndClaimRun(SyncMode.INITIAL, nowEpochMillis = 3_000L),
        )
        assertEquals(
            initial.runId,
            store.enqueueAndClaimRun(SyncMode.INCREMENTAL, 3_100L)?.runId,
        )
        store.updateRun(initial.succeeded(3_200L))
        val paused = store.startRun(SyncMode.RECONCILE, 3_300L)
        store.updateRun(paused.pausedError(3_400L, IOException("paused")))

        val retried = requireNotNull(store.claimRetryRun(3_500L))
        assertEquals(paused.runId, retried.runId)
        store.updateRun(retried.succeeded(3_600L))

        val coordinated = requireNotNull(store.enqueueAndClaimRun(null, 3_700L))
        assertEquals(SyncMode.INCREMENTAL, coordinated.mode)
        assertEquals(SyncRunState.RUNNING, coordinated.state)
        assertEquals(1, runStates().count { it.state == SyncRunState.RUNNING })
    }

    private fun runStates(): List<RunState> {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT mode, state FROM media_sync_run ORDER BY run_id",
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        RunState(
                            mode = SyncMode.valueOf(it.getString(0)),
                            state = SyncRunState.valueOf(it.getString(1)),
                        ),
                    )
                }
            }
        }
    }

    private fun runState(runId: Long): SyncRunState {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT state FROM media_sync_run WHERE run_id = $runId",
        )
        return cursor.use {
            check(it.moveToFirst())
            SyncRunState.valueOf(it.getString(0))
        }
    }

    private fun missingCandidateSince(mediaStoreId: Long): Long? {
        val cursor = database.openHelper.readableDatabase.query(
            """
            SELECT missing_candidate_since_epoch_millis
            FROM image
            WHERE volume_name = '$VOLUME' AND media_store_id = $mediaStoreId
            """.trimIndent(),
        )
        return cursor.use {
            check(it.moveToFirst())
            if (it.isNull(0)) null else it.getLong(0)
        }
    }

    private class EmptyGateway(
        private val observedGeneration: Long,
    ) : MediaStoreGateway {
        var readCount = 0

        override fun externalVolumes(): Set<String> = setOf(VOLUME)

        override fun readPage(
            volume: String,
            mode: SyncMode,
            cursor: MediaStoreCursor?,
            limit: Int,
        ): MediaStorePage {
            readCount++
            return MediaStorePage(
                images = emptyList(),
                nextCursor = cursor,
                hasMore = false,
                observedGeneration = observedGeneration,
                observedVersion = "v1",
            )
        }
    }

    private data class RunState(
        val mode: SyncMode,
        val state: SyncRunState,
    )

    private companion object {
        const val VOLUME = "external_primary"

        fun checkpoint(
            generation: Long,
            completedAtEpochMillis: Long,
        ) = SyncCheckpoint(
            volumeName = VOLUME,
            generation = generation,
            mediaStoreVersion = "v1",
            cursorModifiedAtEpochMillis = null,
            cursorMediaStoreId = Long.MAX_VALUE,
            completedAtEpochMillis = completedAtEpochMillis,
            fullReconciliationAtEpochMillis = null,
        )

        fun image(id: Long) = MediaStoreImage(
            volumeName = VOLUME,
            mediaStoreId = id,
            contentUri = "content://media/$VOLUME/images/media/$id",
            displayName = "$id.jpg",
            mimeType = "image/jpeg",
            width = 100,
            height = 100,
            sizeBytes = id * 10L,
            capturedAtEpochMillis = id * 1_000L,
            addedAtEpochMillis = id * 1_000L,
            modifiedAtEpochMillis = id * 1_000L,
            bucketId = 1L,
            bucketName = "Camera",
            isFavorite = false,
            generationModified = id,
        )
    }
}

package cn.soul2.imageai.media.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.store.MediaStoreCursor
import cn.soul2.imageai.media.store.MediaStoreGateway
import cn.soul2.imageai.media.store.MediaStoreImage
import cn.soul2.imageai.media.store.MediaStorePage
import cn.soul2.imageai.ui.screens.TaskSyncStatus
import cn.soul2.imageai.ui.screens.TasksViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun fiveTransientAttemptsPauseOneDurableRunWithoutQueuedOrphan() = runTest {
        val engine = MediaSyncEngine(
            gateway = ThrowingGateway(IOException("temporary")),
            store = store,
            permissionSource = MediaSyncPermissionSource { GalleryAccessState.Full },
            clock = SyncClock { 4_000L },
        )

        repeat(SyncPolicy.MAX_WORKER_ATTEMPTS) { attempt ->
            val result = if (attempt == 0) {
                engine.runNextSlice(SyncMode.INITIAL)
            } else {
                requireNotNull(engine.continueNextSlice())
            }
            val retry = result as SliceResult.Retry
            if (MediaSyncWorkerPolicy.directive(retry, attempt) == WorkerDirective.PAUSE_ERROR) {
                engine.pauseAfterRetries(retry.error)
            }
        }

        val states = database.openHelper.readableDatabase.query(
            "SELECT state FROM media_sync_run ORDER BY run_id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(listOf(SyncRunState.PAUSED_ERROR.name), states)

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val persistedRun = database.mediaSyncDao().observeCurrentRun().first()
            val viewModel = TasksViewModel(
                syncRuns = MutableStateFlow(persistedRun),
                lastCompletedAt = MutableStateFlow(null),
                onRetry = {},
            )
            backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
            runCurrent()

            assertEquals(TaskSyncStatus.PausedError, viewModel.uiState.value.status)
            assertEquals(true, viewModel.uiState.value.canRetry)
            assertEquals(SyncRunState.PAUSED_ERROR.name, database.mediaSyncDao().observeCurrentRun().first()?.state)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun onlySuccessfulInitialOrReconciliationEstablishesScanBaseline() = runTest {
        val incomplete = store.startRun(SyncMode.INITIAL, 100L)
        val incompleteProgress = incomplete.withPage(VOLUME, imageCount = 1, 110L)
        store.commitBatch(
            images = listOf(image(1L)),
            checkpoint = checkpoint(generation = 1L, completedAtEpochMillis = null),
            run = incompleteProgress,
        )
        store.pauseForPermission(incompleteProgress, 120L, SecurityException("revoked"))

        assertFalse(store.hasPersistedScanBaseline())

        database.clearAllTables()
        val emptyInitial = store.startRun(SyncMode.INITIAL, 200L)
        store.finishRun(emptyInitial, emptySet(), GalleryAccessState.Full, 210L)
        assertTrue(store.hasPersistedScanBaseline())

        database.clearAllTables()
        val completedInitial = store.startRun(SyncMode.INITIAL, 300L)
        val completedProgress = completedInitial.withPage(VOLUME, imageCount = 1, 310L)
        store.commitBatch(
            images = listOf(image(2L)),
            checkpoint = checkpoint(generation = 2L, completedAtEpochMillis = 310L),
            run = completedProgress,
        )
        store.finishRun(
            completedProgress,
            setOf(VOLUME),
            GalleryAccessState.Full,
            320L,
        )
        assertTrue(store.hasPersistedScanBaseline())

        database.clearAllTables()
        val reconciliation = store.startRun(SyncMode.RECONCILE, 400L)
        store.finishRun(reconciliation, emptySet(), GalleryAccessState.Full, 410L)
        assertTrue(store.hasPersistedScanBaseline())

        database.clearAllTables()
        val incremental = store.startRun(SyncMode.INCREMENTAL, 500L)
        store.finishRun(incremental, emptySet(), GalleryAccessState.Full, 510L)
        assertFalse(store.hasPersistedScanBaseline())
    }

    @Test
    fun passiveAccessResumesPermissionPausedInitialWithoutCreatingIncrementalRun() = runTest {
        val first = image(11L)
        val second = image(12L)
        val gateway = ScriptedGateway(
            ArrayDeque(
                listOf(
                    MediaStorePage(
                        images = listOf(first),
                        nextCursor = cursorFor(first),
                        hasMore = true,
                        observedGeneration = 12L,
                        observedVersion = "v1",
                    ),
                    MediaStorePage(
                        images = listOf(second),
                        nextCursor = cursorFor(second),
                        hasMore = false,
                        observedGeneration = 12L,
                        observedVersion = "v1",
                    ),
                ),
            ),
        )
        val permission = MutablePermissionSource(GalleryAccessState.Full)
        val engine = MediaSyncEngine(
            gateway = gateway,
            store = store,
            permissionSource = permission,
            clock = SyncClock { 1_000L },
        )
        val firstSlice = engine.runSlice(
            mode = SyncMode.INITIAL,
            volume = VOLUME,
            maxItems = 1,
        )
        val initialRunId = firstSlice.run.runId
        assertTrue(firstSlice is SliceResult.More)

        permission.access = GalleryAccessState.Denied(canRequestAgain = true)
        assertTrue(engine.runSlice(SyncMode.INITIAL, VOLUME) is SliceResult.PausedPermission)
        assertFalse(store.hasPersistedScanBaseline())

        val backend = RecordingSyncWorkBackend()
        val coordinator = GallerySyncAccessCoordinator(
            store = store,
            scheduler = MediaSyncScheduler(backend),
        )
        permission.access = GalleryAccessState.Full
        coordinator.onAccessAvailable(GalleryAccessState.Full)

        assertEquals(
            listOf(
                ScheduledImmediate(
                    ImmediateSyncWork.Coordinator,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                ),
            ),
            backend.immediate,
        )

        val completed = requireNotNull(engine.continueNextSlice())
        assertTrue(completed is SliceResult.Completed)
        assertEquals(initialRunId, completed.run.runId)
        assertEquals(
            listOf(RunState(SyncMode.INITIAL, SyncRunState.SUCCEEDED)),
            runStates(),
        )
        assertEquals(listOf(11L, 12L), indexedMediaStoreIds())
    }

    @Test
    fun observerIncrementalDrainsAfterPermissionPausedInitialCompletes() = runTest {
        val first = image(21L)
        val second = image(22L)
        val third = image(23L)
        val gateway = ScriptedGateway(
            ArrayDeque(
                listOf(
                    MediaStorePage(
                        images = listOf(first),
                        nextCursor = cursorFor(first),
                        hasMore = true,
                        observedGeneration = 23L,
                        observedVersion = "v1",
                    ),
                    MediaStorePage(
                        images = listOf(second),
                        nextCursor = cursorFor(second),
                        hasMore = false,
                        observedGeneration = 23L,
                        observedVersion = "v1",
                    ),
                    MediaStorePage(
                        images = listOf(third),
                        nextCursor = cursorFor(third),
                        hasMore = false,
                        observedGeneration = 23L,
                        observedVersion = "v1",
                    ),
                ),
            ),
        )
        val permission = MutablePermissionSource(GalleryAccessState.Full)
        val engine = MediaSyncEngine(
            gateway = gateway,
            store = store,
            permissionSource = permission,
            clock = SyncClock { 1_100L },
        )
        val firstSlice = engine.runSlice(
            mode = SyncMode.INITIAL,
            volume = VOLUME,
            maxItems = 1,
        )
        val initialRunId = firstSlice.run.runId
        assertTrue(firstSlice is SliceResult.More)

        permission.access = GalleryAccessState.Denied(canRequestAgain = true)
        val paused = engine.runSlice(SyncMode.INITIAL, VOLUME)
        assertTrue(paused is SliceResult.PausedPermission)
        val observerRequest = SyncMode.INCREMENTAL

        permission.access = GalleryAccessState.Full
        val resumed = engine.runNextSlice(observerRequest)

        assertTrue(resumed is SliceResult.Completed)
        assertEquals(initialRunId, resumed.run.runId)
        assertEquals(SyncMode.INITIAL, resumed.run.mode)
        assertEquals(
            listOf(
                RunState(SyncMode.INITIAL, SyncRunState.SUCCEEDED),
                RunState(SyncMode.INCREMENTAL, SyncRunState.QUEUED),
            ),
            runStates(),
        )

        val drained = requireNotNull(engine.continueNextSlice())

        assertTrue(drained is SliceResult.Completed)
        assertEquals(SyncMode.INCREMENTAL, drained.run.mode)
        assertEquals(
            listOf(
                RunState(SyncMode.INITIAL, SyncRunState.SUCCEEDED),
                RunState(SyncMode.INCREMENTAL, SyncRunState.SUCCEEDED),
            ),
            runStates(),
        )
        assertEquals(
            listOf(SyncMode.INITIAL, SyncMode.INITIAL, SyncMode.INCREMENTAL),
            gateway.readModes,
        )
        assertEquals(listOf(21L, 22L, 23L), indexedMediaStoreIds())
    }

    @Test
    fun explicitReconciliationDrainsAfterPermissionPausedInitialCompletes() = runTest {
        val first = image(31L)
        val second = image(32L)
        val third = image(33L)
        val gateway = ScriptedGateway(
            ArrayDeque(
                listOf(
                    MediaStorePage(
                        images = listOf(first),
                        nextCursor = cursorFor(first),
                        hasMore = true,
                        observedGeneration = 33L,
                        observedVersion = "v1",
                    ),
                    MediaStorePage(
                        images = listOf(second),
                        nextCursor = cursorFor(second),
                        hasMore = false,
                        observedGeneration = 33L,
                        observedVersion = "v1",
                    ),
                    MediaStorePage(
                        images = listOf(first, second, third),
                        nextCursor = cursorFor(third),
                        hasMore = false,
                        observedGeneration = 33L,
                        observedVersion = "v1",
                    ),
                ),
            ),
        )
        val permission = MutablePermissionSource(GalleryAccessState.Full)
        val engine = MediaSyncEngine(
            gateway = gateway,
            store = store,
            permissionSource = permission,
            clock = SyncClock { 1_200L },
        )
        val firstSlice = engine.runSlice(
            mode = SyncMode.INITIAL,
            volume = VOLUME,
            maxItems = 1,
        )
        val initialRunId = firstSlice.run.runId
        assertTrue(firstSlice is SliceResult.More)

        permission.access = GalleryAccessState.Denied(canRequestAgain = true)
        val paused = engine.runSlice(SyncMode.INITIAL, VOLUME)
        assertTrue(paused is SliceResult.PausedPermission)
        val explicitRequest = SyncMode.RECONCILE

        permission.access = GalleryAccessState.Full
        val resumed = engine.runNextSlice(explicitRequest)

        assertTrue(resumed is SliceResult.Completed)
        assertEquals(initialRunId, resumed.run.runId)
        assertEquals(SyncMode.INITIAL, resumed.run.mode)
        assertEquals(
            listOf(
                RunState(SyncMode.INITIAL, SyncRunState.SUCCEEDED),
                RunState(SyncMode.RECONCILE, SyncRunState.QUEUED),
            ),
            runStates(),
        )

        val drained = requireNotNull(engine.continueNextSlice())

        assertTrue(drained is SliceResult.Completed)
        assertEquals(SyncMode.RECONCILE, drained.run.mode)
        assertEquals(
            listOf(
                RunState(SyncMode.INITIAL, SyncRunState.SUCCEEDED),
                RunState(SyncMode.RECONCILE, SyncRunState.SUCCEEDED),
            ),
            runStates(),
        )
        assertEquals(
            listOf(SyncMode.INITIAL, SyncMode.INITIAL, SyncMode.RECONCILE),
            gateway.readModes,
        )
        assertEquals(listOf(31L, 32L, 33L), indexedMediaStoreIds())
    }

    @Test
    fun explicitSelectionChangeSchedulesDurableReconciliation() = runTest {
        val backend = RecordingSyncWorkBackend()
        val coordinator = GallerySyncAccessCoordinator(
            store = store,
            scheduler = MediaSyncScheduler(backend),
        )

        coordinator.onExplicitSelectionChanged(GalleryAccessState.Partial)

        assertEquals(
            listOf(
                ScheduledImmediate(
                    ImmediateSyncWork.RequestedMode(SyncMode.RECONCILE),
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                ),
            ),
            backend.immediate,
        )
    }

    @Test
    fun passiveRecreationWithBaselineRequestsIncrementalWithoutResumingPausedError() = runTest {
        val initial = store.startRun(SyncMode.INITIAL, 2_000L)
        store.finishRun(initial, emptySet(), GalleryAccessState.Full, 2_100L)
        val pausedError = store.startRun(SyncMode.RECONCILE, 2_200L)
        store.updateRun(pausedError.pausedError(2_300L, IOException("user retry required")))
        val backend = RecordingSyncWorkBackend()
        val coordinator = GallerySyncAccessCoordinator(
            store = store,
            scheduler = MediaSyncScheduler(backend),
        )

        coordinator.onAccessAvailable(GalleryAccessState.Full)

        assertEquals(
            listOf(
                ScheduledImmediate(
                    ImmediateSyncWork.RequestedMode(SyncMode.INCREMENTAL),
                    ExistingWorkPolicy.KEEP,
                ),
            ),
            backend.immediate,
        )
        assertEquals(SyncRunState.PAUSED_ERROR, runState(pausedError.runId))
    }

    @Test
    fun passiveAccessRequestsInitialWithoutResumingPausedErrorWhenBaselineIsAbsent() = runTest {
        val pausedError = store.startRun(SyncMode.INITIAL, 2_400L)
        store.updateRun(pausedError.pausedError(2_500L, IOException("user retry required")))
        val backend = RecordingSyncWorkBackend()
        val coordinator = GallerySyncAccessCoordinator(
            store = store,
            scheduler = MediaSyncScheduler(backend),
        )

        coordinator.onAccessAvailable(GalleryAccessState.Full)

        assertEquals(
            listOf(
                ScheduledImmediate(
                    ImmediateSyncWork.RequestedMode(SyncMode.INITIAL),
                    ExistingWorkPolicy.KEEP,
                ),
            ),
            backend.immediate,
        )
        assertEquals(SyncRunState.PAUSED_ERROR, runState(pausedError.runId))
    }

    @Test
    fun historicalPermissionPauseSupersededBySuccessfulBaselineIsNotResumed() = runTest {
        val pausedPermission = store.startRun(SyncMode.INITIAL, 2_600L)
        store.pauseForPermission(
            pausedPermission,
            2_700L,
            SecurityException("temporarily revoked"),
        )
        val newerReconciliation = store.startRun(SyncMode.RECONCILE, 2_800L)
        store.finishRun(
            newerReconciliation,
            emptySet(),
            GalleryAccessState.Full,
            2_900L,
        )
        val backend = RecordingSyncWorkBackend()
        val coordinator = GallerySyncAccessCoordinator(
            store = store,
            scheduler = MediaSyncScheduler(backend),
        )

        coordinator.onAccessAvailable(GalleryAccessState.Full)

        assertEquals(
            listOf(
                ScheduledImmediate(
                    ImmediateSyncWork.RequestedMode(SyncMode.INCREMENTAL),
                    ExistingWorkPolicy.KEEP,
                ),
            ),
            backend.immediate,
        )
        assertEquals(SyncRunState.PAUSED_PERMISSION, runState(pausedPermission.runId))
        assertEquals(SyncRunState.SUCCEEDED, runState(newerReconciliation.runId))
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

    private fun indexedMediaStoreIds(): List<Long> =
        database.openHelper.readableDatabase.query(
            "SELECT media_store_id FROM image ORDER BY media_store_id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
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

    private class ThrowingGateway(
        private val error: IOException,
    ) : MediaStoreGateway {
        override fun externalVolumes(): Set<String> = setOf(VOLUME)

        override fun readPage(
            volume: String,
            mode: SyncMode,
            cursor: MediaStoreCursor?,
            limit: Int,
        ): MediaStorePage = throw error
    }

    private class ScriptedGateway(
        private val pages: ArrayDeque<MediaStorePage>,
    ) : MediaStoreGateway {
        val readModes = mutableListOf<SyncMode>()

        override fun externalVolumes(): Set<String> = setOf(VOLUME)

        override fun readPage(
            volume: String,
            mode: SyncMode,
            cursor: MediaStoreCursor?,
            limit: Int,
        ): MediaStorePage {
            readModes += mode
            return pages.removeFirst()
        }
    }

    private class MutablePermissionSource(
        var access: GalleryAccessState,
    ) : MediaSyncPermissionSource {
        override fun currentAccess(): GalleryAccessState = access
    }

    private data class ScheduledImmediate(
        val work: ImmediateSyncWork,
        val policy: ExistingWorkPolicy,
    )

    private class RecordingSyncWorkBackend : SyncWorkBackend {
        val immediate = mutableListOf<ScheduledImmediate>()

        override fun enqueueImmediate(work: ImmediateSyncWork, policy: ExistingWorkPolicy) {
            immediate += ScheduledImmediate(work, policy)
        }

        override fun enqueuePeriodic(intervalHours: Long) = Unit
    }

    private data class RunState(
        val mode: SyncMode,
        val state: SyncRunState,
    )

    private companion object {
        const val VOLUME = "external_primary"

        fun checkpoint(
            generation: Long,
            completedAtEpochMillis: Long?,
        ) = SyncCheckpoint(
            volumeName = VOLUME,
            generation = generation,
            mediaStoreVersion = "v1",
            fullScanCursorModifiedAtEpochMillis = null,
            fullScanCursorMediaStoreId = null,
            incrementalHighWaterModifiedAtEpochMillis = null,
            incrementalHighWaterMediaStoreId = Long.MAX_VALUE,
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

        fun cursorFor(image: MediaStoreImage) = MediaStoreCursor(
            modifiedAtEpochMillis = image.modifiedAtEpochMillis,
            mediaStoreId = image.mediaStoreId,
            generation = image.generationModified,
        )
    }
}

package cn.soul2.imageai.media.sync

import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.store.MediaStoreCursor
import cn.soul2.imageai.media.store.MediaStoreGateway
import cn.soul2.imageai.media.store.MediaStoreImage
import cn.soul2.imageai.media.store.MediaStorePage
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncEngineTest {
    @Test
    fun oneThousandAndOneImagesUseTwoSlicesAndResumeFromAtomicCheckpoint() = runTest {
        val images = (1L..1_001L).map(::image)
        val gateway = OrderedFakeGateway(images)
        val store = FakeMediaSyncStore()
        val clock = MutableClock(1_000L)

        val first = engine(gateway, store, clock).runSlice(SyncMode.INITIAL, VOLUME)

        assertInstanceOf<SliceResult.More>(first)
        assertEquals(1_000, store.images.size)
        assertEquals(1_000L, store.checkpoint(VOLUME)?.cursorMediaStoreId)
        assertNull(store.checkpoint(VOLUME)?.completedAtEpochMillis)

        val second = engine(gateway, store, clock).runSlice(SyncMode.INITIAL, VOLUME)

        assertInstanceOf<SliceResult.Completed>(second)
        assertEquals(1_001, store.images.size)
        assertEquals(1_000L, gateway.requestedCursors[1]?.mediaStoreId)
        assertEquals(1_001L, store.checkpoint(VOLUME)?.cursorMediaStoreId)
        assertEquals(1_000L, store.checkpoint(VOLUME)?.completedAtEpochMillis)
    }

    @Test
    fun replayedPageUpsertsByExternalIdentityInsteadOfDuplicatingRows() = runTest {
        val page = listOf(image(1L), image(2L))
        val gateway = ScriptedGateway(
            pages = ArrayDeque(
                listOf(
                    MediaStorePage(page, cursorFor(page.last()), hasMore = true, 2L, "v1"),
                    MediaStorePage(page, cursorFor(page.last()), hasMore = false, 2L, "v1"),
                ),
            ),
        )
        val store = FakeMediaSyncStore()

        val result = engine(gateway, store, MutableClock(4_000L)).runSlice(
            mode = SyncMode.INITIAL,
            volume = VOLUME,
        )

        assertInstanceOf<SliceResult.Completed>(result)
        assertEquals(setOf(1L, 2L), store.images.keys.map { it.second }.toSet())
        assertEquals(2, store.commitCount)
    }

    @Test
    fun deniedAndSecurityExceptionPausePermissionWithoutRetryFailure() = runTest {
        val deniedStore = FakeMediaSyncStore().apply { putExisting(image(8L)) }
        val deniedGateway = OrderedFakeGateway(emptyList())
        val denied = engine(
            deniedGateway,
            deniedStore,
            MutableClock(5_000L),
            access = GalleryAccessState.Denied(canRequestAgain = true),
        ).runSlice(SyncMode.INCREMENTAL, VOLUME)

        assertInstanceOf<SliceResult.PausedPermission>(denied)
        assertEquals(0, deniedGateway.readCount)
        assertEquals(ImageAvailability.PERMISSION_REVOKED, deniedStore.availability(8L))

        val securityStore = FakeMediaSyncStore().apply { putExisting(image(9L)) }
        val security = engine(
            ThrowingGateway(SecurityException("revoked")),
            securityStore,
            MutableClock(6_000L),
        ).runSlice(SyncMode.INCREMENTAL, VOLUME)

        assertInstanceOf<SliceResult.PausedPermission>(security)
        assertEquals(ImageAvailability.PERMISSION_REVOKED, securityStore.availability(9L))
        assertEquals(SyncRunState.PAUSED_PERMISSION, securityStore.latestRun?.state)
    }

    @Test
    fun ioFailureIsReturnedForBoundedWorkerRetryAndCanBePausedAfterExhaustion() = runTest {
        val store = FakeMediaSyncStore()
        val engine = engine(
            ThrowingGateway(IOException("temporary")),
            store,
            MutableClock(7_000L),
        )

        val result = engine.runSlice(SyncMode.INCREMENTAL, VOLUME)
        val retry = assertInstanceOf<SliceResult.Retry>(result)
        assertTrue(SyncPolicy.shouldRetry(retry.error, runAttemptCount = 3))
        assertFalse(SyncPolicy.shouldRetry(retry.error, runAttemptCount = 4))

        engine.pauseAfterRetries(retry.error)
        assertEquals(SyncRunState.PAUSED_ERROR, store.latestRun?.state)
    }

    @Test
    fun fullReconciliationRequiresTwoCompleteAbsencesAtLeastTwentyFourHoursApart() = runTest {
        val store = FakeMediaSyncStore().apply { putExisting(image(11L)) }
        val clock = MutableClock(10_000L)
        val gateway = OrderedFakeGateway(emptyList())

        engine(gateway, store, clock).runSlice(SyncMode.RECONCILE, VOLUME)

        assertEquals(ImageAvailability.AVAILABLE, store.availability(11L))
        assertEquals(10_000L, store.record(11L).missingCandidateSinceEpochMillis)
        assertEquals(1, store.record(11L).missingObservationCount)

        clock.now += SyncPolicy.MISSING_CONFIRMATION_MILLIS - 1L
        engine(gateway, store, clock).runSlice(SyncMode.RECONCILE, VOLUME)
        assertEquals(ImageAvailability.AVAILABLE, store.availability(11L))

        clock.now += 1L
        engine(gateway, store, clock).runSlice(SyncMode.RECONCILE, VOLUME)

        assertEquals(ImageAvailability.MEDIA_MISSING, store.availability(11L))
        assertTrue(store.record(11L).missingObservationCount >= 2)
    }

    @Test
    fun partialReconciliationMarksSelectionRemovalAndUnmountedVolumesRemainRecoverable() = runTest {
        val store = FakeMediaSyncStore().apply {
            putExisting(image(21L, volume = VOLUME))
            putExisting(image(22L, volume = "external_sd"))
        }
        val gateway = OrderedFakeGateway(emptyList(), volumes = setOf(VOLUME))

        engine(
            gateway,
            store,
            MutableClock(9_000L),
            access = GalleryAccessState.Partial,
        ).runSlice(SyncMode.RECONCILE, VOLUME)

        assertEquals(ImageAvailability.SELECTION_REMOVED, store.availability(21L))
        assertEquals(ImageAvailability.VOLUME_UNMOUNTED, store.availability(22L))
    }

    @Test
    fun aNewRunInTheSameMillisecondDoesNotReusePreviousRunCompletion() = runTest {
        val gateway = OrderedFakeGateway(
            images = emptyList(),
            volumes = setOf("external_a", "external_b"),
        )
        val store = FakeMediaSyncStore()
        val engine = engine(gateway, store, MutableClock(12_000L))

        assertInstanceOf<SliceResult.More>(engine.runNextSlice(SyncMode.RECONCILE))
        assertInstanceOf<SliceResult.Completed>(engine.continueNextSlice())
        assertInstanceOf<SliceResult.More>(engine.runNextSlice(SyncMode.RECONCILE))
        assertInstanceOf<SliceResult.Completed>(engine.continueNextSlice())

        assertEquals(4, gateway.readCount)
    }

    @Test
    fun queuedIncrementalCannotInterruptActiveReconciliation() = runTest {
        val missing = image(5_000L)
        val gateway = ModeAwareFakeGateway(
            mapOf(
                SyncMode.RECONCILE to (1L..1_001L).map(::image),
                SyncMode.INCREMENTAL to listOf(image(2_000L)),
            ),
        )
        val store = FakeMediaSyncStore().apply { putExisting(missing) }
        val clock = MutableClock(12_500L)
        val engine = engine(gateway, store, clock)

        val first = engine.runNextSlice(SyncMode.RECONCILE)
        assertInstanceOf<SliceResult.More>(first)
        assertNull(store.record(5_000L).missingCandidateSinceEpochMillis)

        val queuedIncrementalWork = engine.runNextSlice(SyncMode.INCREMENTAL)

        assertEquals(SyncMode.RECONCILE, queuedIncrementalWork.run.mode)
        assertInstanceOf<SliceResult.Completed>(queuedIncrementalWork)
        assertEquals(
            listOf(SyncMode.RECONCILE, SyncMode.RECONCILE),
            gateway.requestedModes,
        )
        assertEquals(12_500L, store.record(5_000L).missingCandidateSinceEpochMillis)
        assertEquals(1, store.record(5_000L).missingObservationCount)

        val queuedReconciliationWork = engine.runNextSlice(SyncMode.RECONCILE)
        assertEquals(SyncMode.INCREMENTAL, queuedReconciliationWork.run.mode)
        assertInstanceOf<SliceResult.Completed>(queuedReconciliationWork)

        val originalContinuation = engine.continueNextSlice()
        val queuedReconciliation = assertInstanceOf<SliceResult.More>(originalContinuation)
        assertEquals(SyncMode.RECONCILE, queuedReconciliation.run.mode)

        val finalContinuation = engine.continueNextSlice()
        val completedReconciliation = assertInstanceOf<SliceResult.Completed>(finalContinuation)
        assertEquals(SyncMode.RECONCILE, completedReconciliation.run.mode)
        assertNull(engine.continueNextSlice())

        assertEquals(
            listOf(
                SyncMode.RECONCILE,
                SyncMode.RECONCILE,
                SyncMode.INCREMENTAL,
                SyncMode.RECONCILE,
                SyncMode.RECONCILE,
            ),
            gateway.requestedModes,
        )
        assertEquals(completedReconciliation.run.runId, store.record(1L).lastSeenRunId)
        assertEquals(1, store.maxConcurrentRunning)
        assertEquals(0, store.runningCount)
        assertTrue(store.queuedModes.isEmpty())
    }

    @Test
    fun completedIncrementalEmptyPageAdvancesToObservedGeneration() = runTest {
        val baseline = MediaStoreCursor(
            modifiedAtEpochMillis = null,
            mediaStoreId = 5L,
            generation = 5L,
        )
        val gateway = ScriptedGateway(
            ArrayDeque(
                listOf(
                    MediaStorePage(
                        images = emptyList(),
                        nextCursor = baseline,
                        hasMore = false,
                        observedGeneration = 9L,
                        observedVersion = "v9",
                    ),
                ),
            ),
        )
        val store = FakeMediaSyncStore()

        engine(gateway, store, MutableClock(13_000L)).runSlice(
            mode = SyncMode.INCREMENTAL,
            volume = VOLUME,
            cursor = baseline,
        )

        assertEquals(9L, store.checkpoint(VOLUME)?.generation)
        assertEquals(Long.MAX_VALUE, store.checkpoint(VOLUME)?.cursorMediaStoreId)
    }

    @Test
    fun fullScanKeepsTheFirstObservedGenerationAcrossPages() = runTest {
        val first = image(1L)
        val second = image(2L)
        val gateway = ScriptedGateway(
            ArrayDeque(
                listOf(
                    MediaStorePage(
                        images = listOf(first),
                        nextCursor = cursorFor(first),
                        hasMore = true,
                        observedGeneration = 10L,
                        observedVersion = "v10",
                    ),
                    MediaStorePage(
                        images = listOf(second),
                        nextCursor = cursorFor(second),
                        hasMore = false,
                        observedGeneration = 20L,
                        observedVersion = "v10",
                    ),
                ),
            ),
        )
        val store = FakeMediaSyncStore()

        engine(gateway, store, MutableClock(14_000L)).runSlice(
            mode = SyncMode.RECONCILE,
            volume = VOLUME,
        )

        assertEquals(10L, store.checkpoint(VOLUME)?.generation)
        assertEquals("v10", store.checkpoint(VOLUME)?.mediaStoreVersion)
    }

    @Test
    fun changedMediaStoreVersionInvalidatesGenerationCursorAndRestartsVolume() = runTest {
        val oldCursor = MediaStoreCursor(
            modifiedAtEpochMillis = null,
            mediaStoreId = 50L,
            generation = 50L,
        )
        val store = FakeMediaSyncStore().apply {
            putCheckpoint(
                SyncCheckpoint(
                    volumeName = VOLUME,
                    generation = oldCursor.generation,
                    mediaStoreVersion = "old-version",
                    cursorModifiedAtEpochMillis = null,
                    cursorMediaStoreId = oldCursor.mediaStoreId,
                    completedAtEpochMillis = 1_000L,
                    fullReconciliationAtEpochMillis = null,
                ),
            )
        }
        val skipped = image(99L)
        val recovered = image(1L)
        val clock = MutableClock(15_000L)
        var firstRead = true
        val gateway = ScriptedGateway(
            pages = ArrayDeque(
                listOf(
                    MediaStorePage(
                        images = listOf(skipped),
                        nextCursor = cursorFor(skipped),
                        hasMore = false,
                        observedGeneration = 99L,
                        observedVersion = "new-version",
                    ),
                    MediaStorePage(
                        images = listOf(recovered),
                        nextCursor = cursorFor(recovered),
                        hasMore = false,
                        observedGeneration = 1L,
                        observedVersion = "new-version",
                    ),
                ),
            ),
            onRead = {
                if (firstRead) {
                    firstRead = false
                    clock.now += SyncPolicy.MAX_DURATION_MILLIS
                }
            },
        )

        val reset = engine(gateway, store, clock).runSlice(
            mode = SyncMode.INCREMENTAL,
            volume = VOLUME,
        )
        assertInstanceOf<SliceResult.More>(reset)
        assertNull(store.checkpoint(VOLUME)?.cursorMediaStoreId)
        assertEquals("new-version", store.checkpoint(VOLUME)?.mediaStoreVersion)

        val result = engine(gateway, store, clock).runSlice(
            mode = SyncMode.INCREMENTAL,
            volume = VOLUME,
        )

        assertInstanceOf<SliceResult.Completed>(result)
        assertEquals(listOf(oldCursor, null), gateway.requestedCursors)
        assertEquals(setOf(1L), store.images.keys.map { it.second }.toSet())
        assertEquals("new-version", store.checkpoint(VOLUME)?.mediaStoreVersion)
    }

    @Test
    fun freshReconciliationDoesNotResumeIncompleteCheckpointOwnedByPausedRun() = runTest {
        val missing = image(5_000L)
        val store = FakeMediaSyncStore().apply { putExisting(missing) }
        val partialGateway = OrderedFakeGateway((1L..1_001L).map(::image))
        val clock = MutableClock(20_000L)

        val partial = engine(partialGateway, store, clock).runSlice(
            mode = SyncMode.RECONCILE,
            volume = VOLUME,
        )

        assertInstanceOf<SliceResult.More>(partial)
        assertNull(store.record(5_000L).missingCandidateSinceEpochMillis)
        store.pauseLatest(clock.now)

        val freshGateway = ScriptedGateway(
            ArrayDeque(
                listOf(
                    MediaStorePage(
                        images = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                        observedGeneration = 1_001L,
                        observedVersion = "v1",
                    ),
                ),
            ),
        )
        val completed = engine(freshGateway, store, clock).runSlice(
            mode = SyncMode.RECONCILE,
            volume = VOLUME,
        )

        assertInstanceOf<SliceResult.Completed>(completed)
        assertEquals(listOf<MediaStoreCursor?>(null), freshGateway.requestedCursors)
        assertEquals(20_000L, store.record(5_000L).missingCandidateSinceEpochMillis)
        assertEquals(1, store.record(5_000L).missingObservationCount)
    }

    private fun engine(
        gateway: MediaStoreGateway,
        store: FakeMediaSyncStore,
        clock: MutableClock,
        access: GalleryAccessState = GalleryAccessState.Full,
    ) = MediaSyncEngine(
        gateway = gateway,
        store = store,
        permissionSource = MediaSyncPermissionSource { access },
        clock = clock,
    )

    private class MutableClock(var now: Long) : SyncClock {
        override fun nowEpochMillis(): Long = now
    }

    private class OrderedFakeGateway(
        private val images: List<MediaStoreImage>,
        private val volumes: Set<String> = setOf(VOLUME),
    ) : MediaStoreGateway {
        val requestedCursors = mutableListOf<MediaStoreCursor?>()
        var readCount = 0

        override fun externalVolumes(): Set<String> = volumes

        override fun readPage(
            volume: String,
            mode: SyncMode,
            cursor: MediaStoreCursor?,
            limit: Int,
        ): MediaStorePage {
            readCount++
            requestedCursors += cursor
            val start = cursor?.mediaStoreId?.toInt() ?: 0
            val page = images.drop(start).take(limit)
            return MediaStorePage(
                images = page,
                nextCursor = page.lastOrNull()?.let(::cursorFor) ?: cursor,
                hasMore = start + page.size < images.size,
                observedGeneration = images.lastOrNull()?.generationModified,
                observedVersion = "v1",
            )
        }
    }

    private class ModeAwareFakeGateway(
        private val imagesByMode: Map<SyncMode, List<MediaStoreImage>>,
    ) : MediaStoreGateway {
        val requestedModes = mutableListOf<SyncMode>()

        override fun externalVolumes(): Set<String> = setOf(VOLUME)

        override fun readPage(
            volume: String,
            mode: SyncMode,
            cursor: MediaStoreCursor?,
            limit: Int,
        ): MediaStorePage {
            requestedModes += mode
            val images = imagesByMode.getValue(mode)
            val remaining = images.filter { image ->
                cursor == null || image.mediaStoreId > cursor.mediaStoreId
            }
            val page = remaining.take(limit)
            return MediaStorePage(
                images = page,
                nextCursor = page.lastOrNull()?.let(::cursorFor) ?: cursor,
                hasMore = remaining.size > page.size,
                observedGeneration = images.mapNotNull(MediaStoreImage::generationModified).maxOrNull(),
                observedVersion = "v1",
            )
        }
    }

    private class ScriptedGateway(
        private val pages: ArrayDeque<MediaStorePage>,
        private val onRead: () -> Unit = {},
    ) : MediaStoreGateway {
        val requestedCursors = mutableListOf<MediaStoreCursor?>()

        override fun externalVolumes(): Set<String> = setOf(VOLUME)

        override fun readPage(
            volume: String,
            mode: SyncMode,
            cursor: MediaStoreCursor?,
            limit: Int,
        ): MediaStorePage {
            requestedCursors += cursor
            onRead()
            return pages.removeFirst()
        }
    }

    private class ThrowingGateway(private val error: Throwable) : MediaStoreGateway {
        override fun externalVolumes(): Set<String> = setOf(VOLUME)

        override fun readPage(
            volume: String,
            mode: SyncMode,
            cursor: MediaStoreCursor?,
            limit: Int,
        ): MediaStorePage = throw error
    }

    private class FakeMediaSyncStore : MediaSyncStore {
        data class Stored(
            val image: MediaStoreImage,
            var availability: ImageAvailability = ImageAvailability.AVAILABLE,
            var lastSeenRunId: Long? = null,
            var missingCandidateSinceEpochMillis: Long? = null,
            var missingObservationCount: Int = 0,
        )

        val images = linkedMapOf<Pair<String, Long>, Stored>()
        private val checkpoints = mutableMapOf<String, SyncCheckpoint>()
        private val runs = mutableListOf<SyncRun>()
        var commitCount = 0
        var maxConcurrentRunning = 0
            private set
        val latestRun: SyncRun? get() = runs.lastOrNull()
        val runningCount: Int get() = runs.count { it.state == SyncRunState.RUNNING }
        val queuedModes: List<SyncMode>
            get() = runs.filter { it.state == SyncRunState.QUEUED }.map(SyncRun::mode)

        fun putExisting(image: MediaStoreImage) {
            images[image.volumeName to image.mediaStoreId] = Stored(image)
        }

        fun putCheckpoint(checkpoint: SyncCheckpoint) {
            checkpoints[checkpoint.volumeName] = checkpoint
        }

        fun pauseLatest(nowEpochMillis: Long) {
            replaceRun(
                requireNotNull(latestRun).pausedError(
                    nowEpochMillis,
                    IOException("paused between runs"),
                ),
            )
        }

        fun record(id: Long): Stored = images.values.single { it.image.mediaStoreId == id }
        fun availability(id: Long): ImageAvailability = record(id).availability

        override suspend fun enqueueAndClaimRun(
            requestedMode: SyncMode?,
            nowEpochMillis: Long,
        ): SyncRun? {
            if (requestedMode != null &&
                runs.none { it.mode == requestedMode && it.state == SyncRunState.QUEUED }
            ) {
                runs += SyncRun.queued(runs.size + 1L, requestedMode, nowEpochMillis)
            }
            runs.firstOrNull { it.state == SyncRunState.RUNNING }?.let { return it }
            val queued = runs.firstOrNull { it.state == SyncRunState.QUEUED } ?: return null
            return queued.activated(nowEpochMillis).also(::replaceRun)
        }

        override suspend fun claimRetryRun(nowEpochMillis: Long): SyncRun? {
            runs.firstOrNull { it.state == SyncRunState.RUNNING }?.let { return it }
            val latest = runs.lastOrNull()
            if (latest?.state == SyncRunState.PAUSED_PERMISSION ||
                latest?.state == SyncRunState.PAUSED_ERROR
            ) {
                return latest.resumed(nowEpochMillis).also(::replaceRun)
            }
            val queued = runs.firstOrNull { it.state == SyncRunState.QUEUED } ?: return null
            return queued.activated(nowEpochMillis).also(::replaceRun)
        }

        override suspend fun activeRun(mode: SyncMode): SyncRun? =
            runs.firstOrNull { it.mode == mode && it.state == SyncRunState.RUNNING }

        override suspend fun startRun(mode: SyncMode, nowEpochMillis: Long): SyncRun {
            val run = SyncRun.running(runs.size + 1L, mode, nowEpochMillis)
            runs += run
            recordRunningCount()
            return run
        }

        override suspend fun checkpoint(volume: String): SyncCheckpoint? = checkpoints[volume]

        override suspend fun checkpoints(volumes: Set<String>): Map<String, SyncCheckpoint> =
            checkpoints.filterKeys { it in volumes }

        override suspend fun commitBatch(
            images: List<MediaStoreImage>,
            checkpoint: SyncCheckpoint,
            run: SyncRun,
        ) {
            images.forEach { image ->
                this.images[image.volumeName to image.mediaStoreId] = Stored(
                    image = image,
                    availability = ImageAvailability.AVAILABLE,
                    lastSeenRunId = if (run.mode == SyncMode.RECONCILE) run.runId else null,
                )
            }
            checkpoints[checkpoint.volumeName] = checkpoint
            replaceRun(run)
            commitCount++
        }

        override suspend fun updateRun(run: SyncRun) = replaceRun(run)

        override suspend fun pauseForPermission(run: SyncRun, nowEpochMillis: Long, error: Throwable?) {
            images.values.forEach { it.availability = ImageAvailability.PERMISSION_REVOKED }
            replaceRun(run.pausedPermission(nowEpochMillis, error))
        }

        override suspend fun finishRun(
            run: SyncRun,
            mountedVolumes: Set<String>,
            access: GalleryAccessState,
            nowEpochMillis: Long,
        ) {
            if (run.mode == SyncMode.RECONCILE) {
                images.values.forEach { stored ->
                    when {
                        stored.image.volumeName !in mountedVolumes -> {
                            stored.availability = ImageAvailability.VOLUME_UNMOUNTED
                        }
                        stored.lastSeenRunId == run.runId -> Unit
                        access is GalleryAccessState.Partial -> {
                            stored.availability = ImageAvailability.SELECTION_REMOVED
                        }
                        access is GalleryAccessState.Full -> {
                            val candidate = stored.missingCandidateSinceEpochMillis
                            if (candidate == null) {
                                stored.missingCandidateSinceEpochMillis = nowEpochMillis
                                stored.missingObservationCount = 1
                            } else if (nowEpochMillis - candidate >= SyncPolicy.MISSING_CONFIRMATION_MILLIS) {
                                stored.availability = ImageAvailability.MEDIA_MISSING
                                stored.missingObservationCount++
                            }
                        }
                    }
                }
            }
            replaceRun(run.succeeded(nowEpochMillis))
        }

        private fun replaceRun(run: SyncRun) {
            val index = runs.indexOfFirst { it.runId == run.runId }
            if (index < 0) runs += run else runs[index] = run
            recordRunningCount()
        }

        private fun recordRunningCount() {
            maxConcurrentRunning = maxOf(maxConcurrentRunning, runningCount)
        }
    }

    private companion object {
        const val VOLUME = "external_primary"

        fun cursorFor(image: MediaStoreImage) = MediaStoreCursor(
            modifiedAtEpochMillis = image.modifiedAtEpochMillis,
            mediaStoreId = image.mediaStoreId,
            generation = image.generationModified,
        )

        fun image(id: Long, volume: String = VOLUME) = MediaStoreImage(
            volumeName = volume,
            mediaStoreId = id,
            contentUri = "content://media/$volume/images/media/$id",
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

private inline fun <reified T> assertInstanceOf(value: Any?): T {
    assertTrue("Expected ${T::class.java.name}, got ${value?.javaClass?.name}", value is T)
    @Suppress("UNCHECKED_CAST")
    return value as T
}

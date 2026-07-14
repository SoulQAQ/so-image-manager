package cn.soul2.imageai.media.sync

import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.store.MediaStoreCursor
import cn.soul2.imageai.media.store.MediaStoreGateway
import java.io.IOException

fun interface MediaSyncPermissionSource {
    fun currentAccess(): GalleryAccessState
}

fun interface SyncClock {
    fun nowEpochMillis(): Long
}

object SystemSyncClock : SyncClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

sealed interface SliceResult {
    val run: SyncRun

    data class More(
        override val run: SyncRun,
        val cursor: MediaStoreCursor?,
    ) : SliceResult

    data class Completed(override val run: SyncRun) : SliceResult
    data class PausedPermission(override val run: SyncRun) : SliceResult
    data class Retry(override val run: SyncRun, val error: IOException) : SliceResult
    data class PausedError(override val run: SyncRun, val error: Throwable) : SliceResult
}

class MediaSyncEngine(
    private val gateway: MediaStoreGateway,
    private val store: MediaSyncStore,
    private val permissionSource: MediaSyncPermissionSource,
    private val clock: SyncClock = SystemSyncClock,
) {
    private var lastRun: SyncRun? = null

    suspend fun runNextSlice(mode: SyncMode): SliceResult {
        var run = store.activeRun(mode) ?: store.startRun(mode, clock.nowEpochMillis())
        lastRun = run
        val access = permissionSource.currentAccess()
        if (access is GalleryAccessState.Denied) {
            store.pauseForPermission(run, clock.nowEpochMillis(), error = null)
            run = run.pausedPermission(clock.nowEpochMillis(), error = null)
            lastRun = run
            return SliceResult.PausedPermission(run)
        }
        val mountedVolumes = try {
            gateway.externalVolumes()
        } catch (error: SecurityException) {
            return pauseForPermission(run, error)
        } catch (error: IOException) {
            return SliceResult.Retry(run, error)
        } catch (error: Throwable) {
            return pauseForError(run, error)
        }
        if (mountedVolumes.isEmpty()) {
            val now = clock.nowEpochMillis()
            store.finishRun(run, emptySet(), access, now)
            return SliceResult.Completed(run.succeeded(now))
        }
        val orderedVolumes = mountedVolumes.sorted()
        val checkpoints = store.checkpoints(mountedVolumes)
        val currentVolume = run.currentVolumeName
        val nextVolume = when {
            currentVolume == null || currentVolume !in mountedVolumes -> orderedVolumes.first()
            checkpoints[currentVolume]?.completedAtEpochMillis == null -> currentVolume
            else -> orderedVolumes.dropWhile { it != currentVolume }.drop(1).firstOrNull()
        }
        if (nextVolume == null) {
            val now = clock.nowEpochMillis()
            store.finishRun(run, mountedVolumes, access, now)
            return SliceResult.Completed(run.succeeded(now))
        }
        return runSlice(mode = mode, volume = nextVolume)
    }

    suspend fun retryPausedSlice(): SliceResult {
        val paused = store.recoverableRun() ?: return runNextSlice(SyncMode.INCREMENTAL)
        val resumed = paused.resumed(clock.nowEpochMillis())
        store.updateRun(resumed)
        lastRun = resumed
        return runNextSlice(resumed.mode)
    }

    suspend fun runSlice(
        mode: SyncMode,
        volume: String,
        cursor: MediaStoreCursor? = null,
        maxItems: Int = SyncPolicy.MAX_ITEMS_PER_SLICE,
        maxDurationMillis: Long = SyncPolicy.MAX_DURATION_MILLIS,
    ): SliceResult {
        require(maxItems in 1..SyncPolicy.MAX_ITEMS_PER_SLICE)
        require(maxDurationMillis in 1..SyncPolicy.MAX_DURATION_MILLIS)
        var run = store.activeRun(mode) ?: store.startRun(mode, clock.nowEpochMillis())
        lastRun = run
        val access = permissionSource.currentAccess()
        if (access is GalleryAccessState.Denied) {
            store.pauseForPermission(run, clock.nowEpochMillis(), error = null)
            run = run.pausedPermission(clock.nowEpochMillis(), error = null)
            lastRun = run
            return SliceResult.PausedPermission(run)
        }

        val mountedVolumes = try {
            gateway.externalVolumes()
        } catch (error: SecurityException) {
            return pauseForPermission(run, error)
        } catch (error: IOException) {
            return SliceResult.Retry(run, error)
        } catch (error: Throwable) {
            return pauseForError(run, error)
        }
        var currentCheckpoint = store.checkpoint(volume)
        var currentCursor = cursor ?: resumeCursor(mode, volume, run, currentCheckpoint)
        var processed = 0
        val sliceStartedAt = clock.nowEpochMillis()

        while (processed < maxItems && clock.nowEpochMillis() - sliceStartedAt < maxDurationMillis) {
            val remaining = maxItems - processed
            val firstPage = currentCursor == null
            val checkpointBeforePage = currentCheckpoint
            val page = try {
                gateway.readPage(volume, mode, currentCursor, remaining)
            } catch (error: SecurityException) {
                return pauseForPermission(run, error)
            } catch (error: IOException) {
                return SliceResult.Retry(run, error)
            } catch (error: Throwable) {
                return pauseForError(run, error)
            }
            if (currentCursor != null &&
                checkpointBeforePage?.mediaStoreVersion != null &&
                page.observedVersion != null &&
                checkpointBeforePage.mediaStoreVersion != page.observedVersion
            ) {
                val now = clock.nowEpochMillis()
                run = run.withPage(volume, imageCount = 0, nowEpochMillis = now)
                val resetCheckpoint = SyncCheckpoint(
                    volumeName = volume,
                    generation = null,
                    mediaStoreVersion = page.observedVersion,
                    cursorModifiedAtEpochMillis = null,
                    cursorMediaStoreId = null,
                    completedAtEpochMillis = null,
                    fullReconciliationAtEpochMillis =
                        checkpointBeforePage.fullReconciliationAtEpochMillis,
                )
                store.commitBatch(emptyList(), resetCheckpoint, run)
                currentCheckpoint = resetCheckpoint
                currentCursor = null
                lastRun = run
                continue
            }
            if (page.hasMore && page.images.isEmpty()) {
                return pauseForError(run, IOException("MediaStore page made no progress"))
            }
            processed += page.images.size
            val now = clock.nowEpochMillis()
            run = run.withPage(volume, page.images.size, now)
            val nextCursor = page.nextCursor ?: currentCursor
            val checkpoint = checkpointAfterPage(
                mode = mode,
                volume = volume,
                previous = checkpointBeforePage,
                cursor = nextCursor,
                firstPage = firstPage,
                observedGeneration = page.observedGeneration,
                observedVersion = page.observedVersion,
                completed = !page.hasMore,
                nowEpochMillis = now,
            )
            store.commitBatch(page.images, checkpoint, run)
            currentCheckpoint = checkpoint
            currentCursor = nextCursor
            lastRun = run

            if (!page.hasMore) {
                return if (mountedVolumes.sorted().lastOrNull() == volume) {
                    store.finishRun(run, mountedVolumes, access, now)
                    val succeeded = run.succeeded(now)
                    lastRun = succeeded
                    SliceResult.Completed(succeeded)
                } else {
                    SliceResult.More(run, currentCursor)
                }
            }
        }
        return SliceResult.More(run, currentCursor)
    }

    suspend fun pauseAfterRetries(error: Throwable) {
        var activeRun = lastRun
        if (activeRun == null) {
            for (mode in SyncMode.entries) {
                activeRun = store.activeRun(mode)
                if (activeRun != null) break
            }
        }
        val run = activeRun ?: return
        val paused = run.pausedError(clock.nowEpochMillis(), error)
        store.updateRun(paused)
        lastRun = paused
    }

    private suspend fun pauseForPermission(run: SyncRun, error: Throwable): SliceResult {
        val now = clock.nowEpochMillis()
        store.pauseForPermission(run, now, error)
        val paused = run.pausedPermission(now, error)
        lastRun = paused
        return SliceResult.PausedPermission(paused)
    }

    private suspend fun pauseForError(run: SyncRun, error: Throwable): SliceResult {
        val paused = run.pausedError(clock.nowEpochMillis(), error)
        store.updateRun(paused)
        lastRun = paused
        return SliceResult.PausedError(paused, error)
    }

    private fun resumeCursor(
        mode: SyncMode,
        volume: String,
        run: SyncRun,
        checkpoint: SyncCheckpoint?,
    ): MediaStoreCursor? {
        checkpoint ?: return null
        if (mode != SyncMode.INCREMENTAL && run.currentVolumeName != volume) {
            return null
        }
        val mediaStoreId = checkpoint.cursorMediaStoreId ?: return null
        return MediaStoreCursor(
            modifiedAtEpochMillis = checkpoint.cursorModifiedAtEpochMillis,
            mediaStoreId = mediaStoreId,
            generation = checkpoint.generation,
        )
    }

    private fun checkpointAfterPage(
        mode: SyncMode,
        volume: String,
        previous: SyncCheckpoint?,
        cursor: MediaStoreCursor?,
        firstPage: Boolean,
        observedGeneration: Long?,
        observedVersion: String?,
        completed: Boolean,
        nowEpochMillis: Long,
    ): SyncCheckpoint {
        val advancesToObservedGeneration = mode == SyncMode.INCREMENTAL &&
            completed &&
            observedGeneration != null &&
            (cursor?.generation == null || observedGeneration > cursor.generation)
        val generation = when (mode) {
            SyncMode.INCREMENTAL -> if (advancesToObservedGeneration) {
                observedGeneration
            } else {
                cursor?.generation ?: observedGeneration ?: previous?.generation
            }
            else -> if (firstPage) {
                observedGeneration ?: previous?.generation
            } else {
                previous?.generation ?: observedGeneration
            }
        }
        val mediaStoreVersion = if (mode != SyncMode.INCREMENTAL && !firstPage) {
            previous?.mediaStoreVersion ?: observedVersion
        } else {
            observedVersion ?: previous?.mediaStoreVersion
        }
        return SyncCheckpoint(
            volumeName = volume,
            generation = generation,
            mediaStoreVersion = mediaStoreVersion,
            cursorModifiedAtEpochMillis = cursor?.modifiedAtEpochMillis,
            cursorMediaStoreId = when {
                advancesToObservedGeneration -> Long.MAX_VALUE
                mode != SyncMode.INCREMENTAL && completed &&
                    cursor == null && generation != null -> Long.MAX_VALUE
                else -> cursor?.mediaStoreId
            },
            completedAtEpochMillis = nowEpochMillis.takeIf { completed },
            fullReconciliationAtEpochMillis = when {
                mode == SyncMode.RECONCILE && completed -> nowEpochMillis
                else -> previous?.fullReconciliationAtEpochMillis
            },
        )
    }
}

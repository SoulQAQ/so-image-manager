package cn.soul2.imageai.media.sync

import cn.soul2.imageai.data.db.dao.MediaSyncDao
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.MediaSyncCheckpointEntity
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.store.MediaStoreImage

class RoomMediaSyncStore(
    private val syncDao: MediaSyncDao,
) : MediaSyncStore {
    override suspend fun enqueueAndClaimRun(
        requestedMode: SyncMode?,
        nowEpochMillis: Long,
    ): SyncRun? = syncDao.enqueueAndClaimRun(
        requestedRun = requestedMode?.let { mode ->
            RoomMediaSyncMapper.run(SyncRun.queued(0L, mode, nowEpochMillis))
        },
        nowEpochMillis = nowEpochMillis,
    )?.let(RoomMediaSyncMapper::run)

    override suspend fun activeRun(mode: SyncMode): SyncRun? =
        syncDao.getActiveRun(mode.name)?.let(RoomMediaSyncMapper::run)

    override suspend fun recoverableRun(): SyncRun? =
        syncDao.getRecoverableRun()?.let(RoomMediaSyncMapper::run)

    override suspend fun startRun(mode: SyncMode, nowEpochMillis: Long): SyncRun {
        val run = SyncRun.running(runId = 0L, mode = mode, nowEpochMillis = nowEpochMillis)
        val runId = syncDao.insertRun(RoomMediaSyncMapper.run(run))
        return run.copy(runId = runId)
    }

    override suspend fun checkpoint(volume: String): SyncCheckpoint? =
        syncDao.getCheckpoint(volume)?.let(RoomMediaSyncMapper::checkpoint)

    override suspend fun checkpoints(volumes: Set<String>): Map<String, SyncCheckpoint> {
        if (volumes.isEmpty()) return emptyMap()
        return syncDao.getCheckpoints(volumes)
            .map(RoomMediaSyncMapper::checkpoint)
            .associateBy(SyncCheckpoint::volumeName)
    }

    override suspend fun commitBatch(
        images: List<MediaStoreImage>,
        checkpoint: SyncCheckpoint,
        run: SyncRun,
    ) {
        syncDao.commitBatch(
            images = images.map { image -> RoomMediaSyncMapper.image(image, run) },
            checkpoint = RoomMediaSyncMapper.checkpoint(checkpoint),
            run = RoomMediaSyncMapper.run(run),
        )
    }

    override suspend fun updateRun(run: SyncRun) {
        syncDao.upsertRun(RoomMediaSyncMapper.run(run))
    }

    override suspend fun pauseForPermission(
        run: SyncRun,
        nowEpochMillis: Long,
        error: Throwable?,
    ) {
        syncDao.pauseForPermission(
            RoomMediaSyncMapper.run(run.pausedPermission(nowEpochMillis, error)),
        )
    }

    override suspend fun finishRun(
        run: SyncRun,
        mountedVolumes: Set<String>,
        access: GalleryAccessState,
        nowEpochMillis: Long,
    ) {
        val succeeded = run.succeeded(nowEpochMillis)
        if (run.mode == SyncMode.RECONCILE) {
            syncDao.finishReconciliation(
                run = RoomMediaSyncMapper.run(succeeded),
                mountedVolumes = mountedVolumes,
                partialAccess = access is GalleryAccessState.Partial,
                nowEpochMillis = nowEpochMillis,
                missingThresholdEpochMillis =
                    nowEpochMillis - SyncPolicy.MISSING_CONFIRMATION_MILLIS,
            )
        } else {
            syncDao.upsertRun(RoomMediaSyncMapper.run(succeeded))
        }
    }
}

internal object RoomMediaSyncMapper {
    fun image(image: MediaStoreImage, run: SyncRun): ImageEntity {
        val capturedAt = image.capturedAtEpochMillis?.takeIf { it > 0L }
        val sortTime = capturedAt
            ?: image.modifiedAtEpochMillis.takeIf { it > 0L }
            ?: image.addedAtEpochMillis
        return ImageEntity(
            volumeName = image.volumeName,
            mediaStoreId = image.mediaStoreId,
            contentUri = image.contentUri,
            displayName = image.displayName,
            mimeType = image.mimeType,
            width = image.width,
            height = image.height,
            sizeBytes = image.sizeBytes,
            capturedAtEpochMillis = capturedAt,
            addedAtEpochMillis = image.addedAtEpochMillis,
            modifiedAtEpochMillis = image.modifiedAtEpochMillis,
            sortTimeEpochMillis = sortTime,
            bucketId = image.bucketId,
            bucketName = image.bucketName,
            isFavorite = image.isFavorite,
            quickFingerprint = image.quickFingerprint(),
            availability = ImageAvailability.AVAILABLE,
            lastSeenSyncRunId = run.runId.takeIf { run.mode == SyncMode.RECONCILE },
            missingCandidateSinceEpochMillis = null,
            missingObservationCount = 0,
        )
    }

    fun checkpoint(entity: MediaSyncCheckpointEntity): SyncCheckpoint = SyncCheckpoint(
        volumeName = entity.volumeName,
        generation = entity.generation,
        mediaStoreVersion = entity.mediaStoreVersion,
        cursorModifiedAtEpochMillis = entity.cursorModifiedAtEpochMillis,
        cursorMediaStoreId = entity.cursorMediaStoreId,
        completedAtEpochMillis = entity.completedAtEpochMillis,
        fullReconciliationAtEpochMillis = entity.fullReconciliationAtEpochMillis,
    )

    fun checkpoint(checkpoint: SyncCheckpoint): MediaSyncCheckpointEntity =
        MediaSyncCheckpointEntity(
            volumeName = checkpoint.volumeName,
            generation = checkpoint.generation,
            mediaStoreVersion = checkpoint.mediaStoreVersion,
            cursorModifiedAtEpochMillis = checkpoint.cursorModifiedAtEpochMillis,
            cursorMediaStoreId = checkpoint.cursorMediaStoreId,
            completedAtEpochMillis = checkpoint.completedAtEpochMillis,
            fullReconciliationAtEpochMillis = checkpoint.fullReconciliationAtEpochMillis,
        )

    fun run(entity: MediaSyncRunEntity): SyncRun = SyncRun(
        runId = entity.runId,
        mode = SyncMode.valueOf(entity.mode),
        state = SyncRunState.valueOf(entity.state),
        currentVolumeName = entity.currentVolumeName,
        discoveredCount = entity.discoveredCount,
        indexedCount = entity.indexedCount,
        unavailableCount = entity.unavailableCount,
        errorCode = entity.errorCode,
        errorMessage = entity.errorMessage,
        startedAtEpochMillis = entity.startedAtEpochMillis,
        updatedAtEpochMillis = entity.updatedAtEpochMillis,
        completedAtEpochMillis = entity.completedAtEpochMillis,
    )

    fun run(run: SyncRun): MediaSyncRunEntity = MediaSyncRunEntity(
        runId = run.runId,
        mode = run.mode.name,
        state = run.state.name,
        currentVolumeName = run.currentVolumeName,
        discoveredCount = run.discoveredCount,
        indexedCount = run.indexedCount,
        unavailableCount = run.unavailableCount,
        errorCode = run.errorCode,
        errorMessage = run.errorMessage,
        startedAtEpochMillis = run.startedAtEpochMillis,
        updatedAtEpochMillis = run.updatedAtEpochMillis,
        completedAtEpochMillis = run.completedAtEpochMillis,
    )
}

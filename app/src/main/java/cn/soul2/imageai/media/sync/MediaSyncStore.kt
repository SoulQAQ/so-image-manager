package cn.soul2.imageai.media.sync

import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.store.MediaStoreImage

interface MediaSyncStore {
    suspend fun enqueueAndClaimRun(
        requestedMode: SyncMode?,
        nowEpochMillis: Long,
    ): SyncRun?

    suspend fun claimRetryRun(nowEpochMillis: Long): SyncRun?

    suspend fun activeRun(mode: SyncMode): SyncRun?
    suspend fun recoverableRun(): SyncRun? = null
    suspend fun startRun(mode: SyncMode, nowEpochMillis: Long): SyncRun
    suspend fun checkpoint(volume: String): SyncCheckpoint?
    suspend fun checkpoints(volumes: Set<String>): Map<String, SyncCheckpoint>

    suspend fun commitBatch(
        images: List<MediaStoreImage>,
        checkpoint: SyncCheckpoint,
        run: SyncRun,
    )

    suspend fun updateRun(run: SyncRun)

    suspend fun pauseForPermission(
        run: SyncRun,
        nowEpochMillis: Long,
        error: Throwable?,
    )

    suspend fun finishRun(
        run: SyncRun,
        mountedVolumes: Set<String>,
        access: GalleryAccessState,
        nowEpochMillis: Long,
    )
}

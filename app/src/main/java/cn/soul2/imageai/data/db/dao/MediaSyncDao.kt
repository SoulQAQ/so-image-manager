package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.MediaSyncCheckpointEntity
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MediaSyncDao {
    @Query("SELECT * FROM media_sync_run ORDER BY run_id DESC LIMIT 1")
    abstract fun observeCurrentRun(): Flow<MediaSyncRunEntity?>

    @Query("SELECT MAX(completed_at_epoch_millis) FROM media_sync_run")
    abstract fun observeLastCompletedAt(): Flow<Long?>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM media_sync_run
            WHERE state = 'SUCCEEDED' AND mode IN ('INITIAL', 'RECONCILE')
        )
        """,
    )
    abstract suspend fun hasPersistedScanBaseline(): Boolean

    @Query("SELECT * FROM media_sync_checkpoint WHERE volume_name = :volumeName LIMIT 1")
    abstract suspend fun getCheckpoint(volumeName: String): MediaSyncCheckpointEntity?

    @Query("SELECT * FROM media_sync_checkpoint WHERE volume_name IN (:volumeNames)")
    abstract suspend fun getCheckpoints(volumeNames: Set<String>): List<MediaSyncCheckpointEntity>

    @Query(
        """
        SELECT * FROM media_sync_run
        WHERE mode = :mode AND state = 'RUNNING'
        ORDER BY run_id ASC LIMIT 1
        """,
    )
    abstract suspend fun getActiveRun(mode: String): MediaSyncRunEntity?

    @Query(
        """
        SELECT * FROM media_sync_run
        WHERE mode = :mode AND state = 'QUEUED'
        ORDER BY run_id ASC LIMIT 1
        """,
    )
    protected abstract suspend fun getQueuedRun(mode: String): MediaSyncRunEntity?

    @Query(
        """
        SELECT * FROM media_sync_run
        WHERE state = 'RUNNING'
        ORDER BY run_id ASC LIMIT 1
        """,
    )
    protected abstract suspend fun getOldestRunningRun(): MediaSyncRunEntity?

    @Query(
        """
        SELECT * FROM media_sync_run
        WHERE state = 'QUEUED'
        ORDER BY run_id ASC LIMIT 1
        """,
    )
    protected abstract suspend fun getOldestQueuedRun(): MediaSyncRunEntity?

    @Query("SELECT * FROM media_sync_run ORDER BY run_id DESC LIMIT 1")
    protected abstract suspend fun getLatestRun(): MediaSyncRunEntity?

    @Query(
        """
        SELECT paused.* FROM media_sync_run AS paused
        WHERE paused.state = 'PAUSED_PERMISSION'
          AND NOT EXISTS(
              SELECT 1 FROM media_sync_run AS newer
              WHERE newer.run_id > paused.run_id
                AND newer.state != 'QUEUED'
          )
        ORDER BY paused.run_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun getRecoverableRun(): MediaSyncRunEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM media_sync_run WHERE state = 'QUEUED')")
    abstract suspend fun hasQueuedWork(): Boolean

    @Upsert
    abstract suspend fun upsertCheckpoint(checkpoint: MediaSyncCheckpointEntity)

    @Upsert
    abstract suspend fun upsertRun(run: MediaSyncRunEntity)

    @Insert
    abstract suspend fun insertRun(run: MediaSyncRunEntity): Long

    @Query(
        """
        UPDATE media_sync_run
        SET state = 'RUNNING', updated_at_epoch_millis = :nowEpochMillis
        WHERE run_id = :runId AND state = 'QUEUED'
        """,
    )
    protected abstract suspend fun activateRun(runId: Long, nowEpochMillis: Long): Int

    @Query(
        """
        UPDATE media_sync_run
        SET state = 'RUNNING',
            error_code = NULL,
            error_message = NULL,
            updated_at_epoch_millis = :nowEpochMillis,
            completed_at_epoch_millis = NULL
        WHERE run_id = :runId AND state IN ('PAUSED_PERMISSION', 'PAUSED_ERROR')
        """,
    )
    protected abstract suspend fun resumePausedRun(runId: Long, nowEpochMillis: Long): Int

    @Transaction
    open suspend fun enqueueAndClaimRun(
        requestedRun: MediaSyncRunEntity?,
        nowEpochMillis: Long,
    ): MediaSyncRunEntity? {
        val recoverableRun = getRecoverableRun()
        if (requestedRun != null && getQueuedRun(requestedRun.mode) == null) {
            insertRun(requestedRun)
        }
        getOldestRunningRun()?.let { return it }
        recoverableRun?.let { paused ->
            check(resumePausedRun(paused.runId, nowEpochMillis) == 1) {
                "Unable to resume permission-paused media sync run ${paused.runId}"
            }
            return paused.copy(
                state = "RUNNING",
                errorCode = null,
                errorMessage = null,
                updatedAtEpochMillis = nowEpochMillis,
                completedAtEpochMillis = null,
            )
        }
        val queued = getOldestQueuedRun() ?: return null
        check(activateRun(queued.runId, nowEpochMillis) == 1) {
            "Unable to activate queued media sync run ${queued.runId}"
        }
        return queued.copy(
            state = "RUNNING",
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    @Transaction
    open suspend fun claimRetryRun(nowEpochMillis: Long): MediaSyncRunEntity? {
        getOldestRunningRun()?.let { return it }
        val latest = getLatestRun()
        if (latest?.state == "PAUSED_PERMISSION" || latest?.state == "PAUSED_ERROR") {
            check(resumePausedRun(latest.runId, nowEpochMillis) == 1) {
                "Unable to resume paused media sync run ${latest.runId}"
            }
            return latest.copy(
                state = "RUNNING",
                errorCode = null,
                errorMessage = null,
                updatedAtEpochMillis = nowEpochMillis,
                completedAtEpochMillis = null,
            )
        }
        val queued = getOldestQueuedRun() ?: return null
        check(activateRun(queued.runId, nowEpochMillis) == 1) {
            "Unable to activate queued media sync run ${queued.runId}"
        }
        return queued.copy(
            state = "RUNNING",
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    @Query(
        """
        UPDATE media_sync_run
        SET state = :state,
            current_volume_name = :currentVolumeName,
            discovered_count = :discoveredCount,
            indexed_count = :indexedCount,
            unavailable_count = :unavailableCount,
            error_code = :errorCode,
            error_message = :errorMessage,
            updated_at_epoch_millis = :updatedAtEpochMillis,
            completed_at_epoch_millis = :completedAtEpochMillis
        WHERE run_id = :runId
        """,
    )
    abstract suspend fun updateRunProgress(
        runId: Long,
        state: String,
        currentVolumeName: String?,
        discoveredCount: Int,
        indexedCount: Int,
        unavailableCount: Int,
        errorCode: String?,
        errorMessage: String?,
        updatedAtEpochMillis: Long,
        completedAtEpochMillis: Long?,
    ): Int

    @Transaction
    open suspend fun commitBatch(
        images: List<ImageEntity>,
        checkpoint: MediaSyncCheckpointEntity,
        run: MediaSyncRunEntity,
    ): List<ImageEntity> {
        val resolved = resolveImagesForUpsert(images, ::findExistingIdentities)
        val existingIds = resolved.map(ImageEntity::localId).filter { it > 0L }
        if (existingIds.isNotEmpty()) deleteSearchDocuments(existingIds)
        if (resolved.isNotEmpty()) upsertImages(resolved)
        upsertCheckpoint(checkpoint)
        upsertRun(run)
        return resolveImagesForUpsert(images, ::findExistingIdentities)
    }

    @Query("DELETE FROM search_document WHERE rowid IN (:imageLocalIds)")
    protected abstract suspend fun deleteSearchDocuments(imageLocalIds: List<Long>): Int

    @Transaction
    open suspend fun pauseForPermission(run: MediaSyncRunEntity) {
        markAllPermissionRevoked()
        upsertRun(run.copy(unavailableCount = countUnavailable()))
    }

    @Transaction
    open suspend fun finishReconciliation(
        run: MediaSyncRunEntity,
        mountedVolumes: Set<String>,
        partialAccess: Boolean,
        nowEpochMillis: Long,
        missingThresholdEpochMillis: Long,
    ) {
        if (mountedVolumes.isEmpty()) {
            markAllVolumesUnmounted()
        } else {
            markUnmountedVolumes(mountedVolumes)
            if (partialAccess) {
                markSelectionRemoved(run.runId, mountedVolumes)
            } else {
                observeMissingCandidates(
                    runId = run.runId,
                    mountedVolumes = mountedVolumes,
                    nowEpochMillis = nowEpochMillis,
                    missingThresholdEpochMillis = missingThresholdEpochMillis,
                )
            }
        }
        upsertRun(run.copy(unavailableCount = countUnavailable()))
    }

    @Query("UPDATE image SET availability = 'PERMISSION_REVOKED' WHERE source = 'MEDIA_STORE' AND availability != 'REMOVED_FROM_SOIM'")
    protected abstract suspend fun markAllPermissionRevoked(): Int

    @Query("UPDATE image SET availability = 'VOLUME_UNMOUNTED' WHERE source = 'MEDIA_STORE' AND availability != 'REMOVED_FROM_SOIM'")
    protected abstract suspend fun markAllVolumesUnmounted(): Int

    @Query(
        """
        UPDATE image
        SET availability = 'VOLUME_UNMOUNTED'
        WHERE source = 'MEDIA_STORE' AND availability != 'REMOVED_FROM_SOIM' AND volume_name NOT IN (:mountedVolumes)
        """,
    )
    protected abstract suspend fun markUnmountedVolumes(mountedVolumes: Set<String>): Int

    @Query(
        """
        UPDATE image
        SET availability = 'SELECTION_REMOVED',
            missing_candidate_since_epoch_millis = NULL,
            missing_observation_count = 0
        WHERE source = 'MEDIA_STORE' AND availability != 'REMOVED_FROM_SOIM' AND volume_name IN (:mountedVolumes)
          AND (last_seen_sync_run_id IS NULL OR last_seen_sync_run_id != :runId)
        """,
    )
    protected abstract suspend fun markSelectionRemoved(
        runId: Long,
        mountedVolumes: Set<String>,
    ): Int

    @Query(
        """
        UPDATE image
        SET availability = CASE
                WHEN missing_candidate_since_epoch_millis IS NOT NULL
                  AND missing_candidate_since_epoch_millis <= :missingThresholdEpochMillis
                  AND missing_observation_count >= 1
                THEN 'MEDIA_MISSING'
                ELSE availability
            END,
            missing_candidate_since_epoch_millis =
                COALESCE(missing_candidate_since_epoch_millis, :nowEpochMillis),
            missing_observation_count = CASE
                WHEN missing_candidate_since_epoch_millis IS NULL THEN 1
                WHEN missing_candidate_since_epoch_millis <= :missingThresholdEpochMillis
                THEN missing_observation_count + 1
                ELSE missing_observation_count
            END
        WHERE source = 'MEDIA_STORE' AND availability != 'REMOVED_FROM_SOIM' AND volume_name IN (:mountedVolumes)
          AND (last_seen_sync_run_id IS NULL OR last_seen_sync_run_id != :runId)
        """,
    )
    protected abstract suspend fun observeMissingCandidates(
        runId: Long,
        mountedVolumes: Set<String>,
        nowEpochMillis: Long,
        missingThresholdEpochMillis: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM image WHERE availability != 'AVAILABLE' AND availability != 'REMOVED_FROM_SOIM'")
    protected abstract suspend fun countUnavailable(): Int

    @Query(
        """
        SELECT volume_name, media_store_id, local_id, availability
        FROM image
        WHERE volume_name = :volumeName AND media_store_id IN (:mediaStoreIds)
        """,
    )
    protected abstract suspend fun findExistingIdentities(
        volumeName: String,
        mediaStoreIds: List<Long>,
    ): List<ExistingImageIdentity>

    @Upsert
    protected abstract suspend fun upsertImages(images: List<ImageEntity>)
}

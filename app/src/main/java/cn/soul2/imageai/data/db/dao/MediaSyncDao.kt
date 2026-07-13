package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
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

    @Query("SELECT * FROM media_sync_checkpoint WHERE volume_name = :volumeName LIMIT 1")
    abstract suspend fun getCheckpoint(volumeName: String): MediaSyncCheckpointEntity?

    @Upsert
    abstract suspend fun upsertCheckpoint(checkpoint: MediaSyncCheckpointEntity)

    @Upsert
    abstract suspend fun upsertRun(run: MediaSyncRunEntity)

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
    ) {
        upsertImages(images)
        upsertCheckpoint(checkpoint)
        upsertRun(run)
    }

    @Upsert
    protected abstract suspend fun upsertImages(images: List<ImageEntity>)
}

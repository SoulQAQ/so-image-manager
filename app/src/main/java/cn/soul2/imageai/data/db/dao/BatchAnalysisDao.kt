package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.BatchAnalysisItemEntity
import cn.soul2.imageai.data.db.entity.BatchAnalysisEnqueueResult
import cn.soul2.imageai.data.db.entity.BatchAnalysisRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BatchAnalysisDao {
    @Query("SELECT * FROM batch_analysis_run ORDER BY run_id DESC LIMIT 1")
    abstract fun observeLatest(): Flow<BatchAnalysisRunEntity?>

    @Query("SELECT * FROM batch_analysis_run WHERE state IN ('QUEUED', 'RUNNING') ORDER BY run_id ASC LIMIT 1")
    abstract suspend fun activeRun(): BatchAnalysisRunEntity?

    @Query("SELECT image_local_id FROM batch_analysis_item WHERE run_id = :runId AND state = 'QUEUED' ORDER BY image_local_id ASC LIMIT 1")
    abstract suspend fun nextQueuedImageId(runId: Long): Long?

    @Query("UPDATE batch_analysis_item SET state = 'QUEUED' WHERE run_id = :runId AND state = 'RUNNING'")
    abstract suspend fun recoverInterruptedItem(runId: Long): Int

    @Query("UPDATE batch_analysis_item SET state = 'RUNNING' WHERE run_id = :runId AND image_local_id = :imageLocalId AND state = 'QUEUED'")
    abstract suspend fun claimItem(runId: Long, imageLocalId: Long): Int

    @Query("UPDATE batch_analysis_item SET state = :state, failure_code = :failureCode WHERE run_id = :runId AND image_local_id = :imageLocalId")
    abstract suspend fun finishItem(runId: Long, imageLocalId: Long, state: String, failureCode: String?): Int

    @Query("SELECT COUNT(*) FROM batch_analysis_item WHERE run_id = :runId AND state = 'QUEUED'")
    abstract suspend fun queuedCount(runId: Long): Int

    @Query("SELECT COUNT(*) FROM batch_analysis_item WHERE run_id = :runId AND state = 'SUCCEEDED'")
    abstract suspend fun completedCount(runId: Long): Int

    @Query("SELECT COUNT(*) FROM batch_analysis_item WHERE run_id = :runId AND state = 'FAILED'")
    abstract suspend fun failedCount(runId: Long): Int

    @Insert
    protected abstract suspend fun insertRun(run: BatchAnalysisRunEntity): Long

    @Insert
    protected abstract suspend fun insertItems(items: List<BatchAnalysisItemEntity>)

    @Query("SELECT image_local_id FROM batch_analysis_item WHERE run_id = :runId AND image_local_id IN (:imageIds)")
    protected abstract suspend fun existingItemIds(runId: Long, imageIds: List<Long>): List<Long>

    @Upsert
    abstract suspend fun upsertRun(run: BatchAnalysisRunEntity)

    @Query("UPDATE batch_analysis_run SET state = 'PAUSED', updated_at_epoch_millis = :now WHERE run_id = :runId")
    abstract suspend fun pauseRun(runId: Long, now: Long): Int

    @Transaction
    open suspend fun createRun(imageIds: List<Long>, now: Long): BatchAnalysisEnqueueResult {
        val ids = imageIds.distinct().filter { it > 0L }
        if (ids.isEmpty()) return BatchAnalysisEnqueueResult(null, 0)
        activeRun()?.let { active ->
            val existingIds = existingItemIds(active.runId, ids).toHashSet()
            val addedIds = ids.filterNot(existingIds::contains)
            if (addedIds.isEmpty()) return BatchAnalysisEnqueueResult(active, 0)
            insertItems(addedIds.map { BatchAnalysisItemEntity(active.runId, it, "QUEUED") })
            val updated = active.copy(
                totalCount = active.totalCount + addedIds.size,
                updatedAtEpochMillis = now,
            )
            upsertRun(updated)
            return BatchAnalysisEnqueueResult(updated, addedIds.size)
        }
        val run = BatchAnalysisRunEntity(
            state = "QUEUED", totalCount = ids.size, completedCount = 0, failedCount = 0,
            createdAtEpochMillis = now, updatedAtEpochMillis = now, completedAtEpochMillis = null,
        )
        val runId = insertRun(run)
        insertItems(ids.map { BatchAnalysisItemEntity(runId, it, "QUEUED") })
        return BatchAnalysisEnqueueResult(run.copy(runId = runId), ids.size)
    }

    @Transaction
    open suspend fun refreshRun(run: BatchAnalysisRunEntity, now: Long): BatchAnalysisRunEntity {
        val queued = queuedCount(run.runId)
        val completed = completedCount(run.runId)
        val failed = failedCount(run.runId)
        val state = if (queued == 0) "SUCCEEDED" else "RUNNING"
        val refreshed = run.copy(
            state = state,
            completedCount = completed,
            failedCount = failed,
            updatedAtEpochMillis = now,
            completedAtEpochMillis = if (queued == 0) now else null,
        )
        upsertRun(refreshed)
        return refreshed
    }
}

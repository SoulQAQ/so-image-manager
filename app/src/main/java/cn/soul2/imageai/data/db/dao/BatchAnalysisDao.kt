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

    @Query("SELECT * FROM batch_analysis_run WHERE state IN ('QUEUED', 'RUNNING', 'PAUSED') ORDER BY run_id ASC LIMIT 1")
    protected abstract suspend fun openRun(): BatchAnalysisRunEntity?

    @Query("UPDATE batch_analysis_run SET state = 'QUEUED', pause_reason = NULL, resume_at_epoch_millis = NULL, updated_at_epoch_millis = :now WHERE run_id = :runId AND state = 'PAUSED' AND resume_at_epoch_millis IS NOT NULL AND resume_at_epoch_millis <= :now")
    protected abstract suspend fun resumeTimedRun(runId: Long, now: Long): Int

    @Transaction
    open suspend fun runnableRun(now: Long): BatchAnalysisRunEntity? {
        val run = openRun() ?: return null
        if (run.state != "PAUSED") return run
        val resumeAt = run.resumeAtEpochMillis ?: return null
        if (resumeAt > now || resumeTimedRun(run.runId, now) != 1) return null
        return run.copy(
            state = "QUEUED",
            pauseReason = null,
            resumeAtEpochMillis = null,
            updatedAtEpochMillis = now,
        )
    }

    @Query("SELECT image_local_id FROM batch_analysis_item WHERE run_id = :runId AND state = 'QUEUED' ORDER BY image_local_id ASC LIMIT 1")
    abstract suspend fun nextQueuedImageId(runId: Long): Long?

    @Query("UPDATE batch_analysis_item SET state = 'QUEUED' WHERE run_id = :runId AND state = 'RUNNING'")
    abstract suspend fun recoverInterruptedItem(runId: Long): Int

    @Query("UPDATE batch_analysis_item SET state = 'RUNNING', attempt_count = attempt_count + 1 WHERE run_id = :runId AND image_local_id = :imageLocalId AND state = 'QUEUED'")
    abstract suspend fun claimItem(runId: Long, imageLocalId: Long): Int

    @Query("UPDATE batch_analysis_item SET state = :state, failure_code = :failureCode WHERE run_id = :runId AND image_local_id = :imageLocalId")
    abstract suspend fun finishItem(runId: Long, imageLocalId: Long, state: String, failureCode: String?): Int

    @Query("UPDATE batch_analysis_item SET state = 'QUEUED', failure_code = :failureCode WHERE run_id = :runId AND image_local_id = :imageLocalId")
    abstract suspend fun requeueItem(runId: Long, imageLocalId: Long, failureCode: String?): Int

    @Query("SELECT attempt_count FROM batch_analysis_item WHERE run_id = :runId AND image_local_id = :imageLocalId LIMIT 1")
    abstract suspend fun attemptCount(runId: Long, imageLocalId: Long): Int

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

    @Query("UPDATE batch_analysis_run SET state = 'PAUSED', pause_reason = :reason, resume_at_epoch_millis = :resumeAt, updated_at_epoch_millis = :now WHERE run_id = :runId")
    abstract suspend fun pauseRun(runId: Long, now: Long, reason: String? = null, resumeAt: Long? = null): Int

    @Query("UPDATE batch_analysis_run SET last_provider_id = :providerId, updated_at_epoch_millis = :now WHERE run_id = :runId")
    abstract suspend fun recordProvider(runId: Long, providerId: String?, now: Long): Int

    @Transaction
    open suspend fun createRun(imageIds: List<Long>, now: Long): BatchAnalysisEnqueueResult {
        val ids = imageIds.distinct().filter { it > 0L }
        if (ids.isEmpty()) return BatchAnalysisEnqueueResult(null, 0)
        openRun()?.let { active ->
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

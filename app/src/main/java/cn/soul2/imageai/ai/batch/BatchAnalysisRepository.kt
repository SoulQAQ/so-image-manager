package cn.soul2.imageai.ai.batch

import cn.soul2.imageai.ai.analysis.ImageAnalysisTarget
import cn.soul2.imageai.ai.analysis.SingleImageAnalysisResult
import cn.soul2.imageai.ai.analysis.SingleImageAnalyzer
import cn.soul2.imageai.data.db.dao.BatchAnalysisDao
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.entity.BatchAnalysisEnqueueResult
import cn.soul2.imageai.data.db.entity.BatchAnalysisRunEntity
import cn.soul2.imageai.data.db.entity.ImagePartition
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import kotlinx.coroutines.flow.Flow

class BatchAnalysisRepository(
    private val dao: BatchAnalysisDao,
    private val imageDao: ImageDao,
    private val analyzer: SingleImageAnalyzer,
    private val configurationRepository: AiConfigurationRepository? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeLatest(): Flow<BatchAnalysisRunEntity?> = dao.observeLatest()

    suspend fun enqueueAll(): BatchAnalysisEnqueueResult = enqueue(imageDao.allUnanalyzedAvailableIds())

    suspend fun enqueue(imageIds: List<Long>): BatchAnalysisEnqueueResult =
        dao.createRun(imageIds, now())

    suspend fun runOne(): BatchSliceResult {
        val run = dao.runnableRun(now()) ?: return BatchSliceResult.Idle
        dao.recoverInterruptedItem(run.runId)
        val imageId = dao.nextQueuedImageId(run.runId) ?: return BatchSliceResult.Completed(
            dao.refreshRun(run, now()),
        )
        if (dao.claimItem(run.runId, imageId) != 1) return BatchSliceResult.More
        val image = imageDao.getById(imageId)
        val result = if (image == null || image.availability.name != "AVAILABLE") {
            SingleImageAnalysisResult.Failure(
                cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure.IMAGE_UNAVAILABLE,
            )
        } else {
            analyzer.analyze(ImageAnalysisTarget(image.localId, image.contentUri, image.partition))
        }
        when (result) {
            is SingleImageAnalysisResult.Success -> {
                dao.finishItem(run.runId, imageId, "SUCCEEDED", null)
                dao.recordProvider(run.runId, result.providerId.ifBlank { null }, now())
            }
            is SingleImageAnalysisResult.Failure -> {
                dao.recordProvider(run.runId, result.providerId, now())
                val runtime = configurationRepository?.getRuntimeSetting()
                val attempts = dao.attemptCount(run.runId, imageId)
                if (result.reason.isTemporary() && attempts <= (runtime?.retryLimit ?: DEFAULT_RETRY_LIMIT)) {
                    dao.requeueItem(run.runId, imageId, result.detailCode ?: result.reason.name)
                    val resumeAt = maxOf(
                        result.retryAtEpochMillis ?: 0L,
                        now() + retryDelayMillis(attempts),
                    )
                    val breakerThreshold = runtime?.circuitBreakerThreshold ?: DEFAULT_BREAKER_THRESHOLD
                    if (attempts >= breakerThreshold) {
                        val cooldown = (runtime?.circuitBreakerCooldownMinutes ?: DEFAULT_COOLDOWN_MINUTES)
                            .toLong() * 60_000L
                        val breakerAt = maxOf(resumeAt, now() + cooldown)
                        dao.pauseRun(run.runId, now(), "CIRCUIT_BREAKER", breakerAt)
                        return BatchSliceResult.Deferred(breakerAt)
                    }
                    dao.pauseRun(run.runId, now(), result.reason.name, resumeAt)
                    return BatchSliceResult.Deferred(resumeAt)
                }
                dao.finishItem(run.runId, imageId, "FAILED", result.reason.name)
                if (result.reason == cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure.PROVIDER_REJECTED) {
                    when (image?.partition) {
                        ImagePartition.MAIN,
                        ImagePartition.UNPROCESSED -> imageDao.moveRejectedMainImageToPrivate(imageId)
                        ImagePartition.PRIVATE -> imageDao.markRejectedPrivateImageUnanalyzable(imageId)
                        ImagePartition.PRIVATE_UNANALYZABLE -> Unit
                        null -> Unit
                    }
                }
                if (BatchAnalysisPolicy.pausesRun(result.reason)) {
                    dao.pauseRun(run.runId, now(), result.detailCode ?: result.reason.name, result.retryAtEpochMillis)
                    return BatchSliceResult.Paused
                }
            }
        }
        val refreshed = dao.refreshRun(run, now())
        return if (refreshed.state == "SUCCEEDED") BatchSliceResult.Completed(refreshed)
        else BatchSliceResult.More
    }

    private fun cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure.isTemporary() =
        this == cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure.NETWORK_FAILED ||
            this == cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure.REQUEST_LIMITED

    private fun retryDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 8)
        return (BASE_RETRY_MILLIS shl exponent).coerceAtMost(MAX_RETRY_MILLIS)
    }

    private companion object {
        const val DEFAULT_RETRY_LIMIT = 4
        const val DEFAULT_BREAKER_THRESHOLD = 5
        const val DEFAULT_COOLDOWN_MINUTES = 30
        const val BASE_RETRY_MILLIS = 15_000L
        const val MAX_RETRY_MILLIS = 30 * 60_000L
    }
}

sealed interface BatchSliceResult {
    data object Idle : BatchSliceResult
    data object More : BatchSliceResult
    data class Completed(val run: BatchAnalysisRunEntity) : BatchSliceResult
    data object Paused : BatchSliceResult
    data class Deferred(val resumeAtEpochMillis: Long) : BatchSliceResult
}

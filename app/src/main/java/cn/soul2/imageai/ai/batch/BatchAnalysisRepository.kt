package cn.soul2.imageai.ai.batch

import cn.soul2.imageai.ai.analysis.ImageAnalysisTarget
import cn.soul2.imageai.ai.analysis.SingleImageAnalysisResult
import cn.soul2.imageai.ai.analysis.SingleImageAnalyzer
import cn.soul2.imageai.data.db.dao.BatchAnalysisDao
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.entity.BatchAnalysisRunEntity
import cn.soul2.imageai.data.db.entity.ImagePartition
import kotlinx.coroutines.flow.Flow

class BatchAnalysisRepository(
    private val dao: BatchAnalysisDao,
    private val imageDao: ImageDao,
    private val analyzer: SingleImageAnalyzer,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeLatest(): Flow<BatchAnalysisRunEntity?> = dao.observeLatest()

    suspend fun enqueueAll(): BatchAnalysisRunEntity? = enqueue(imageDao.allUnanalyzedAvailableIds())

    suspend fun enqueue(imageIds: List<Long>): BatchAnalysisRunEntity? =
        dao.createRun(imageIds, now())

    suspend fun runOne(): BatchSliceResult {
        val run = dao.activeRun() ?: return BatchSliceResult.Idle
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
            is SingleImageAnalysisResult.Success -> dao.finishItem(run.runId, imageId, "SUCCEEDED", null)
            is SingleImageAnalysisResult.Failure -> {
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
                    dao.pauseRun(run.runId, now())
                    return BatchSliceResult.Paused
                }
            }
        }
        val refreshed = dao.refreshRun(run, now())
        return if (refreshed.state == "SUCCEEDED") BatchSliceResult.Completed(refreshed)
        else BatchSliceResult.More
    }
}

sealed interface BatchSliceResult {
    data object Idle : BatchSliceResult
    data object More : BatchSliceResult
    data class Completed(val run: BatchAnalysisRunEntity) : BatchSliceResult
    data object Paused : BatchSliceResult
}

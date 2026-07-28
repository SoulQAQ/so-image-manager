package cn.soul2.imageai.gallery

import cn.soul2.imageai.ai.batch.BatchAnalysisRepository
import cn.soul2.imageai.ai.batch.BatchAnalysisScheduler
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.entity.BatchAnalysisEnqueueResult

class GallerySelectionActions(
    private val imageDao: ImageDao,
    private val batchRepository: BatchAnalysisRepository,
    private val batchScheduler: BatchAnalysisScheduler,
) {
    suspend fun removeFromSoim(imageIds: Collection<Long>) {
        imageDao.removeFromSoim(imageIds.distinct())
    }

    suspend fun moveToPrivate(imageIds: Collection<Long>) {
        imageDao.moveMainImagesToPrivate(imageIds)
    }

    suspend fun analyze(imageIds: Collection<Long>): BatchAnalysisEnqueueResult {
        val result = batchRepository.enqueue(imageIds.toList())
        if (result.addedCount > 0) batchScheduler.enqueue()
        return result
    }

    suspend fun analyzeAll(): BatchAnalysisEnqueueResult {
        val result = batchRepository.enqueueAll()
        if (result.addedCount > 0) batchScheduler.enqueue()
        return result
    }
}

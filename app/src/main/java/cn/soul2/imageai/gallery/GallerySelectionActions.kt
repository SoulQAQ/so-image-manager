package cn.soul2.imageai.gallery

import cn.soul2.imageai.ai.batch.BatchAnalysisRepository
import cn.soul2.imageai.ai.batch.BatchAnalysisScheduler
import cn.soul2.imageai.data.db.dao.ImageDao

class GallerySelectionActions(
    private val imageDao: ImageDao,
    private val batchRepository: BatchAnalysisRepository,
    private val batchScheduler: BatchAnalysisScheduler,
) {
    suspend fun removeFromSoim(imageIds: Collection<Long>) {
        imageDao.removeFromSoim(imageIds.distinct())
    }

    suspend fun analyze(imageIds: Collection<Long>) {
        if (batchRepository.enqueue(imageIds.toList()) != null) batchScheduler.enqueue()
    }

    suspend fun analyzeAll() {
        if (batchRepository.enqueueAll() != null) batchScheduler.enqueue()
    }
}

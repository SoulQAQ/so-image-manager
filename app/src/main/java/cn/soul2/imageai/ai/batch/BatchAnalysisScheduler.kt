package cn.soul2.imageai.ai.batch

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BatchAnalysisScheduler(private val workManager: WorkManager) {
    fun enqueue() {
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<BatchAnalysisWorker>().build(),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "soim_batch_ai_analysis"
    }
}

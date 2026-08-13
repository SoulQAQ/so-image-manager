package cn.soul2.imageai.ai.batch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.soul2.imageai.SoImApplication

class BatchAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (
        val result = (applicationContext.applicationContext as SoImApplication)
            .container.batchAnalysisRepository.runOne()
    ) {
        BatchSliceResult.Idle,
        is BatchSliceResult.Completed,
        BatchSliceResult.Paused,
        -> Result.success()
        is BatchSliceResult.Deferred -> {
            (applicationContext.applicationContext as SoImApplication)
                .container.batchAnalysisScheduler.enqueue(result.resumeAtEpochMillis)
            Result.success()
        }
        BatchSliceResult.More -> {
            (applicationContext.applicationContext as SoImApplication)
                .container.batchAnalysisScheduler.enqueue()
            Result.success()
        }
    }
}

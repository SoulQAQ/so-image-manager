package cn.soul2.imageai.media.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.soul2.imageai.SoImApplication

enum class WorkerDirective {
    SUCCESS,
    RETRY,
    CONTINUE,
    PAUSE_ERROR,
}

object MediaSyncWorkerPolicy {
    fun directive(
        result: SliceResult,
        runAttemptCount: Int,
        hasQueuedWork: Boolean = false,
    ): WorkerDirective = when (result) {
        is SliceResult.More -> WorkerDirective.CONTINUE
        is SliceResult.Retry -> if (SyncPolicy.shouldRetry(result.error, runAttemptCount)) {
            WorkerDirective.RETRY
        } else {
            WorkerDirective.PAUSE_ERROR
        }
        is SliceResult.Completed -> if (hasQueuedWork) {
            WorkerDirective.CONTINUE
        } else {
            WorkerDirective.SUCCESS
        }
        is SliceResult.PausedError,
        is SliceResult.PausedPermission,
        -> WorkerDirective.SUCCESS
    }
}

class MediaSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext.applicationContext as SoImApplication).container
        if (inputData.getBoolean(INPUT_PERIODIC_TRIGGER, false)) {
            container.mediaSyncScheduler.requestReconciliation()
            return Result.success()
        }
        val modeValue = inputData.getString(INPUT_MODE)
        val requestedMode = modeValue
            ?.takeUnless { it == MODE_RETRY }
            ?.let { value ->
                runCatching { SyncMode.valueOf(value) }.getOrNull()
                    ?: return Result.failure()
            }
        val result = when {
            runAttemptCount > 0 -> container.mediaSyncEngine.continueNextSlice()
            modeValue == MODE_RETRY -> container.mediaSyncEngine.retryPausedSlice()
            modeValue == null -> container.mediaSyncEngine.continueNextSlice()
            else -> container.mediaSyncEngine.runNextSlice(requireNotNull(requestedMode))
        }
        result ?: return Result.success()
        val hasQueuedWork = result is SliceResult.Completed &&
            container.mediaSyncStore.hasQueuedWork()
        return when (
            MediaSyncWorkerPolicy.directive(
                result,
                runAttemptCount,
                hasQueuedWork = hasQueuedWork,
            )
        ) {
            WorkerDirective.SUCCESS -> Result.success()
            WorkerDirective.RETRY -> Result.retry()
            WorkerDirective.CONTINUE -> {
                container.mediaSyncScheduler.continueScan()
                Result.success()
            }
            WorkerDirective.PAUSE_ERROR -> {
                container.mediaSyncEngine.pauseAfterRetries((result as SliceResult.Retry).error)
                Result.success()
            }
        }
    }

    companion object {
        const val INPUT_MODE = "media_sync_mode"
        const val INPUT_PERIODIC_TRIGGER = "media_sync_periodic_trigger"
        const val MODE_RETRY = "RETRY_PAUSED"
    }
}

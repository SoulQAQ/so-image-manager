package cn.soul2.imageai.media.sync

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

sealed interface ImmediateSyncWork {
    data class RequestedMode(val mode: SyncMode) : ImmediateSyncWork
    data object Coordinator : ImmediateSyncWork
    data object Retry : ImmediateSyncWork
}

interface SyncWorkBackend {
    fun enqueueImmediate(work: ImmediateSyncWork, policy: ExistingWorkPolicy)
    fun enqueuePeriodic(intervalHours: Long)
}

class MediaSyncScheduler(
    private val backend: SyncWorkBackend,
) {
    fun requestInitial() {
        backend.enqueueImmediate(
            ImmediateSyncWork.RequestedMode(SyncMode.INITIAL),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    fun requestIncremental() {
        backend.enqueueImmediate(
            ImmediateSyncWork.RequestedMode(SyncMode.INCREMENTAL),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    fun requestReconciliation() {
        backend.enqueueImmediate(
            ImmediateSyncWork.RequestedMode(SyncMode.RECONCILE),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    fun retry() {
        backend.enqueueImmediate(
            ImmediateSyncWork.Retry,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    fun ensurePeriodicReconciliation() {
        backend.enqueuePeriodic(SyncPolicy.RECONCILIATION_INTERVAL_HOURS)
    }

    internal fun continueScan() {
        backend.enqueueImmediate(
            ImmediateSyncWork.Coordinator,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }
}

class WorkManagerSyncWorkBackend(
    private val workManager: WorkManager,
) : SyncWorkBackend {
    override fun enqueueImmediate(work: ImmediateSyncWork, policy: ExistingWorkPolicy) {
        val modeValue = when (work) {
            is ImmediateSyncWork.RequestedMode -> work.mode.name
            ImmediateSyncWork.Retry -> MediaSyncWorker.MODE_RETRY
            ImmediateSyncWork.Coordinator -> null
        }
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setInputData(
                modeValue?.let { workDataOf(MediaSyncWorker.INPUT_MODE to it) }
                    ?: workDataOf(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(
            SyncPolicy.IMMEDIATE_UNIQUE_WORK_NAME,
            policy,
            request,
        )
    }

    override fun enqueuePeriodic(intervalHours: Long) {
        val request = PeriodicWorkRequestBuilder<MediaSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setInputData(workDataOf(MediaSyncWorker.INPUT_PERIODIC_TRIGGER to true))
            .build()
        workManager.enqueueUniquePeriodicWork(
            SyncPolicy.PERIODIC_UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

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

interface SyncWorkBackend {
    fun enqueueImmediate(mode: SyncMode?, policy: ExistingWorkPolicy)
    fun enqueuePeriodic(intervalHours: Long)
}

class MediaSyncScheduler(
    private val backend: SyncWorkBackend,
) {
    fun requestInitial() {
        backend.enqueueImmediate(SyncMode.INITIAL, ExistingWorkPolicy.KEEP)
    }

    fun requestIncremental() {
        backend.enqueueImmediate(SyncMode.INCREMENTAL, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun requestReconciliation() {
        backend.enqueueImmediate(SyncMode.RECONCILE, ExistingWorkPolicy.KEEP)
    }

    fun retry() {
        backend.enqueueImmediate(mode = null, policy = ExistingWorkPolicy.REPLACE)
    }

    fun ensurePeriodicReconciliation() {
        backend.enqueuePeriodic(SyncPolicy.RECONCILIATION_INTERVAL_HOURS)
    }

    internal fun continueScan(mode: SyncMode) {
        backend.enqueueImmediate(mode, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }
}

class WorkManagerSyncWorkBackend(
    private val workManager: WorkManager,
) : SyncWorkBackend {
    override fun enqueueImmediate(mode: SyncMode?, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setInputData(
                workDataOf(
                    MediaSyncWorker.INPUT_MODE to (mode?.name ?: MediaSyncWorker.MODE_RETRY),
                ),
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

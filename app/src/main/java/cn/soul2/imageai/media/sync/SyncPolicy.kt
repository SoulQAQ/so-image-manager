package cn.soul2.imageai.media.sync

import java.io.IOException

enum class SyncFailureDisposition {
    PAUSE_PERMISSION,
    RETRY_TRANSIENT,
    PAUSE_ERROR,
}

object SyncPolicy {
    const val MAX_ITEMS_PER_SLICE = 1_000
    const val MAX_DURATION_MILLIS = 20_000L
    const val MAX_WORKER_ATTEMPTS = 5
    const val IMMEDIATE_UNIQUE_WORK_NAME = "media-sync-immediate"
    const val PERIODIC_UNIQUE_WORK_NAME = "media-sync-reconciliation"
    const val RECONCILIATION_INTERVAL_HOURS = 24L
    const val OBSERVER_DEBOUNCE_MILLIS = 2_000L
    const val MISSING_CONFIRMATION_MILLIS = 24L * 60L * 60L * 1_000L

    fun classify(error: Throwable): SyncFailureDisposition = when (error) {
        is SecurityException -> SyncFailureDisposition.PAUSE_PERMISSION
        is IOException -> SyncFailureDisposition.RETRY_TRANSIENT
        else -> SyncFailureDisposition.PAUSE_ERROR
    }

    fun shouldRetry(error: Throwable, runAttemptCount: Int): Boolean =
        classify(error) == SyncFailureDisposition.RETRY_TRANSIENT &&
            runAttemptCount < MAX_WORKER_ATTEMPTS - 1
}

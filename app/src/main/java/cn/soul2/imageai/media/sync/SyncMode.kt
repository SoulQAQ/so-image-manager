package cn.soul2.imageai.media.sync

enum class SyncMode {
    INITIAL,
    INCREMENTAL,
    RECONCILE,
}

enum class SyncRunState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PAUSED_PERMISSION,
    PAUSED_ERROR;

    val isActive: Boolean
        get() = this == QUEUED || this == RUNNING
}

data class SyncRun(
    val runId: Long,
    val mode: SyncMode,
    val state: SyncRunState,
    val currentVolumeName: String?,
    val discoveredCount: Int,
    val indexedCount: Int,
    val unavailableCount: Int,
    val errorCode: String?,
    val errorMessage: String?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
) {
    fun withPage(volume: String, imageCount: Int, nowEpochMillis: Long): SyncRun = copy(
        state = SyncRunState.RUNNING,
        currentVolumeName = volume,
        discoveredCount = discoveredCount + imageCount,
        indexedCount = indexedCount + imageCount,
        errorCode = null,
        errorMessage = null,
        updatedAtEpochMillis = nowEpochMillis,
    )

    fun pausedPermission(nowEpochMillis: Long, error: Throwable?): SyncRun = copy(
        state = SyncRunState.PAUSED_PERMISSION,
        errorCode = error?.javaClass?.simpleName ?: "PERMISSION_DENIED",
        errorMessage = error?.message,
        updatedAtEpochMillis = nowEpochMillis,
        completedAtEpochMillis = null,
    )

    fun pausedError(nowEpochMillis: Long, error: Throwable): SyncRun = copy(
        state = SyncRunState.PAUSED_ERROR,
        errorCode = error.javaClass.simpleName,
        errorMessage = error.message,
        updatedAtEpochMillis = nowEpochMillis,
        completedAtEpochMillis = null,
    )

    fun succeeded(nowEpochMillis: Long): SyncRun = copy(
        state = SyncRunState.SUCCEEDED,
        currentVolumeName = null,
        errorCode = null,
        errorMessage = null,
        updatedAtEpochMillis = nowEpochMillis,
        completedAtEpochMillis = nowEpochMillis,
    )

    fun resumed(nowEpochMillis: Long): SyncRun = copy(
        state = SyncRunState.RUNNING,
        errorCode = null,
        errorMessage = null,
        updatedAtEpochMillis = nowEpochMillis,
        completedAtEpochMillis = null,
    )

    fun activated(nowEpochMillis: Long): SyncRun = copy(
        state = SyncRunState.RUNNING,
        updatedAtEpochMillis = nowEpochMillis,
    )

    companion object {
        fun running(runId: Long, mode: SyncMode, nowEpochMillis: Long): SyncRun = SyncRun(
            runId = runId,
            mode = mode,
            state = SyncRunState.RUNNING,
            currentVolumeName = null,
            discoveredCount = 0,
            indexedCount = 0,
            unavailableCount = 0,
            errorCode = null,
            errorMessage = null,
            startedAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            completedAtEpochMillis = null,
        )

        fun queued(runId: Long, mode: SyncMode, nowEpochMillis: Long): SyncRun =
            running(runId, mode, nowEpochMillis).copy(state = SyncRunState.QUEUED)
    }
}

data class SyncCheckpoint(
    val volumeName: String,
    val generation: Long?,
    val mediaStoreVersion: String?,
    val cursorModifiedAtEpochMillis: Long?,
    val cursorMediaStoreId: Long?,
    val completedAtEpochMillis: Long?,
    val fullReconciliationAtEpochMillis: Long?,
)

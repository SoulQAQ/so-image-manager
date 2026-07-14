package cn.soul2.imageai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class TaskSyncMode {
    None,
    Initial,
    Incremental,
    Reconciliation,
    Unknown,
}

enum class TaskSyncStatus {
    Idle,
    Queued,
    Running,
    Succeeded,
    PausedPermission,
    PausedError,
    Unknown,
}

enum class TaskSyncErrorCategory {
    Permission,
    Storage,
    Unexpected,
}

data class TasksUiState(
    val mode: TaskSyncMode = TaskSyncMode.None,
    val status: TaskSyncStatus = TaskSyncStatus.Idle,
    val discoveredCount: Int = 0,
    val indexedCount: Int = 0,
    val unavailableCount: Int = 0,
    val completedAtEpochMillis: Long? = null,
    val errorCategory: TaskSyncErrorCategory? = null,
    val canRetry: Boolean = false,
)

class TasksViewModel(
    syncRuns: Flow<MediaSyncRunEntity?>,
    lastCompletedAt: Flow<Long?>,
    private val onRetry: () -> Unit,
) : ViewModel() {
    val uiState = combine(syncRuns, lastCompletedAt) { run, completedAt ->
        run.toTasksUiState(completedAt)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TasksUiState(),
    )

    fun retry() {
        if (uiState.value.canRetry) onRetry()
    }

    companion object {
        fun factory(
            syncRuns: Flow<MediaSyncRunEntity?>,
            lastCompletedAt: Flow<Long?>,
            onRetry: () -> Unit,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TasksViewModel(
                    syncRuns = syncRuns,
                    lastCompletedAt = lastCompletedAt,
                    onRetry = onRetry,
                )
            }
        }
    }
}

private fun MediaSyncRunEntity?.toTasksUiState(lastCompletedAt: Long?): TasksUiState {
    this ?: return TasksUiState(completedAtEpochMillis = lastCompletedAt)
    val mappedStatus = when (state) {
        "QUEUED" -> TaskSyncStatus.Queued
        "RUNNING" -> TaskSyncStatus.Running
        "SUCCEEDED" -> TaskSyncStatus.Succeeded
        "PAUSED_PERMISSION" -> TaskSyncStatus.PausedPermission
        "PAUSED_ERROR" -> TaskSyncStatus.PausedError
        else -> TaskSyncStatus.Unknown
    }
    val errorCategory = when (mappedStatus) {
        TaskSyncStatus.PausedPermission -> TaskSyncErrorCategory.Permission
        TaskSyncStatus.PausedError -> classifyError(errorCode)
        else -> null
    }
    return TasksUiState(
        mode = when (mode) {
            "INITIAL" -> TaskSyncMode.Initial
            "INCREMENTAL" -> TaskSyncMode.Incremental
            "RECONCILE" -> TaskSyncMode.Reconciliation
            else -> TaskSyncMode.Unknown
        },
        status = mappedStatus,
        discoveredCount = discoveredCount,
        indexedCount = indexedCount,
        unavailableCount = unavailableCount,
        completedAtEpochMillis = lastCompletedAt,
        errorCategory = errorCategory,
        canRetry = mappedStatus == TaskSyncStatus.PausedPermission ||
            mappedStatus == TaskSyncStatus.PausedError,
    )
}

private fun classifyError(errorCode: String?): TaskSyncErrorCategory {
    val normalized = errorCode.orEmpty().lowercase()
    return when {
        normalized.contains("security") || normalized.contains("permission") ->
            TaskSyncErrorCategory.Permission
        normalized.contains("io") || normalized.contains("file") ||
            normalized.contains("disk") || normalized.contains("storage") ->
            TaskSyncErrorCategory.Storage
        else -> TaskSyncErrorCategory.Unexpected
    }
}

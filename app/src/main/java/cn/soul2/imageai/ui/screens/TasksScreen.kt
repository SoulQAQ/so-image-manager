package cn.soul2.imageai.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.soul2.imageai.R
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.Flow

@Composable
fun TasksScreen(
    syncRuns: Flow<MediaSyncRunEntity?>,
    lastCompletedAt: Flow<Long?>,
    onRetry: () -> Unit,
) {
    val tasksViewModel: TasksViewModel = viewModel(
        factory = TasksViewModel.factory(syncRuns, lastCompletedAt, onRetry),
    )
    val uiState by tasksViewModel.uiState.collectAsStateWithLifecycle()
    TasksContent(uiState = uiState, onRetry = tasksViewModel::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksContent(
    uiState: TasksUiState,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("screen_tasks")) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_tasks)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                SectionLabel(R.string.tasks_section_current)
                KeyValueRow(R.string.tasks_mode, stringResource(taskModeRes(uiState.mode)))
                HorizontalDivider()
                KeyValueRow(R.string.tasks_status, stringResource(taskStatusRes(uiState.status)))
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SyncCounts(uiState)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            item {
                val completion = uiState.completedAtEpochMillis?.let { epochMillis ->
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(epochMillis))
                } ?: stringResource(R.string.tasks_never_completed)
                KeyValueRow(R.string.tasks_last_completed, completion)
            }
            uiState.errorCategory?.let { errorCategory ->
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(taskErrorRes(errorCategory)),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (uiState.canRetry) {
                            FilledTonalButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.tasks_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(@StringRes labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        modifier = Modifier.padding(vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun KeyValueRow(@StringRes labelRes: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SyncCounts(uiState: TasksUiState) {
    Row(Modifier.fillMaxWidth()) {
        SyncCount(R.string.tasks_discovered, uiState.discoveredCount, Modifier.weight(1f))
        SyncCount(R.string.tasks_indexed, uiState.indexedCount, Modifier.weight(1f))
        SyncCount(R.string.tasks_unavailable, uiState.unavailableCount, Modifier.weight(1f))
    }
}

@Composable
private fun SyncCount(@StringRes labelRes: Int, count: Int, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(text = count.toString(), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@StringRes
private fun taskModeRes(mode: TaskSyncMode): Int = when (mode) {
    TaskSyncMode.None -> R.string.tasks_mode_none
    TaskSyncMode.Initial -> R.string.tasks_mode_initial
    TaskSyncMode.Incremental -> R.string.tasks_mode_incremental
    TaskSyncMode.Reconciliation -> R.string.tasks_mode_reconciliation
    TaskSyncMode.Unknown -> R.string.tasks_mode_unknown
}

@StringRes
private fun taskStatusRes(status: TaskSyncStatus): Int = when (status) {
    TaskSyncStatus.Idle -> R.string.tasks_status_idle
    TaskSyncStatus.Queued -> R.string.tasks_status_queued
    TaskSyncStatus.Running -> R.string.tasks_status_running
    TaskSyncStatus.Succeeded -> R.string.tasks_status_succeeded
    TaskSyncStatus.PausedPermission -> R.string.tasks_status_paused_permission
    TaskSyncStatus.PausedError -> R.string.tasks_status_paused_error
    TaskSyncStatus.Unknown -> R.string.tasks_status_unknown
}

@StringRes
private fun taskErrorRes(error: TaskSyncErrorCategory): Int = when (error) {
    TaskSyncErrorCategory.Permission -> R.string.tasks_error_permission
    TaskSyncErrorCategory.Storage -> R.string.tasks_error_storage
    TaskSyncErrorCategory.Unexpected -> R.string.tasks_error_unexpected
}

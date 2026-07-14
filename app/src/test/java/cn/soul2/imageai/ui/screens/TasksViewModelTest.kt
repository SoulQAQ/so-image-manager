package cn.soul2.imageai.ui.screens

import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    @Test
    fun runningReconciliationMapsProgressWithoutExposingRetry() = withTestMain {
        val runs = MutableStateFlow(
            syncRun(
                mode = "RECONCILE",
                state = "RUNNING",
                discoveredCount = 19,
                indexedCount = 17,
                unavailableCount = 2,
            ),
        )
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            TasksViewModel.factory(
                syncRuns = runs,
                lastCompletedAt = MutableStateFlow(90L),
                onRetry = {},
            ),
        )[TasksViewModel::class.java]
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }

        runCurrent()

        assertEquals(TaskSyncMode.Reconciliation, viewModel.uiState.value.mode)
        assertEquals(TaskSyncStatus.Running, viewModel.uiState.value.status)
        assertEquals(19, viewModel.uiState.value.discoveredCount)
        assertEquals(17, viewModel.uiState.value.indexedCount)
        assertEquals(2, viewModel.uiState.value.unavailableCount)
        assertEquals(90L, viewModel.uiState.value.completedAtEpochMillis)
        assertNull(viewModel.uiState.value.errorCategory)
        assertFalse(viewModel.uiState.value.canRetry)
        store.clear()
    }

    @Test
    fun completedInitialRunKeepsCompletionTimeAndClearsError() = withTestMain {
        val completedAt = 1_720_598_400_000L
        val viewModel = TasksViewModel(
            syncRuns = MutableStateFlow(
                syncRun(
                    mode = "INITIAL",
                    state = "SUCCEEDED",
                    discoveredCount = 30,
                    indexedCount = 30,
                    completedAtEpochMillis = completedAt,
                ),
            ),
            lastCompletedAt = MutableStateFlow(completedAt),
            onRetry = {},
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }

        runCurrent()

        assertEquals(TaskSyncMode.Initial, viewModel.uiState.value.mode)
        assertEquals(TaskSyncStatus.Succeeded, viewModel.uiState.value.status)
        assertEquals(completedAt, viewModel.uiState.value.completedAtEpochMillis)
        assertNull(viewModel.uiState.value.errorCategory)
        assertFalse(viewModel.uiState.value.canRetry)
    }

    @Test
    fun pausedFailuresAreClassifiedAndOnlyRecoverableStateDispatchesRetry() = withTestMain {
        val runs = MutableStateFlow(
            syncRun(
                state = "PAUSED_PERMISSION",
                errorCode = "SecurityException",
                errorMessage = "revoked",
            ),
        )
        val retries = mutableListOf<Unit>()
        val viewModel = TasksViewModel(
            syncRuns = runs,
            lastCompletedAt = MutableStateFlow(null),
            onRetry = { retries += Unit },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }

        runCurrent()

        assertEquals(TaskSyncStatus.PausedPermission, viewModel.uiState.value.status)
        assertEquals(TaskSyncErrorCategory.Permission, viewModel.uiState.value.errorCategory)
        assertTrue(viewModel.uiState.value.canRetry)
        viewModel.retry()
        assertEquals(1, retries.size)

        runs.value = syncRun(
            state = "PAUSED_ERROR",
            errorCode = "IOException",
            errorMessage = "disk unavailable",
        )
        runCurrent()

        assertEquals(TaskSyncErrorCategory.Storage, viewModel.uiState.value.errorCategory)
        assertTrue(viewModel.uiState.value.canRetry)

        runs.value = syncRun(state = "RUNNING")
        runCurrent()
        viewModel.retry()

        assertEquals(1, retries.size)
    }

    private fun withTestMain(testBody: suspend TestScope.() -> Unit) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        try {
            TestScope(dispatcher).runTest { testBody() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun syncRun(
        mode: String = "INCREMENTAL",
        state: String,
        discoveredCount: Int = 0,
        indexedCount: Int = 0,
        unavailableCount: Int = 0,
        errorCode: String? = null,
        errorMessage: String? = null,
        completedAtEpochMillis: Long? = null,
    ) = MediaSyncRunEntity(
        runId = 7L,
        mode = mode,
        state = state,
        currentVolumeName = "external_primary",
        discoveredCount = discoveredCount,
        indexedCount = indexedCount,
        unavailableCount = unavailableCount,
        errorCode = errorCode,
        errorMessage = errorMessage,
        startedAtEpochMillis = 100L,
        updatedAtEpochMillis = 200L,
        completedAtEpochMillis = completedAtEpochMillis,
    )
}

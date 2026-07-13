package cn.soul2.imageai.ui.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.permission.GalleryPermissionStateMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryAccessViewModelTest {
    @Test
    fun `partial permission result invokes access callback exactly once`() = withTestMain {
        val store = ViewModelStore()
        val onboardingStore = FakeGalleryOnboardingStore()
        val viewModel = createViewModel(
            store = store,
            permissionMonitor = FakeGalleryPermissionStateMonitor(GalleryAccessState.Partial),
            onboardingStore = onboardingStore,
        )
        var callbackCount = 0

        try {
            viewModel.onPermissionResult(canRequestAgain = false) { accessState ->
                assertEquals(GalleryAccessState.Partial, accessState)
                callbackCount += 1
            }
            advanceUntilIdle()

            assertEquals(1, callbackCount)
            assertEquals(1, onboardingStore.markHandledCalls)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `dismissing recovery marks handled once and clears recovery`() = withTestMain {
        val store = ViewModelStore()
        val onboardingStore = FakeGalleryOnboardingStore()
        val viewModel = createViewModel(
            store = store,
            permissionMonitor = FakeGalleryPermissionStateMonitor(
                GalleryAccessState.Denied(canRequestAgain = true),
            ),
            onboardingStore = onboardingStore,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }

        try {
            runCurrent()
            viewModel.onPermissionResult(canRequestAgain = true)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isPermissionRecovery)
            onboardingStore.resetMarkHandledCalls()

            viewModel.dismissOnboarding()
            advanceUntilIdle()

            assertEquals(1, onboardingStore.markHandledCalls)
            assertFalse(viewModel.uiState.value.isPermissionRecovery)
        } finally {
            store.clear()
        }
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

    private fun createViewModel(
        store: ViewModelStore,
        permissionMonitor: GalleryPermissionStateMonitor,
        onboardingStore: GalleryOnboardingStore,
    ): GalleryAccessViewModel = ViewModelProvider(
        store,
        GalleryAccessViewModel.factory(permissionMonitor, onboardingStore),
    )[GalleryAccessViewModel::class.java]

    private class FakeGalleryPermissionStateMonitor(
        initialState: GalleryAccessState,
    ) : GalleryPermissionStateMonitor {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<GalleryAccessState> = mutableState.asStateFlow()

        override fun refresh(canRequestAgain: Boolean) {
            val current = mutableState.value
            if (current is GalleryAccessState.Denied) {
                mutableState.value = GalleryAccessState.Denied(canRequestAgain)
            }
        }
    }

    private class FakeGalleryOnboardingStore : GalleryOnboardingStore {
        private val handled = MutableStateFlow(false)
        private val requested = MutableStateFlow(false)

        override val isHandled: Flow<Boolean> = handled
        override val isPermissionRequested: Flow<Boolean> = requested

        var markHandledCalls: Int = 0
            private set

        override suspend fun markHandled() {
            markHandledCalls += 1
            handled.value = true
        }

        override suspend fun markPermissionRequested() {
            requested.value = true
        }

        fun resetMarkHandledCalls() {
            markHandledCalls = 0
        }
    }
}

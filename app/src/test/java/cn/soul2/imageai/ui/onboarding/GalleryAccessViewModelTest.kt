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
    fun `first grant updates state without invoking explicit selection callback`() = withTestMain {
        val store = ViewModelStore()
        val onboardingStore = FakeGalleryOnboardingStore()
        val permissionMonitor = FakeGalleryPermissionStateMonitor(
            initialState = GalleryAccessState.Denied(canRequestAgain = true),
            refreshedState = GalleryAccessState.Partial,
        )
        val viewModel = createViewModel(
            store = store,
            permissionMonitor = permissionMonitor,
            onboardingStore = onboardingStore,
        )
        val passiveObservations = mutableListOf<GalleryAccessState>()
        var callbackCount = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            permissionMonitor.state.collect { access ->
                if (access !is GalleryAccessState.Denied) passiveObservations += access
            }
        }

        try {
            runCurrent()
            viewModel.onPermissionResult(canRequestAgain = false) { callbackCount += 1 }
            advanceUntilIdle()

            assertEquals(GalleryAccessState.Partial, permissionMonitor.state.value)
            assertEquals(listOf(GalleryAccessState.Partial), passiveObservations)
            assertEquals(0, callbackCount)
            assertEquals(1, onboardingStore.markHandledCalls)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `partial reselection invokes explicit selection callback exactly once`() = withTestMain {
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
    fun `partial to full result invokes explicit selection callback exactly once`() = withTestMain {
        val store = ViewModelStore()
        val onboardingStore = FakeGalleryOnboardingStore()
        val permissionMonitor = FakeGalleryPermissionStateMonitor(
            initialState = GalleryAccessState.Partial,
            refreshedState = GalleryAccessState.Full,
        )
        val viewModel = createViewModel(
            store = store,
            permissionMonitor = permissionMonitor,
            onboardingStore = onboardingStore,
        )
        val callbacks = mutableListOf<GalleryAccessState>()

        try {
            viewModel.onPermissionResult(canRequestAgain = false, callbacks::add)
            advanceUntilIdle()

            assertEquals(GalleryAccessState.Full, permissionMonitor.state.value)
            assertEquals(listOf(GalleryAccessState.Full), callbacks)
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
        private val refreshedState: GalleryAccessState = initialState,
    ) : GalleryPermissionStateMonitor {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<GalleryAccessState> = mutableState.asStateFlow()

        override fun refresh(canRequestAgain: Boolean) {
            mutableState.value = if (refreshedState is GalleryAccessState.Denied) {
                GalleryAccessState.Denied(canRequestAgain)
            } else {
                refreshedState
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

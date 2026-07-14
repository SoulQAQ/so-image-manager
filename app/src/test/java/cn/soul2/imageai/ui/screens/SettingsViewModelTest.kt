package cn.soul2.imageai.ui.screens

import androidx.paging.PagingData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun combinesPermissionAndRealRepositoryCountsAcrossUpdates() = withTestMain {
        val access = MutableStateFlow<GalleryAccessState>(GalleryAccessState.Partial)
        val repository = CountingGalleryRepository(initialCount = 12)
        val unavailableCounts = MutableStateFlow(3)
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            SettingsViewModel.factory(
                galleryAccessStates = access,
                repository = repository,
                unavailableCounts = unavailableCounts,
            ),
        )[SettingsViewModel::class.java]
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }

        runCurrent()

        assertEquals(SettingsPermissionLabel.Partial, viewModel.uiState.value.permissionLabel)
        assertEquals(12, viewModel.uiState.value.indexedCount)
        assertEquals(3, viewModel.uiState.value.unavailableCount)

        access.value = GalleryAccessState.Full
        repository.count.value = 15
        unavailableCounts.value = 4
        runCurrent()

        assertEquals(SettingsPermissionLabel.Full, viewModel.uiState.value.permissionLabel)
        assertEquals(15, viewModel.uiState.value.indexedCount)
        assertEquals(4, viewModel.uiState.value.unavailableCount)

        access.value = GalleryAccessState.Denied(canRequestAgain = false)
        runCurrent()

        assertEquals(SettingsPermissionLabel.Denied, viewModel.uiState.value.permissionLabel)
        store.clear()
    }

    @Test
    fun settingsActionsEmitTypedCommandsInOrder() = withTestMain {
        val viewModel = SettingsViewModel(
            galleryAccessStates = flowOf(GalleryAccessState.Full),
            repository = CountingGalleryRepository(initialCount = 0),
            unavailableCounts = flowOf(0),
        )
        val commands = mutableListOf<SettingsCommand>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.commands.take(3).toList(commands)
        }

        runCurrent()

        viewModel.reselectPhotos()
        viewModel.rescan()
        viewModel.openSystemSettings()
        runCurrent()

        assertEquals(
            listOf(
                SettingsCommand.ReselectPhotos,
                SettingsCommand.Rescan,
                SettingsCommand.OpenSystemSettings,
            ),
            commands,
        )
        collection.join()
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

    private class CountingGalleryRepository(initialCount: Int) : GalleryRepository {
        val count = MutableStateFlow(initialCount)

        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> =
            flowOf(PagingData.empty())

        override fun observeCount(): Flow<Int> = count

        override fun observeImage(localId: Long): Flow<GalleryImage?> = flowOf(null)
    }
}

package cn.soul2.imageai.ui.gallery

import androidx.paging.PagingData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GallerySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
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
class GalleryViewModelTest {
    @Test
    fun homeUsesRecentWhileLibraryUsesAll() = withTestMain {
        val repository = FakeGalleryRepository()
        val homeStore = ViewModelStore()
        val libraryStore = ViewModelStore()

        ViewModelProvider(homeStore, HomeViewModel.factory(repository, flowOf(null)))[
            HomeViewModel::class.java
        ]
        ViewModelProvider(libraryStore, LibraryViewModel.factory(repository, flowOf(null)))[
            LibraryViewModel::class.java
        ]

        assertEquals(
            listOf(GalleryQuery(GallerySource.Recent), GalleryQuery(GallerySource.All)),
            repository.queries,
        )
        homeStore.clear()
        libraryStore.clear()
    }

    @Test
    fun detailObservesRequestedLocalIdAndTransitionsFromLoadingToReady() = withTestMain {
        val expected = galleryImage(42L)
        val repository = FakeGalleryRepository(detail = MutableStateFlow(expected))
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(store, ImageDetailViewModel.factory(repository, 42L))[
            ImageDetailViewModel::class.java
        ]
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }

        runCurrent()

        assertEquals(listOf(42L), repository.detailIds)
        assertEquals(ImageDetailUiState.Ready(expected), viewModel.uiState.value)
        store.clear()
    }

    @Test
    fun detailReportsMissingWhenTheIndexedRowIsUnavailable() = withTestMain {
        val repository = FakeGalleryRepository(detail = MutableStateFlow(null))
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(store, ImageDetailViewModel.factory(repository, 9L))[
            ImageDetailViewModel::class.java
        ]
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }

        runCurrent()

        assertEquals(ImageDetailUiState.Missing, viewModel.uiState.value)
        store.clear()
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

    private class FakeGalleryRepository(
        private val detail: MutableStateFlow<GalleryImage?> = MutableStateFlow(null),
    ) : GalleryRepository {
        val queries = mutableListOf<GalleryQuery>()
        val detailIds = mutableListOf<Long>()

        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> {
            queries += query
            return flowOf(PagingData.empty())
        }

        override fun observeCount(): Flow<Int> = flowOf(0)

        override fun observeImage(localId: Long): Flow<GalleryImage?> {
            detailIds += localId
            return detail
        }
    }

    private fun galleryImage(localId: Long) = GalleryImage(
        localId = localId,
        contentUri = "content://media/external/images/media/$localId",
        displayName = "$localId.jpg",
        mimeType = "image/jpeg",
        width = 100,
        height = 200,
        sizeBytes = 1_024L,
        capturedAtEpochMillis = 1L,
        addedAtEpochMillis = 1L,
        modifiedAtEpochMillis = 1L,
        bucketName = "相机",
        isFavorite = false,
    )
}

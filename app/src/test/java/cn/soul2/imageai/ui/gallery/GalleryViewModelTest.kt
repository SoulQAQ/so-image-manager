package cn.soul2.imageai.ui.gallery

import androidx.paging.PagingData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cn.soul2.imageai.ai.analysis.ImageAnalysisTarget
import cn.soul2.imageai.ai.analysis.SingleImageAnalysisResult
import cn.soul2.imageai.ai.analysis.SingleImageAnalyzer
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GalleryImageWindow
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
    fun gallerySyncStateKeepsUpdatingWhileItsTabIsOffScreen() = withTestMain {
        val syncRuns = MutableStateFlow<MediaSyncRunEntity?>(syncRun("RUNNING"))
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            HomeViewModel.factory(FakeGalleryRepository(), syncRuns),
        )[HomeViewModel::class.java]

        runCurrent()
        assertEquals(true, viewModel.uiState.value.isSyncing)

        syncRuns.value = syncRun("SUCCEEDED")
        runCurrent()

        assertEquals(false, viewModel.uiState.value.isSyncing)
        store.clear()
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
        assertEquals(
            ImageDetailUiState.Ready(GalleryImageWindow(null, expected, null)),
            viewModel.uiState.value,
        )
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

    @Test
    fun detailMovesToAdjacentImagesWithoutBuildingAnInMemoryLibrary() = withTestMain {
        val first = galleryImage(1L)
        val second = galleryImage(2L)
        val repository = FakeGalleryRepository(
            windows = mapOf(
                1L to GalleryImageWindow(previous = null, current = first, next = second),
                2L to GalleryImageWindow(previous = first, current = second, next = null),
            ),
        )
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(store, ImageDetailViewModel.factory(repository, 1L))[
            ImageDetailViewModel::class.java
        ]
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }

        runCurrent()
        viewModel.showImage(2L)
        runCurrent()

        assertEquals(listOf(1L, 2L), repository.windowIds)
        assertEquals(
            ImageDetailUiState.Ready(requireNotNull(repository.windows[2L])),
            viewModel.uiState.value,
        )
        store.clear()
    }

    @Test
    fun detailAnalyzesTheCurrentlySelectedImage() = withTestMain {
        val image = galleryImage(42L)
        val repository = FakeGalleryRepository(detail = MutableStateFlow(image))
        var receivedTarget: ImageAnalysisTarget? = null
        val analyzer = SingleImageAnalyzer { target ->
            receivedTarget = target
            SingleImageAnalysisResult.Success("analysis-1", 1L)
        }
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            ImageDetailViewModel.factory(repository, image.localId, analyzer),
        )[ImageDetailViewModel::class.java]

        viewModel.analyzeCurrentImage()
        runCurrent()

        assertEquals(ImageAnalysisTarget(image.localId, image.contentUri), receivedTarget)
        assertEquals(ImageAnalysisUiState.Success(image.localId), viewModel.analysisState.value)
        store.clear()
    }

    @Test
    fun detailPagerRecentersCurrentImageWhenAnAdjacentImageDisappears() {
        val previous = galleryImage(1L)
        val current = galleryImage(2L)
        val next = galleryImage(3L)
        val withPrevious = GalleryImageWindow(previous, current, next)
        val withoutPrevious = GalleryImageWindow(null, current, next)

        assertEquals(1, detailPagerModel(withPrevious).currentPage)
        assertEquals(0, detailPagerModel(withoutPrevious).currentPage)
        assertEquals(false, detailPagerModel(withPrevious).stateKey == detailPagerModel(withoutPrevious).stateKey)
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
        val windows: Map<Long, GalleryImageWindow> = emptyMap(),
    ) : GalleryRepository {
        val queries = mutableListOf<GalleryQuery>()
        val detailIds = mutableListOf<Long>()
        val windowIds = mutableListOf<Long>()

        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> {
            queries += query
            return flowOf(PagingData.empty())
        }

        override fun observeCount(): Flow<Int> = flowOf(0)

        override fun observeImage(localId: Long): Flow<GalleryImage?> {
            detailIds += localId
            return detail
        }

        override fun observeImageWindow(localId: Long): Flow<GalleryImageWindow?> {
            windowIds += localId
            return windows[localId]?.let(::flowOf) ?: super.observeImageWindow(localId)
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

    private fun syncRun(state: String) = MediaSyncRunEntity(
        runId = 1L,
        mode = "INCREMENTAL",
        state = state,
        currentVolumeName = "external",
        discoveredCount = 1,
        indexedCount = 1,
        unavailableCount = 0,
        errorCode = null,
        errorMessage = null,
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        completedAtEpochMillis = 2L.takeIf { state == "SUCCEEDED" },
    )
}

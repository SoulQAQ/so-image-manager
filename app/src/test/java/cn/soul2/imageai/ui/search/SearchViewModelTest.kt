package cn.soul2.imageai.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.search.ImageSearchRepository
import cn.soul2.imageai.search.SearchField
import cn.soul2.imageai.search.SearchProgress
import cn.soul2.imageai.search.SearchRequest
import cn.soul2.imageai.search.SearchResult
import cn.soul2.imageai.search.SearchStage
import cn.soul2.imageai.search.SearchTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test
    fun queryWaits250MillisAndClearReturnsToIdle() = withTestMain { dispatcher ->
        val search = FakeSearchRepository()
        val viewModel = SearchViewModel(
            SavedStateHandle(),
            search,
            FakeGalleryRepository(),
            dispatcher,
        )

        viewModel.onQueryChanged("cat")
        advanceTimeBy(249)
        runCurrent()
        assertTrue(search.requests.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals("cat", search.requests.single().rawQuery)

        viewModel.clearQuery()
        runCurrent()
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertFalse(viewModel.uiState.value.isRefining)
    }

    @Test
    fun replacementQueryDiscardsObsoleteGenerationEmissions() = withTestMain { dispatcher ->
        val search = FakeSearchRepository()
        val gallery = FakeGalleryRepository(
            mapOf(1L to image(1), 2L to image(2)),
        )
        val viewModel = SearchViewModel(
            SavedStateHandle(),
            search,
            gallery,
            dispatcher,
        )

        viewModel.onQueryChanged("cat")
        advanceTimeBy(250)
        runCurrent()
        val first = search.requests.single()

        viewModel.onQueryChanged("dog")
        advanceTimeBy(250)
        runCurrent()
        val second = search.requests.last()
        search.flow(second.generation).emit(progress(second, 2))
        runCurrent()
        search.flow(first.generation).emit(progress(first, 1))
        runCurrent()

        assertEquals("dog", viewModel.uiState.value.query)
        assertEquals(listOf(2L), viewModel.uiState.value.items.map { it.image.localId })
    }

    @Test
    fun savedStateRestoresQueryAndHydratesSearchResult() = withTestMain { dispatcher ->
        val savedState = SavedStateHandle(mapOf("search_query" to "重庆"))
        val search = FakeSearchRepository()
        val viewModel = SearchViewModel(
            savedState,
            search,
            FakeGalleryRepository(mapOf(3L to image(3))),
            dispatcher,
        )

        advanceTimeBy(250)
        runCurrent()
        val request = search.requests.single()
        search.flow(request.generation).emit(progress(request, 3))
        runCurrent()

        assertEquals("重庆", viewModel.uiState.value.query)
        assertEquals(3L, viewModel.uiState.value.items.single().image.localId)
        viewModel.onQueryChanged("上海")
        assertEquals("上海", savedState.get<String>("search_query"))
    }

    private class FakeSearchRepository : ImageSearchRepository {
        val requests = mutableListOf<SearchRequest>()
        private val flows = mutableMapOf<Long, MutableSharedFlow<SearchProgress>>()

        override fun search(request: SearchRequest): Flow<SearchProgress> {
            requests += request
            return flow(request.generation)
        }

        fun flow(generation: Long): MutableSharedFlow<SearchProgress> =
            flows.getOrPut(generation) { MutableSharedFlow(extraBufferCapacity = 4) }
    }

    private class FakeGalleryRepository(
        private val images: Map<Long, GalleryImage> = emptyMap(),
    ) : GalleryRepository {
        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> =
            flowOf(PagingData.empty())

        override fun observeCount(): Flow<Int> = flowOf(images.size)

        override fun observeImage(localId: Long): Flow<GalleryImage?> = flowOf(images[localId])
    }

    private fun progress(request: SearchRequest, imageId: Long) = SearchProgress(
        generation = request.generation,
        items = listOf(
            SearchResult(
                imageLocalId = imageId,
                tier = SearchTier.FTS4,
                field = SearchField.CAPTION,
                fieldWeight = 300.0,
                matchScore = 1.0,
                sortTimeEpochMillis = imageId,
                mediaStoreId = imageId,
                volumeName = "external",
                stableLocalId = imageId,
                reason = "描述匹配",
            ),
        ),
        completedStages = setOf(SearchStage.STRUCTURED, SearchStage.FTS4),
        isRefining = false,
        partialReasons = emptySet(),
    )

    private fun image(id: Long) = GalleryImage(
        localId = id,
        contentUri = "content://media/$id",
        displayName = "photo-$id.jpg",
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        sizeBytes = 1_000,
        capturedAtEpochMillis = null,
        addedAtEpochMillis = id,
        modifiedAtEpochMillis = id,
        bucketName = "测试",
        isFavorite = false,
    )

    private fun withTestMain(
        testBody: suspend TestScope.(TestDispatcher) -> Unit,
    ) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        try {
            TestScope(dispatcher).runTest { testBody(dispatcher) }
        } finally {
            Dispatchers.resetMain()
        }
    }
}

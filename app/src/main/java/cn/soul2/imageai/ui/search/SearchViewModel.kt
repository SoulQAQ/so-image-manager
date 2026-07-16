package cn.soul2.imageai.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.search.ImageSearchRepository
import cn.soul2.imageai.search.SearchPartialReason
import cn.soul2.imageai.search.SearchProgress
import cn.soul2.imageai.search.SearchRequest
import cn.soul2.imageai.search.SearchResult
import cn.soul2.imageai.search.SearchValidationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SearchResultItem(
    val image: GalleryImage,
    val hit: SearchResult,
)

enum class SearchUiError {
    QUERY_TOO_LONG,
    SEARCH_FAILED,
}

data class SearchUiState(
    val query: String = "",
    val items: List<SearchResultItem> = emptyList(),
    val isRefining: Boolean = false,
    val partialReasons: Set<SearchPartialReason> = emptySet(),
    val error: SearchUiError? = null,
) {
    val isIdle: Boolean get() = query.isBlank()
    val isEmptyResult: Boolean get() = query.isNotBlank() && !isRefining && items.isEmpty() && error == null
    val requiresRebuild: Boolean
        get() = SearchPartialReason.REBUILD_REQUIRED in partialReasons
}

private data class SearchExecution(
    val query: String,
    val items: List<SearchResultItem> = emptyList(),
    val isRefining: Boolean = false,
    val partialReasons: Set<SearchPartialReason> = emptySet(),
    val error: SearchUiError? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val searchRepository: ImageSearchRepository,
    private val galleryRepository: GalleryRepository,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val query = MutableStateFlow(savedStateHandle[QUERY_KEY] ?: "")
    private val generation = AtomicLong(0L)

    private val execution = query
        .flatMapLatest { rawQuery ->
            if (rawQuery.isBlank()) {
                flowOf(SearchExecution(rawQuery))
            } else {
                flow {
                    delay(QUERY_DEBOUNCE_MILLIS)
                    emit(rawQuery)
                }.flatMapLatest(::executeSearch)
            }
        }
        .flowOn(workerDispatcher)

    val uiState = combine(query, execution) { currentQuery, currentExecution ->
        if (currentExecution.query != currentQuery) {
            SearchUiState(
                query = currentQuery,
                isRefining = currentQuery.isNotBlank(),
            )
        } else {
            SearchUiState(
                query = currentQuery,
                items = currentExecution.items,
                isRefining = currentExecution.isRefining,
                partialReasons = currentExecution.partialReasons,
                error = currentExecution.error,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SearchUiState(query = query.value, isRefining = query.value.isNotBlank()),
    )

    fun onQueryChanged(value: String) {
        savedStateHandle[QUERY_KEY] = value
        query.value = value
    }

    fun clearQuery() = onQueryChanged("")

    private fun executeSearch(rawQuery: String): Flow<SearchExecution> {
        val request = SearchRequest(
            rawQuery = rawQuery,
            generation = generation.getAndIncrement(),
        )
        return searchRepository.search(request)
            .flatMapLatest { progress -> hydrate(rawQuery, progress) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(
                    SearchExecution(
                        query = rawQuery,
                        error = if (error is SearchValidationException) {
                            SearchUiError.QUERY_TOO_LONG
                        } else {
                            SearchUiError.SEARCH_FAILED
                        },
                    ),
                )
            }
    }

    private fun hydrate(rawQuery: String, progress: SearchProgress): Flow<SearchExecution> {
        if (progress.items.isEmpty()) {
            return flowOf(progress.toExecution(rawQuery, emptyList()))
        }
        val itemFlows: List<Flow<SearchResultItem?>> = progress.items.map { hit ->
            galleryRepository.observeImage(hit.imageLocalId).map { image ->
                image?.let { SearchResultItem(it, hit) }
            }
        }
        return combine(itemFlows) { values ->
            progress.toExecution(rawQuery, values.filterNotNull())
        }
    }

    private fun SearchProgress.toExecution(
        rawQuery: String,
        hydrated: List<SearchResultItem>,
    ) = SearchExecution(
        query = rawQuery,
        items = hydrated,
        isRefining = isRefining,
        partialReasons = partialReasons,
    )

    companion object {
        private const val QUERY_KEY = "search_query"
        private const val QUERY_DEBOUNCE_MILLIS = 250L

        fun factory(
            searchRepository: ImageSearchRepository,
            galleryRepository: GalleryRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    createSavedStateHandle(),
                    searchRepository,
                    galleryRepository,
                )
            }
        }
    }
}

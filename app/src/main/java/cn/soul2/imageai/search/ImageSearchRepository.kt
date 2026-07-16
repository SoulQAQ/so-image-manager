package cn.soul2.imageai.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

fun interface ImageSearchRepository {
    fun search(request: SearchRequest): Flow<SearchProgress>

    companion object {
        val Empty = ImageSearchRepository { request ->
            flowOf(
                SearchProgress(
                    generation = request.generation,
                    items = emptyList(),
                    completedStages = SearchStage.entries.toSet(),
                    isRefining = false,
                    partialReasons = emptySet(),
                ),
            )
        }
    }
}

package cn.soul2.imageai.search

import kotlinx.coroutines.flow.Flow

fun interface ImageSearchRepository {
    fun search(request: SearchRequest): Flow<SearchProgress>
}

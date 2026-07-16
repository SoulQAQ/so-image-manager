package cn.soul2.imageai.search

data class SearchRequest(
    val rawQuery: String,
    val generation: Long,
    val pageSize: Int = 40,
)

data class SearchProgress(
    val generation: Long,
    val items: List<SearchResult>,
    val completedStages: Set<SearchStage>,
    val isRefining: Boolean,
    val partialReasons: Set<SearchPartialReason>,
)

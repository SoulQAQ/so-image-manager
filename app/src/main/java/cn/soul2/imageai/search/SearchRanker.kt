package cn.soul2.imageai.search

object SearchRanker {
    private val comparator = compareBy<SearchResult> { it.tier.ordinal }
        .thenByDescending { it.fieldWeight }
        .thenByDescending { it.matchScore }
        .thenByDescending { it.sortTimeEpochMillis }
        .thenByDescending { it.mediaStoreId }
        .thenByDescending { it.stableLocalId }

    fun rank(candidates: Collection<SearchResult>): List<SearchResult> = candidates
        .sortedWith(comparator)
        .distinctBy(SearchResult::imageLocalId)
}

package cn.soul2.imageai.search

data class SearchResult(
    val imageLocalId: Long,
    val tier: SearchTier,
    val field: SearchField,
    val fieldWeight: Double,
    val matchScore: Double,
    val sortTimeEpochMillis: Long,
    val mediaStoreId: Long,
    val volumeName: String,
    val stableLocalId: Long,
    val reason: String,
)

package cn.soul2.imageai.media.store

data class MediaStoreCursor(
    val modifiedAtEpochMillis: Long?,
    val mediaStoreId: Long,
    val generation: Long?,
)

data class MediaStorePage(
    val images: List<MediaStoreImage>,
    val nextCursor: MediaStoreCursor?,
    val hasMore: Boolean,
    val observedGeneration: Long?,
    val observedVersion: String?,
)

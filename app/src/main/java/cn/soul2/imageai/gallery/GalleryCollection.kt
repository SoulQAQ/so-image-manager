package cn.soul2.imageai.gallery

import androidx.room.ColumnInfo

enum class GalleryCollectionType {
    ALBUM,
    TAG,
    CATEGORY,
}

data class GalleryCollectionSummary(
    @ColumnInfo(name = "collection_key")
    val key: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "image_count")
    val imageCount: Int,
    @ColumnInfo(name = "cover_uri")
    val coverUri: String?,
)

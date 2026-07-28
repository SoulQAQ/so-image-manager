package cn.soul2.imageai.gallery

sealed interface GallerySource {
    data object Recent : GallerySource
    data object All : GallerySource
    data object Analyzed : GallerySource
    data object Unanalyzed : GallerySource
    data object Private : GallerySource
    data object PrivateUnanalyzable : GallerySource
    data object Rejected : GallerySource
    data class Album(val bucketId: Long?, val bucketName: String) : GallerySource
    data class Tag(val normalizedKey: String) : GallerySource
    data class Category(val normalizedKey: String) : GallerySource
}

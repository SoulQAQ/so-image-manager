package cn.soul2.imageai.gallery

enum class GallerySort { NEWEST, NAME, SIZE }

data class GalleryQuery(
    val source: GallerySource,
    val sort: GallerySort = GallerySort.NEWEST,
)

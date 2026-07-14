package cn.soul2.imageai.gallery

sealed interface GallerySource {
    data object Recent : GallerySource
    data object All : GallerySource
}

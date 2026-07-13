package cn.soul2.imageai.media.permission

sealed interface GalleryAccessState {
    data object Full : GalleryAccessState

    data object Partial : GalleryAccessState

    data class Denied(val canRequestAgain: Boolean) : GalleryAccessState
}

package cn.soul2.imageai.ui.gallery

import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.media.permission.GalleryAccessState

enum class GalleryLayout {
    Waterfall,
    Grid,
}

sealed interface GalleryContentState {
    data object Loading : GalleryContentState
    data object NoPermission : GalleryContentState
    data class Syncing(val indexedCount: Int) : GalleryContentState
    data object Empty : GalleryContentState
    data object Content : GalleryContentState
}

data class GalleryUiState(
    val availableCount: Int?,
    val syncRun: MediaSyncRunEntity?,
) {
    val isSyncing: Boolean
        get() = syncRun?.state == "QUEUED" || syncRun?.state == "RUNNING"

    fun contentState(accessState: GalleryAccessState): GalleryContentState = when {
        accessState is GalleryAccessState.Denied -> GalleryContentState.NoPermission
        availableCount == null -> GalleryContentState.Loading
        availableCount > 0 -> GalleryContentState.Content
        isSyncing -> GalleryContentState.Syncing(syncRun?.indexedCount ?: 0)
        else -> GalleryContentState.Empty
    }
}

fun galleryColumnCount(layout: GalleryLayout, widthDp: Int): Int = when (layout) {
    GalleryLayout.Waterfall -> if (widthDp < 600) 2 else 3
    GalleryLayout.Grid -> if (widthDp < 600) 3 else 5
}

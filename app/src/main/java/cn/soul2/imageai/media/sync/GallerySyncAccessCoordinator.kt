package cn.soul2.imageai.media.sync

import cn.soul2.imageai.media.permission.GalleryAccessState

object GallerySyncAccessPolicy {
    @JvmStatic
    fun mode(hasPersistedBaseline: Boolean): SyncMode =
        if (hasPersistedBaseline) SyncMode.INCREMENTAL else SyncMode.INITIAL
}

class GallerySyncAccessCoordinator(
    private val store: MediaSyncStore,
    private val scheduler: MediaSyncScheduler,
) {
    suspend fun onAccessAvailable(access: GalleryAccessState) {
        if (access is GalleryAccessState.Denied) return
        scheduler.requestForAccess(
            GallerySyncAccessPolicy.mode(store.hasPersistedScanBaseline()),
        )
    }
}

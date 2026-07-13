package cn.soul2.imageai.media.store

import cn.soul2.imageai.media.sync.SyncMode

interface MediaStoreGateway {
    fun externalVolumes(): Set<String>

    fun readPage(
        volume: String,
        mode: SyncMode,
        cursor: MediaStoreCursor?,
        limit: Int,
    ): MediaStorePage
}

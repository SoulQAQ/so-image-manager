package cn.soul2.imageai.gallery

import androidx.paging.PagingData
import cn.soul2.imageai.analysis.EffectiveImageMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import cn.soul2.imageai.data.db.entity.ImagePartition

data class GalleryStatus(val totalCount: Int, val analyzedCount: Int)

interface GalleryRepository {
    fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>>
    fun observeCount(): Flow<Int>
    fun observeStatus(): Flow<GalleryStatus> = observeCount().map { GalleryStatus(it, it) }
    fun observeImage(localId: Long): Flow<GalleryImage?>
    fun observeImage(localId: Long, source: GallerySource): Flow<GalleryImage?> = observeImage(localId)
    fun observeEffectiveMetadata(localId: Long): Flow<EffectiveImageMetadata?> = flowOf(null)
    fun observeImageWindow(localId: Long): Flow<GalleryImageWindow?> =
        observeImage(localId).map { image ->
            image?.let { GalleryImageWindow(previous = null, current = it, next = null) }
        }
    fun observeImageWindow(localId: Long, source: GallerySource): Flow<GalleryImageWindow?> =
        observeImage(localId, source).map { image ->
            image?.let { GalleryImageWindow(previous = null, current = it, next = null) }
        }
}

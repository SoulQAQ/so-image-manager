package cn.soul2.imageai.gallery

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface GalleryRepository {
    fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>>
    fun observeCount(): Flow<Int>
    fun observeImage(localId: Long): Flow<GalleryImage?>
    fun observeImageWindow(localId: Long): Flow<GalleryImageWindow?> =
        observeImage(localId).map { image ->
            image?.let { GalleryImageWindow(previous = null, current = it, next = null) }
        }
}

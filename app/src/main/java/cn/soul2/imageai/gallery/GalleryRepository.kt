package cn.soul2.imageai.gallery

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

interface GalleryRepository {
    fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>>
    fun observeCount(): Flow<Int>
    fun observeImage(localId: Long): Flow<GalleryImage?>
}

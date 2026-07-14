package cn.soul2.imageai.gallery

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomGalleryRepository(
    private val imageDao: ImageDao,
) : GalleryRepository {
    override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> = Pager(
        config = PAGING_CONFIG,
        pagingSourceFactory = {
            when (query.source) {
                GallerySource.Recent -> imageDao.pagingRecent()
                GallerySource.All -> imageDao.pagingAll()
            }
        },
    ).flow.map { pagingData -> pagingData.map(ImageEntity::toGalleryImage) }

    override fun observeCount(): Flow<Int> = imageDao.observeAvailableCount()

    override fun observeImage(localId: Long): Flow<GalleryImage?> =
        imageDao.observeAvailableById(localId).map { entity -> entity?.toGalleryImage() }

    companion object {
        internal val PAGING_CONFIG = PagingConfig(
            pageSize = 60,
            initialLoadSize = 120,
            prefetchDistance = 20,
            enablePlaceholders = false,
        )
    }
}

private fun ImageEntity.toGalleryImage() = GalleryImage(
    localId = localId,
    contentUri = contentUri,
    displayName = displayName,
    mimeType = mimeType,
    width = width,
    height = height,
    sizeBytes = sizeBytes,
    capturedAtEpochMillis = capturedAtEpochMillis,
    addedAtEpochMillis = addedAtEpochMillis,
    modifiedAtEpochMillis = modifiedAtEpochMillis,
    bucketName = bucketName,
    isFavorite = isFavorite,
)

package cn.soul2.imageai.gallery

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import cn.soul2.imageai.analysis.EffectiveImageMetadata
import cn.soul2.imageai.analysis.EffectiveMetadataReader
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class RoomGalleryRepository(
    private val imageDao: ImageDao,
    private val effectiveMetadataReader: EffectiveMetadataReader = EffectiveMetadataReader.Empty,
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

    override fun observeEffectiveMetadata(localId: Long): Flow<EffectiveImageMetadata?> =
        effectiveMetadataReader.observeEffectiveMetadata(localId)

    override fun observeImageWindow(localId: Long): Flow<GalleryImageWindow?> =
        imageDao.observeAvailableById(localId).flatMapLatest { current ->
            if (current == null) {
                flowOf(null)
            } else {
                combine(
                    imageDao.observePreviousAvailable(
                        current.sortTimeEpochMillis,
                        current.mediaStoreId,
                        current.volumeName,
                        current.localId,
                    ),
                    imageDao.observeNextAvailable(
                        current.sortTimeEpochMillis,
                        current.mediaStoreId,
                        current.volumeName,
                        current.localId,
                    ),
                ) { previous, next ->
                    GalleryImageWindow(
                        previous = previous?.toGalleryImage(),
                        current = current.toGalleryImage(),
                        next = next?.toGalleryImage(),
                    )
                }
            }
        }

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

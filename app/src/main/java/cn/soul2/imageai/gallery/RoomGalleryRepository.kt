package cn.soul2.imageai.gallery

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import cn.soul2.imageai.analysis.EffectiveImageMetadata
import cn.soul2.imageai.analysis.EffectiveMetadataReader
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImagePartition
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
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
                GallerySource.Analyzed -> imageDao.pagingAnalyzed()
                GallerySource.Unanalyzed -> imageDao.pagingUnanalyzed()
                GallerySource.Private -> imageDao.pagingPrivate()
                GallerySource.PrivateUnanalyzable -> imageDao.pagingPrivateUnanalyzable()
                GallerySource.Rejected -> imageDao.pagingRejected()
                is GallerySource.Album -> imageDao.pagingAlbum(
                    query.source.bucketId,
                    query.source.bucketName,
                )
                is GallerySource.Tag -> imageDao.pagingTerm(
                    AnalysisTermKind.TAG,
                    query.source.normalizedKey,
                )
                is GallerySource.Category -> imageDao.pagingTerm(
                    AnalysisTermKind.CATEGORY,
                    query.source.normalizedKey,
                )
            }
        },
    ).flow.map { pagingData -> pagingData.map(ImageEntity::toGalleryImage) }

    override fun observeCount(): Flow<Int> = imageDao.observeAvailableCount()

    override fun observeStatus(): Flow<GalleryStatus> = combine(
        imageDao.observeTotalGalleryCount(),
        imageDao.observeAvailableCount(),
    ) { total, analyzed -> GalleryStatus(total, analyzed) }

    override fun observeUnprocessedCount(): Flow<Int> = imageDao.observeUnprocessedCount()

    override fun observeCollections(
        type: GalleryCollectionType,
    ): Flow<List<GalleryCollectionSummary>> = when (type) {
        GalleryCollectionType.ALBUM -> imageDao.observeAlbumCollections()
        GalleryCollectionType.TAG -> imageDao.observeTermCollections(AnalysisTermKind.TAG)
        GalleryCollectionType.CATEGORY -> imageDao.observeTermCollections(AnalysisTermKind.CATEGORY)
    }

    override fun observeImage(localId: Long): Flow<GalleryImage?> =
        imageDao.observeAvailableById(localId).map { entity -> entity?.toGalleryImage() }

    override fun observeImage(localId: Long, source: GallerySource): Flow<GalleryImage?> {
        return observeSourceImage(localId, source).map { it?.toGalleryImage() }
    }

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

    override fun observeImageWindow(localId: Long, source: GallerySource): Flow<GalleryImageWindow?> {
        return observeSourceImage(localId, source).flatMapLatest { current ->
            if (current == null) {
                flowOf(null)
            } else {
                val neighbors = sourceNeighborFlows(current, source)
                combine(neighbors.first, neighbors.second) { previous, next ->
                    GalleryImageWindow(
                        previous?.toGalleryImage(),
                        current.toGalleryImage(),
                        next?.toGalleryImage(),
                    )
                }
            }
        }
    }

    private fun observeSourceImage(localId: Long, source: GallerySource): Flow<ImageEntity?> =
        when (source) {
            is GallerySource.Album -> imageDao.observeAlbumImage(
                localId,
                source.bucketId,
                source.bucketName,
            )
            is GallerySource.Tag -> imageDao.observeTermImage(
                localId,
                AnalysisTermKind.TAG,
                source.normalizedKey,
            )
            is GallerySource.Category -> imageDao.observeTermImage(
                localId,
                AnalysisTermKind.CATEGORY,
                source.normalizedKey,
            )
            else -> imageDao.observeAvailableByIdInPartition(
                localId,
                source.partitionOrNull() ?: ImagePartition.MAIN,
            )
        }

    private fun sourceNeighborFlows(
        image: ImageEntity,
        source: GallerySource,
    ): Pair<Flow<ImageEntity?>, Flow<ImageEntity?>> = when (source) {
        is GallerySource.Album -> imageDao.observePreviousInAlbum(
            image.sortTimeEpochMillis,
            image.mediaStoreId,
            image.volumeName,
            image.localId,
            source.bucketId,
            source.bucketName,
        ) to imageDao.observeNextInAlbum(
            image.sortTimeEpochMillis,
            image.mediaStoreId,
            image.volumeName,
            image.localId,
            source.bucketId,
            source.bucketName,
        )
        is GallerySource.Tag -> termNeighborFlows(image, AnalysisTermKind.TAG, source.normalizedKey)
        is GallerySource.Category -> termNeighborFlows(
            image,
            AnalysisTermKind.CATEGORY,
            source.normalizedKey,
        )
        else -> {
            val partition = source.partitionOrNull() ?: ImagePartition.MAIN
            imageDao.observePreviousInPartition(
                image.sortTimeEpochMillis,
                image.mediaStoreId,
                image.volumeName,
                image.localId,
                partition,
            ) to imageDao.observeNextInPartition(
                image.sortTimeEpochMillis,
                image.mediaStoreId,
                image.volumeName,
                image.localId,
                partition,
            )
        }
    }

    private fun termNeighborFlows(
        image: ImageEntity,
        kind: AnalysisTermKind,
        normalizedKey: String,
    ): Pair<Flow<ImageEntity?>, Flow<ImageEntity?>> = imageDao.observePreviousInTerm(
        image.sortTimeEpochMillis,
        image.mediaStoreId,
        image.volumeName,
        image.localId,
        kind,
        normalizedKey,
    ) to imageDao.observeNextInTerm(
        image.sortTimeEpochMillis,
        image.mediaStoreId,
        image.volumeName,
        image.localId,
        kind,
        normalizedKey,
    )

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
    source = source,
)

private fun GallerySource.partitionOrNull(): ImagePartition? = when (this) {
    GallerySource.Unanalyzed -> ImagePartition.UNPROCESSED
    GallerySource.Private -> ImagePartition.PRIVATE
    GallerySource.PrivateUnanalyzable,
    GallerySource.Rejected,
    -> ImagePartition.PRIVATE_UNANALYZABLE
    else -> null
}

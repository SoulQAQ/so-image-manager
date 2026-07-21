package cn.soul2.imageai.data.db.dao

import androidx.room.ColumnInfo
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImageAvailability

data class ExistingImageIdentity(
    @ColumnInfo(name = "volume_name")
    val volumeName: String,
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    @ColumnInfo(name = "local_id")
    val localId: Long,
    val availability: ImageAvailability = ImageAvailability.AVAILABLE,
)

internal data class ImageIdentityQueryBatch(
    val volumeName: String,
    val mediaStoreIds: List<Long>,
)

internal object ImageUpsertResolver {
    private const val DEFAULT_MAX_IDS_PER_BATCH = 998

    fun queryBatches(
        images: List<ImageEntity>,
        maxIdsPerBatch: Int = DEFAULT_MAX_IDS_PER_BATCH,
    ): List<ImageIdentityQueryBatch> {
        require(maxIdsPerBatch > 0) { "maxIdsPerBatch must be positive" }
        return deduplicateLastWins(images)
            .groupBy(ImageEntity::volumeName)
            .flatMap { (volumeName, volumeImages) ->
                volumeImages
                    .map(ImageEntity::mediaStoreId)
                    .chunked(maxIdsPerBatch)
                    .map { mediaStoreIds ->
                        ImageIdentityQueryBatch(volumeName, mediaStoreIds)
                    }
            }
    }

    fun resolve(
        images: List<ImageEntity>,
        existing: List<ExistingImageIdentity>,
    ): List<ImageEntity> {
        val existingByIdentity = existing.associateBy { identity ->
            ExternalIdentity(identity.volumeName, identity.mediaStoreId)
        }
        return deduplicateLastWins(images).map { image ->
            val existingImage = existingByIdentity[
                ExternalIdentity(image.volumeName, image.mediaStoreId)
            ]
            if (existingImage == null) image else image.copy(
                localId = existingImage.localId,
                availability = existingImage.availability.takeIf {
                    it == ImageAvailability.REMOVED_FROM_SOIM
                } ?: image.availability,
            )
        }
    }

    private fun deduplicateLastWins(images: List<ImageEntity>): List<ImageEntity> {
        val deduplicated = LinkedHashMap<ExternalIdentity, ImageEntity>(images.size)
        images.forEach { image ->
            val identity = ExternalIdentity(image.volumeName, image.mediaStoreId)
            deduplicated.remove(identity)
            deduplicated[identity] = image
        }
        return deduplicated.values.toList()
    }

    private data class ExternalIdentity(
        val volumeName: String,
        val mediaStoreId: Long,
    )
}

internal suspend fun resolveImagesForUpsert(
    images: List<ImageEntity>,
    findExisting: suspend (String, List<Long>) -> List<ExistingImageIdentity>,
): List<ImageEntity> {
    val existing = ImageUpsertResolver.queryBatches(images).flatMap { batch ->
        findExisting(batch.volumeName, batch.mediaStoreIds)
    }
    return ImageUpsertResolver.resolve(images, existing)
}

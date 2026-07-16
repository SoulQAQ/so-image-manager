package cn.soul2.imageai.search

import androidx.room.withTransaction
import cn.soul2.imageai.analysis.EffectiveProjectionSnapshot
import cn.soul2.imageai.analysis.SearchProjectionPreparation
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveImageMetadataEntity
import cn.soul2.imageai.data.db.entity.ImageEntity

internal class SearchIndexBackfill(
    private val database: AppDatabase,
    private val writer: RoomSearchProjectionWriter,
) {
    private val searchDao = database.searchIndexDao()
    private val effectiveDao = database.effectiveMetadataDao()

    suspend fun reindexCommitted(images: List<ImageEntity>) {
        images.distinctBy(ImageEntity::localId)
            .chunked(BATCH_SIZE)
            .forEach { batch ->
                database.withTransaction {
                    batch.forEach { image -> indexCurrent(image) }
                }
            }
    }

    suspend fun backfillMissing(): Int {
        var indexed = 0
        while (true) {
            val batch = searchDao.getUnindexedAvailableImages(BATCH_SIZE)
            if (batch.isEmpty()) return indexed
            database.withTransaction {
                batch.forEach { image ->
                    indexCurrent(image)
                    indexed++
                }
            }
        }
    }

    private suspend fun indexCurrent(image: ImageEntity) {
        val metadata = effectiveDao.getMetadata(image.localId) ?: EffectiveImageMetadataEntity(
            imageLocalId = image.localId,
            caption = null,
            captionSource = EffectiveCaptionSource.NONE,
            projectionGeneration = 0,
            updatedAtEpochMillis = image.modifiedAtEpochMillis,
        )
        val snapshot = EffectiveProjectionSnapshot(
            metadata = metadata,
            terms = effectiveDao.getTerms(image.localId),
        )
        when (val preparation = writer.prepareForImage(image.localId, snapshot)) {
            is SearchProjectionPreparation.Ready -> writer.replaceForImage(preparation)
            is SearchProjectionPreparation.Blocked -> error(
                "${preparation.code}: ${preparation.detail}",
            )
        }
    }

    private companion object {
        const val BATCH_SIZE = 100
    }
}

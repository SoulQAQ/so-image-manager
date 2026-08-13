package cn.soul2.imageai.performance

import cn.soul2.imageai.data.db.entity.*

internal object GalleryScaleFixture {
    fun images(count: Int): List<ImageEntity> {
        require(count in 1..100_000)
        return List(count) { index ->
            val id = index + 1L
            ImageEntity(
                localId = id, volumeName = "external", mediaStoreId = id,
                contentUri = "content://media/external/images/$id",
                displayName = "IMG_${id.toString().padStart(6, '0')}.jpg", mimeType = "image/jpeg",
                width = 1920, height = 1080, sizeBytes = 2_000_000L + index,
                capturedAtEpochMillis = 1_700_000_000_000L + index * 1_000L,
                addedAtEpochMillis = 1_700_000_000_000L + index * 1_000L,
                modifiedAtEpochMillis = 1_700_000_000_000L + index * 1_000L,
                sortTimeEpochMillis = 1_700_000_000_000L + index * 1_000L,
                bucketId = (index % 100).toLong(), bucketName = "相册 ${index % 100}",
                isFavorite = index % 20 == 0, quickFingerprint = "fixture-$id",
                availability = ImageAvailability.AVAILABLE, source = ImageSource.MEDIA_STORE,
                partition = if (index % 5 == 0) ImagePartition.UNPROCESSED else ImagePartition.MAIN,
                lastSeenSyncRunId = null, missingCandidateSinceEpochMillis = null,
                missingObservationCount = 0,
            )
        }
    }
}

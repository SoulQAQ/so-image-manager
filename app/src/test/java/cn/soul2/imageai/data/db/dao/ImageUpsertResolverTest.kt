package cn.soul2.imageai.data.db.dao

import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUpsertResolverTest {
    @Test
    fun existingLocalIdsAreRestoredAndLastIncomingDuplicateWins() {
        val resolved = ImageUpsertResolver.resolve(
            images = listOf(
                image(volumeName = "external", mediaStoreId = 1L, displayName = "first"),
                image(volumeName = "internal", mediaStoreId = 1L, displayName = "other"),
                image(volumeName = "external", mediaStoreId = 1L, displayName = "last"),
            ),
            existing = listOf(
                ExistingImageIdentity("external", 1L, localId = 7L),
                ExistingImageIdentity("internal", 1L, localId = 9L),
            ),
        ).associateBy { it.volumeName }

        assertEquals(2, resolved.size)
        assertEquals(7L, resolved.getValue("external").localId)
        assertEquals("last", resolved.getValue("external").displayName)
        assertEquals(9L, resolved.getValue("internal").localId)
        assertEquals("other", resolved.getValue("internal").displayName)
    }

    @Test
    fun identityQueriesAreGroupedByVolumeAndChunked() {
        val batches = ImageUpsertResolver.queryBatches(
            images = listOf(
                image("external", 1L, "first"),
                image("external", 2L, "second"),
                image("external", 3L, "third"),
                image("external", 1L, "last"),
                image("internal", 1L, "internal"),
            ),
            maxIdsPerBatch = 2,
        )

        assertEquals(
            listOf(
                ImageIdentityQueryBatch("external", listOf(2L, 3L)),
                ImageIdentityQueryBatch("external", listOf(1L)),
                ImageIdentityQueryBatch("internal", listOf(1L)),
            ),
            batches,
        )
    }

    private fun image(
        volumeName: String,
        mediaStoreId: Long,
        displayName: String,
    ) = ImageEntity(
        volumeName = volumeName,
        mediaStoreId = mediaStoreId,
        contentUri = "content://media/$volumeName/$mediaStoreId",
        displayName = displayName,
        mimeType = "image/jpeg",
        width = 1,
        height = 1,
        sizeBytes = 1L,
        capturedAtEpochMillis = null,
        addedAtEpochMillis = 1L,
        modifiedAtEpochMillis = 1L,
        sortTimeEpochMillis = 1L,
        bucketId = null,
        bucketName = null,
        isFavorite = false,
        quickFingerprint = displayName,
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )
}

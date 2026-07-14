package cn.soul2.imageai.media.sync

import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.media.store.MediaStoreImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMediaSyncStoreTest {
    @Test
    fun checkpointMappingPreservesFullScanAndIncrementalCursorsIndependently() {
        val checkpoint = SyncCheckpoint(
            volumeName = "external_primary",
            generation = 31L,
            mediaStoreVersion = "v31",
            fullScanCursorModifiedAtEpochMillis = 11_000L,
            fullScanCursorMediaStoreId = 11L,
            incrementalHighWaterModifiedAtEpochMillis = 29_000L,
            incrementalHighWaterMediaStoreId = 29L,
            completedAtEpochMillis = null,
            fullReconciliationAtEpochMillis = 7_000L,
        )

        val restored = RoomMediaSyncMapper.checkpoint(
            RoomMediaSyncMapper.checkpoint(checkpoint),
        )

        assertEquals(checkpoint, restored)
    }

    @Test
    fun mediaStoreImageMapsToRecoverableRoomRecordWithoutReadingBytes() {
        val run = SyncRun.running(42L, SyncMode.RECONCILE, nowEpochMillis = 1_000L)
        val image = MediaStoreImage(
            volumeName = "external_primary",
            mediaStoreId = 7L,
            contentUri = "content://media/external_primary/images/media/7",
            displayName = "seven.jpg",
            mimeType = "image/jpeg",
            width = 300,
            height = 200,
            sizeBytes = 90L,
            capturedAtEpochMillis = null,
            addedAtEpochMillis = 2_000L,
            modifiedAtEpochMillis = 3_000L,
            bucketId = 8L,
            bucketName = "Camera",
            isFavorite = true,
            generationModified = 4L,
        )

        val entity = RoomMediaSyncMapper.image(image, run)

        assertEquals(ImageAvailability.AVAILABLE, entity.availability)
        assertEquals(42L, entity.lastSeenSyncRunId)
        assertEquals(3_000L, entity.sortTimeEpochMillis)
        assertEquals(image.quickFingerprint(), entity.quickFingerprint)
        assertNull(entity.missingCandidateSinceEpochMillis)
        assertEquals(0, entity.missingObservationCount)
    }

    @Test
    fun daoKeepsBatchCheckpointRunAndReconciliationUpdatesTransactional() {
        val source = projectFile(
            "app/src/main/java/cn/soul2/imageai/data/db/dao/MediaSyncDao.kt",
        ).readText()

        assertTrue(transactionalMethod(source, "commitBatch"))
        assertTrue(transactionalMethod(source, "enqueueAndClaimRun"))
        assertTrue(transactionalMethod(source, "claimRetryRun"))
        assertTrue(transactionalMethod(source, "pauseForPermission"))
        assertTrue(transactionalMethod(source, "finishReconciliation"))
    }

    private fun transactionalMethod(source: String, methodName: String): Boolean =
        Regex("@Transaction\\s+open suspend fun $methodName\\b").containsMatchIn(source)

    private fun projectFile(path: String): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile }
        return File(root, path)
    }
}

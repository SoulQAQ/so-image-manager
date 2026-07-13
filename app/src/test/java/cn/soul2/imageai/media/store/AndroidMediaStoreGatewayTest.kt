package cn.soul2.imageai.media.store

import cn.soul2.imageai.media.sync.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMediaStoreGatewayTest {
    @Test
    fun apiThirtyProjectionAndFiltersNeverUseDataPaths() {
        val plan = MediaStoreQueryPlan.create(
            mode = SyncMode.INITIAL,
            cursor = null,
            sdkInt = 30,
            limit = 250,
        )

        assertFalse(plan.projection.any { it.equals("_data", ignoreCase = true) })
        assertTrue(plan.projection.containsAll(REQUIRED_PROJECTION))
        assertTrue(plan.projection.contains("is_favorite"))
        assertTrue(plan.projection.contains("generation_modified"))
        assertTrue(plan.selection.contains("is_pending = ?"))
        assertTrue(plan.selection.contains("is_trashed = ?"))
        assertEquals("date_modified DESC, _id DESC", plan.sortOrder)
        assertEquals(250, plan.limit)
    }

    @Test
    fun initialKeysetConvertsMillisecondCursorOnlyAtProviderBoundary() {
        val cursor = MediaStoreCursor(
            modifiedAtEpochMillis = 12_345_000L,
            mediaStoreId = 91L,
            generation = null,
        )

        val plan = MediaStoreQueryPlan.create(SyncMode.RECONCILE, cursor, sdkInt = 29, limit = 10)

        assertTrue(plan.selection.contains("date_modified < ?"))
        assertTrue(plan.selection.contains("date_modified = ? AND _id < ?"))
        assertEquals(listOf("0", "12345", "12345", "91"), plan.selectionArgs)
        assertFalse(plan.projection.contains("generation_modified"))
    }

    @Test
    fun incrementalUsesGenerationOnApiThirtyAndModifiedTimeOnApiTwentyNine() {
        val generationCursor = MediaStoreCursor(null, mediaStoreId = 9L, generation = 77L)
        val api30 = MediaStoreQueryPlan.create(
            SyncMode.INCREMENTAL,
            generationCursor,
            sdkInt = 30,
            limit = 100,
        )
        assertTrue(api30.selection.contains("generation_modified > ?"))
        assertEquals(listOf("0", "0", "77", "77", "9"), api30.selectionArgs)
        assertEquals("generation_modified ASC, _id ASC", api30.sortOrder)

        val modifiedCursor = MediaStoreCursor(22_000L, mediaStoreId = 4L, generation = null)
        val api29 = MediaStoreQueryPlan.create(
            SyncMode.INCREMENTAL,
            modifiedCursor,
            sdkInt = 29,
            limit = 100,
        )
        assertTrue(api29.selection.contains("date_modified > ?"))
        assertEquals(listOf("0", "22", "22", "4"), api29.selectionArgs)
        assertEquals("date_modified ASC, _id ASC", api29.sortOrder)
    }

    @Test
    fun mediaStoreSecondFieldsBecomeMillisecondsAndFingerprintUsesMetadata() {
        assertEquals(91_000L, MediaStoreTime.secondsToEpochMillis(91L))
        val image = image(sizeBytes = 100L)

        assertEquals(image.quickFingerprint(), image.quickFingerprint())
        assertNotEquals(image.quickFingerprint(), image(sizeBytes = 101L).quickFingerprint())
    }

    private fun image(sizeBytes: Long) = MediaStoreImage(
        volumeName = "external_primary",
        mediaStoreId = 1L,
        contentUri = "content://media/external_primary/images/media/1",
        displayName = "one.jpg",
        mimeType = "image/jpeg",
        width = 1920,
        height = 1080,
        sizeBytes = sizeBytes,
        capturedAtEpochMillis = 123L,
        addedAtEpochMillis = 1_000L,
        modifiedAtEpochMillis = 2_000L,
        bucketId = 3L,
        bucketName = "Camera",
        isFavorite = false,
        generationModified = 4L,
    )

    private companion object {
        val REQUIRED_PROJECTION = setOf(
            "_id",
            "volume_name",
            "_display_name",
            "mime_type",
            "width",
            "height",
            "_size",
            "datetaken",
            "date_added",
            "date_modified",
            "bucket_id",
            "bucket_display_name",
        )
    }
}

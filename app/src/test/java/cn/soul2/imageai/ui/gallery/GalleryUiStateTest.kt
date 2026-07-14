package cn.soul2.imageai.ui.gallery

import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.media.permission.GalleryAccessState
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryUiStateTest {
    @Test
    fun deniedAccessWinsOverStoredRowsAndRunningSync() {
        val state = GalleryUiState(availableCount = 4, syncRun = syncRun("RUNNING"))

        assertEquals(
            GalleryContentState.NoPermission,
            state.contentState(GalleryAccessState.Denied(canRequestAgain = true)),
        )
    }

    @Test
    fun grantedEmptyIndexDistinguishesActiveSyncFromCompletedEmptyLibrary() {
        assertEquals(
            GalleryContentState.Syncing(indexedCount = 3),
            GalleryUiState(0, syncRun("RUNNING", indexedCount = 3))
                .contentState(GalleryAccessState.Full),
        )
        assertEquals(
            GalleryContentState.Empty,
            GalleryUiState(0, syncRun("SUCCEEDED"))
                .contentState(GalleryAccessState.Partial),
        )
    }

    @Test
    fun unknownCountLoadsAndCommittedRowsRenderDuringSync() {
        assertEquals(
            GalleryContentState.Loading,
            GalleryUiState(null, null).contentState(GalleryAccessState.Full),
        )
        assertEquals(
            GalleryContentState.Content,
            GalleryUiState(1, syncRun("RUNNING")).contentState(GalleryAccessState.Full),
        )
    }

    @Test
    fun responsiveColumnsSwitchAtExactlySixHundredDp() {
        assertEquals(2, galleryColumnCount(GalleryLayout.Waterfall, widthDp = 599))
        assertEquals(3, galleryColumnCount(GalleryLayout.Waterfall, widthDp = 600))
        assertEquals(3, galleryColumnCount(GalleryLayout.Grid, widthDp = 599))
        assertEquals(5, galleryColumnCount(GalleryLayout.Grid, widthDp = 600))
    }

    @Test
    fun tileAspectPolicyKeepsExtremeOriginalRatioAndMakesLibraryCellsSquare() {
        val panorama = GalleryImage(
            localId = 1L,
            contentUri = "content://media/external/images/media/1",
            displayName = "1.jpg",
            mimeType = "image/jpeg",
            width = 1_000,
            height = 200,
            sizeBytes = 1L,
            capturedAtEpochMillis = null,
            addedAtEpochMillis = 1L,
            modifiedAtEpochMillis = 1L,
            bucketName = null,
            isFavorite = false,
        )

        assertEquals(5f, galleryTileAspectRatio(panorama, GalleryTileLayout.OriginalAspect), 0f)
        assertEquals(1f, galleryTileAspectRatio(panorama, GalleryTileLayout.Square), 0f)
    }

    private fun syncRun(state: String, indexedCount: Int = 0) = MediaSyncRunEntity(
        runId = 1L,
        mode = "INITIAL",
        state = state,
        currentVolumeName = "external",
        discoveredCount = indexedCount,
        indexedCount = indexedCount,
        unavailableCount = 0,
        errorCode = null,
        errorMessage = null,
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        completedAtEpochMillis = 2L.takeIf { state == "SUCCEEDED" },
    )
}

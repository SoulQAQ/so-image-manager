package cn.soul2.imageai.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.screens.HomeScreen
import cn.soul2.imageai.ui.screens.LibraryScreen
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GalleryScreensTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun deniedHomeShowsPermissionRecoveryAndNeverShowsSearch() {
        composeRule.setContent {
            SoImageManagerTheme {
                HomeScreen(
                    repository = StaticGalleryRepository(),
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Denied(canRequestAgain = true),
                    onImageClick = {},
                )
            }
        }

        composeRule.onNodeWithText("尚未获得照片权限").assertIsDisplayed()
        composeRule.onNodeWithText("重新授权").assertIsDisplayed()
        composeRule.onAllNodesWithText("搜索").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("搜索").assertCountEquals(0)
    }

    @Test
    fun emptyHomeDistinguishesRunningSyncAndShowsRealIndexedCount() {
        composeRule.setContent {
            SoImageManagerTheme {
                HomeScreen(
                    repository = StaticGalleryRepository(),
                    syncRuns = flowOf(syncRun(indexedCount = 3)),
                    galleryAccessState = GalleryAccessState.Full,
                    onImageClick = {},
                )
            }
        }

        composeRule.onNodeWithText("正在建立图片索引").assertIsDisplayed()
        composeRule.onNodeWithText("已索引 3 张").assertIsDisplayed()
        composeRule.onNodeWithTag("gallery_sync_progress").assertIsDisplayed()
    }

    @Test
    fun libraryUsesStableGridAndGrantedEmptyCopy() {
        composeRule.setContent {
            SoImageManagerTheme {
                LibraryScreen(
                    repository = StaticGalleryRepository(),
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Full,
                    onImageClick = {},
                )
            }
        }

        composeRule.onNodeWithText("未发现可索引的图片").assertIsDisplayed()
        composeRule.onAllNodesWithText("搜索").assertCountEquals(0)
    }

    @Test
    fun originalAspectTileReservesItsHeightAndInvokesLocalId() {
        var selectedId: Long? = null
        composeRule.setContent {
            SoImageManagerTheme {
                Box(Modifier.width(200.dp)) {
                    GalleryImageTile(
                        image = galleryImage(width = 400, height = 200),
                        layout = GalleryTileLayout.OriginalAspect,
                        onClick = { selectedId = it },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("gallery_image_42")
            .assertWidthIsEqualTo(200.dp)
            .assertHeightIsEqualTo(100.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(42L, selectedId) }
    }

    @Test
    fun detailShowsRealMetadataAndBackAction() {
        var backs = 0
        composeRule.setContent {
            SoImageManagerTheme {
                ImageDetailScreen(
                    repository = StaticGalleryRepository(images = listOf(galleryImage())),
                    localId = 42L,
                    onBack = { backs += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("screen_image_detail").assertIsDisplayed()
        composeRule.onNodeWithText("42.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("400 × 200").assertIsDisplayed()
        composeRule.onNodeWithText("相机").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    private class StaticGalleryRepository(
        private val images: List<GalleryImage> = emptyList(),
    ) : GalleryRepository {
        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> =
            flowOf(PagingData.from(images))

        override fun observeCount(): Flow<Int> = flowOf(images.size)

        override fun observeImage(localId: Long): Flow<GalleryImage?> =
            flowOf(images.firstOrNull { image -> image.localId == localId })
    }

    private fun galleryImage(width: Int = 400, height: Int = 200) = GalleryImage(
        localId = 42L,
        contentUri = "content://media/external/images/media/42",
        displayName = "42.jpg",
        mimeType = "image/jpeg",
        width = width,
        height = height,
        sizeBytes = 4_096L,
        capturedAtEpochMillis = 1_720_598_400_000L,
        addedAtEpochMillis = 1_720_598_400_000L,
        modifiedAtEpochMillis = 1_720_598_400_000L,
        bucketName = "相机",
        isFavorite = false,
    )

    private fun syncRun(indexedCount: Int) = MediaSyncRunEntity(
        runId = 1L,
        mode = "INITIAL",
        state = "RUNNING",
        currentVolumeName = "external",
        discoveredCount = indexedCount,
        indexedCount = indexedCount,
        unavailableCount = 0,
        errorCode = null,
        errorMessage = null,
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        completedAtEpochMillis = null,
    )
}

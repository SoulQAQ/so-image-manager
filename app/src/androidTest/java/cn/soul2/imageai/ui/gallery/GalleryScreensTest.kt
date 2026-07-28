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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryCollectionSummary
import cn.soul2.imageai.gallery.GalleryCollectionType
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GallerySource
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.screens.HomeScreen
import cn.soul2.imageai.ui.screens.LibraryScreen
import cn.soul2.imageai.ui.screens.LibraryBrowserScreen
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        composeRule.onAllNodesWithContentDescription("筛选图片分组").assertCountEquals(0)
    }

    @Test
    fun privateGalleryUsesExplicitSectionsWithoutFilterMenu() {
        composeRule.setContent {
            SoImageManagerTheme {
                LibraryScreen(
                    repository = StaticGalleryRepository(),
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Full,
                    onImageClick = {},
                    initialSource = GallerySource.Private,
                    privateMode = true,
                )
            }
        }

        composeRule.onNodeWithText("隐私图片").assertIsDisplayed()
        composeRule.onNodeWithText("无法分析").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("筛选图片分组").assertCountEquals(0)
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
    fun selectedTileShowsScrimSelectionIndicatorAndIndependentPreview() {
        var previewedId: Long? = null
        composeRule.setContent {
            SoImageManagerTheme {
                Box(Modifier.width(200.dp)) {
                    GalleryImageTile(
                        image = galleryImage(),
                        layout = GalleryTileLayout.Square,
                        onClick = {},
                        onPreview = { previewedId = it },
                        selectionMode = true,
                        selected = true,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("gallery_selection_scrim_42").assertIsDisplayed()
        composeRule.onNodeWithTag("gallery_selection_indicator_42").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("已选择").assertIsDisplayed()
        val preview = composeRule.onNodeWithContentDescription("全屏预览").assertIsDisplayed()
        val previewBounds = preview.fetchSemanticsNode().boundsInRoot
        val indicatorBounds = composeRule.onNodeWithTag("gallery_selection_indicator_42")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(previewBounds.left < indicatorBounds.left)
        assertTrue(previewBounds.top > indicatorBounds.top)
        preview.performClick()
        composeRule.runOnIdle { assertEquals(42L, previewedId) }
    }

    @Test
    fun libraryBrowserShowsCollectionsAndOpensAlbum() {
        val album = GalleryCollectionSummary(
            key = "10",
            displayName = "相机",
            imageCount = 3,
            coverUri = null,
        )
        var opened: GalleryCollectionSummary? = null
        composeRule.setContent {
            SoImageManagerTheme {
                LibraryBrowserScreen(
                    repository = StaticGalleryRepository(
                        images = listOf(galleryImage()),
                        collections = mapOf(GalleryCollectionType.ALBUM to listOf(album)),
                    ),
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Full,
                    onImageClick = {},
                    onOpenCollection = { _, collection -> opened = collection },
                )
            }
        }

        listOf("图片", "相册", "标签", "分类").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onNodeWithText("相册").performClick()
        composeRule.onNodeWithText("相机").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("3 张").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(album, opened) }
    }

    @Test
    fun librarySelectionUsesShortLabelAndEqualActionWidths() {
        composeRule.setContent {
            SoImageManagerTheme {
                LibraryScreen(
                    repository = StaticGalleryRepository(images = listOf(galleryImage())),
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Full,
                    onImageClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("gallery_image_42").performTouchInput { longClick() }
        composeRule.onNodeWithText("分析").assertIsDisplayed()
        composeRule.onAllNodesWithText("分析 / 重新分析").assertCountEquals(0)
        val tags = listOf(
            "gallery_selection_action_analyze",
            "gallery_selection_action_share",
            "gallery_selection_action_private",
            "gallery_selection_action_remove",
            "gallery_selection_action_delete",
        )
        val widths = tags.map { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.width
        }
        assertTrue(widths.max() - widths.min() <= 1f)
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
        private val collections: Map<GalleryCollectionType, List<GalleryCollectionSummary>> = emptyMap(),
    ) : GalleryRepository {
        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> =
            flowOf(PagingData.from(images))

        override fun observeCount(): Flow<Int> = flowOf(images.size)

        override fun observeCollections(type: GalleryCollectionType): Flow<List<GalleryCollectionSummary>> =
            flowOf(collections[type].orEmpty())

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

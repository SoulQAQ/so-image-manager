package cn.soul2.imageai.ui.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.search.SearchField
import cn.soul2.imageai.search.SearchPartialReason
import cn.soul2.imageai.search.SearchResult
import cn.soul2.imageai.search.SearchTier
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SearchScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun chineseResultSurfaceShowsStableProgressPartialReasonAndHitLabel() {
        var clickedId: Long? = null
        composeRule.setContent {
            SoImageManagerTheme {
                SearchScreenContent(
                    uiState = SearchUiState(
                        query = "chongqing",
                        items = listOf(SearchResultItem(image(), hit())),
                        isRefining = true,
                        partialReasons = setOf(SearchPartialReason.GRAM_TERM_CAP),
                    ),
                    onQueryChanged = {},
                    onClear = {},
                    onBack = {},
                    onImageClick = { clickedId = it },
                    onRebuildIndex = {},
                )
            }
        }

        composeRule.onNodeWithTag("search_input").assertIsDisplayed()
        composeRule.onNodeWithTag("search_refining").assertIsDisplayed()
        composeRule.onNodeWithText("结果较多，已显示相关度较高的部分").assertIsDisplayed()
        composeRule.onNodeWithText("描述 · 拼音匹配").assertIsDisplayed()
        composeRule.onNodeWithTag("gallery_image_42").performClick()
        assertEquals(42L, clickedId)
    }

    @Test
    fun rebuildAndValidationStatesRemainChineseAndActionable() {
        var rebuilds = 0
        composeRule.setContent {
            SoImageManagerTheme {
                SearchScreenContent(
                    uiState = SearchUiState(
                        query = "测试",
                        partialReasons = setOf(SearchPartialReason.REBUILD_REQUIRED),
                        error = SearchUiError.QUERY_TOO_LONG,
                    ),
                    onQueryChanged = {},
                    onClear = {},
                    onBack = {},
                    onImageClick = {},
                    onRebuildIndex = { rebuilds++ },
                )
            }
        }

        composeRule.onNodeWithText("搜索索引需要重建").assertIsDisplayed()
        composeRule.onNodeWithText("搜索内容过长").assertIsDisplayed()
        composeRule.onNodeWithText("重新扫描").performClick()
        assertEquals(1, rebuilds)
        composeRule.onNodeWithContentDescription("清除搜索").assertIsDisplayed()
    }

    private fun image() = GalleryImage(
        localId = 42,
        contentUri = "content://media/42",
        displayName = "重庆.jpg",
        mimeType = "image/jpeg",
        width = 400,
        height = 300,
        sizeBytes = 1_000,
        capturedAtEpochMillis = null,
        addedAtEpochMillis = 1,
        modifiedAtEpochMillis = 1,
        bucketName = "相机",
        isFavorite = false,
    )

    private fun hit() = SearchResult(
        imageLocalId = 42,
        tier = SearchTier.PINYIN,
        field = SearchField.CAPTION,
        fieldWeight = 300.0,
        matchScore = 1.0,
        sortTimeEpochMillis = 1,
        mediaStoreId = 42,
        volumeName = "external",
        stableLocalId = 42,
        reason = "internal",
    )
}

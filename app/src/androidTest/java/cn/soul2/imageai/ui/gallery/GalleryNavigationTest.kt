package cn.soul2.imageai.ui.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.paging.PagingData
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GalleryImageWindow
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.app.SoImageManagerApp
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class GalleryNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pushedDetailHidesBottomNavigationAndBackRestoresOriginatingGallery() {
        val repository = OneImageRepository(image())
        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = repository,
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Full,
                )
            }
        }

        composeRule.onNodeWithTag("gallery_image_42").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen_image_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_navigation").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithTag("screen_home").assertIsDisplayed()
        composeRule.onNodeWithTag("gallery_image_42").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_navigation").assertIsDisplayed()
    }

    @Test
    fun detailSwipesToTheNextImage() {
        val first = image(42L)
        val second = image(41L)
        val repository = TwoImageRepository(first, second)
        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = repository,
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Full,
                )
            }
        }

        composeRule.onNodeWithTag("gallery_image_42").performClick()
        composeRule.onNodeWithTag("image_detail_pager").performTouchInput { swipeLeft() }

        composeRule.onNodeWithText("41.jpg").assertIsDisplayed()
    }

    private class OneImageRepository(private val image: GalleryImage) : GalleryRepository {
        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> =
            flowOf(PagingData.from(listOf(image)))

        override fun observeCount(): Flow<Int> = flowOf(1)

        override fun observeImage(localId: Long): Flow<GalleryImage?> =
            flowOf(image.takeIf { it.localId == localId })
    }

    private class TwoImageRepository(
        private val first: GalleryImage,
        private val second: GalleryImage,
    ) : GalleryRepository {
        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> =
            flowOf(PagingData.from(listOf(first, second)))

        override fun observeCount(): Flow<Int> = flowOf(2)

        override fun observeImage(localId: Long): Flow<GalleryImage?> =
            flowOf(listOf(first, second).firstOrNull { it.localId == localId })

        override fun observeImageWindow(localId: Long): Flow<GalleryImageWindow?> = flowOf(
            when (localId) {
                first.localId -> GalleryImageWindow(null, first, second)
                second.localId -> GalleryImageWindow(first, second, null)
                else -> null
            },
        )
    }

    private fun image(localId: Long = 42L) = GalleryImage(
        localId = localId,
        contentUri = "content://media/external/images/media/$localId",
        displayName = "$localId.jpg",
        mimeType = "image/jpeg",
        width = 400,
        height = 200,
        sizeBytes = localId * 100L,
        capturedAtEpochMillis = 1L,
        addedAtEpochMillis = 1L,
        modifiedAtEpochMillis = 1L,
        bucketName = "相机",
        isFavorite = false,
    )
}

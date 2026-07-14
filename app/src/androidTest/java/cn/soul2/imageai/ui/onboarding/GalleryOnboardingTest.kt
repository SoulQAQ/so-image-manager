package cn.soul2.imageai.ui.onboarding

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.app.SoImageManagerApp
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GalleryOnboardingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun firstDeniedVisitShowsChineseOnboardingAndRequestsOnlyAfterAction() {
        var requestCount = 0

        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = EmptyGalleryRepository,
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Denied(canRequestAgain = true),
                    showGalleryOnboarding = true,
                    isGalleryPermissionRecovery = false,
                    onRequestGalleryPermission = { requestCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("访问您的照片").assertIsDisplayed()
        composeRule.onAllNodesWithText("授权并扫描").assertCountEquals(1)
        composeRule.onNodeWithText("稍后再说").assertDoesNotExist()
        composeRule.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, requestCount) }

        composeRule.onNodeWithText("授权并扫描").performClick()

        composeRule.runOnIdle { assertEquals(1, requestCount) }
    }

    @Test
    fun retryableDenialOffersRetryAndDismissal() {
        val showOnboarding = mutableStateOf(true)
        var dismissCount = 0

        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = EmptyGalleryRepository,
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Denied(canRequestAgain = true),
                    showGalleryOnboarding = showOnboarding.value,
                    isGalleryPermissionRecovery = true,
                    onDismissGalleryOnboarding = {
                        dismissCount += 1
                        showOnboarding.value = false
                    },
                )
            }
        }

        composeRule.onNodeWithText("重新授权").assertIsDisplayed()
        composeRule.onNodeWithText("稍后再说").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_navigation").assertDoesNotExist()

        composeRule.onNodeWithText("稍后再说").performClick()

        composeRule.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun permanentDenialOffersSystemSettingsAndDismissal() {
        var settingsOpenCount = 0

        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = EmptyGalleryRepository,
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Denied(canRequestAgain = false),
                    showGalleryOnboarding = true,
                    isGalleryPermissionRecovery = true,
                    onOpenAppSettings = { settingsOpenCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("前往系统设置").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("稍后再说").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, settingsOpenCount) }
    }

    @Test
    fun partialAccessShowsCompactReselectionBannerAboveMainApp() {
        var reselectionCount = 0

        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = EmptyGalleryRepository,
                    syncRuns = flowOf(null),
                    galleryAccessState = GalleryAccessState.Partial,
                    onRequestGalleryReselection = { reselectionCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("仅可访问已选择的照片").assertIsDisplayed()
        composeRule.onNodeWithText("重新选择").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, reselectionCount) }
    }

    @Test
    fun permissionRequestActionsAreDisabledWhileRequestIsInFlight() {
        val mode = mutableStateOf(0)

        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = EmptyGalleryRepository,
                    syncRuns = flowOf(null),
                    galleryAccessState = when (mode.value) {
                        2 -> GalleryAccessState.Partial
                        else -> GalleryAccessState.Denied(canRequestAgain = true)
                    },
                    showGalleryOnboarding = mode.value != 2,
                    isGalleryPermissionRecovery = mode.value == 1,
                    isGalleryPermissionRequestInFlight = true,
                )
            }
        }

        composeRule.onNodeWithText("授权并扫描").assertIsNotEnabled()

        composeRule.runOnIdle { mode.value = 1 }
        composeRule.onNodeWithText("重新授权").assertIsNotEnabled()

        composeRule.runOnIdle { mode.value = 2 }
        composeRule.onNodeWithText("重新选择").assertIsNotEnabled()
    }
}

private object EmptyGalleryRepository : GalleryRepository {
    override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> =
        flowOf(PagingData.empty())

    override fun observeCount(): Flow<Int> = flowOf(0)

    override fun observeImage(localId: Long): Flow<GalleryImage?> = flowOf(null)
}

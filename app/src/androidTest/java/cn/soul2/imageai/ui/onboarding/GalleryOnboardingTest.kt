package cn.soul2.imageai.ui.onboarding

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.app.SoImageManagerApp
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
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
}

package cn.soul2.imageai.ui.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.soul2.imageai.MainActivity
import cn.soul2.imageai.SoImApplication
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allPrimaryDestinationsNavigateAndRestore() {
        val container = (composeRule.activity.application as SoImApplication).container
        runBlocking { container.galleryOnboardingRepository.markHandled() }
        try {
            composeRule.onNodeWithTag("screen_home").assertIsDisplayed()
            listOf("library", "tasks", "settings").forEach { route ->
                composeRule.onNodeWithTag("destination_$route").performClick()
                composeRule.onNodeWithTag("screen_$route").assertIsDisplayed()
            }
            composeRule.activityRule.scenario.recreate()
            composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        } finally {
            runBlocking { container.galleryOnboardingRepository.clearHandled() }
        }
    }
}

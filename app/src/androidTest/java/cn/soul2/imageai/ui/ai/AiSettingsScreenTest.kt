package cn.soul2.imageai.ui.ai

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiSettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun protocolSwitchRevealsCustomDefinitionAndSaveDispatches() {
        var state by mutableStateOf(AiSettingsUiState(loading = false))
        var saves = 0
        composeRule.setContent {
            SoImageManagerTheme {
                AiSettingsContent(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    onFormChange = { state = state.copy(form = it) },
                    onSave = { saves += 1 },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("screen_ai_settings").assertIsDisplayed()
        composeRule.onNodeWithText("OpenAI Responses").assertIsDisplayed()
        composeRule.onNodeWithText("自定义 JSON").performClick()
        composeRule.runOnIdle {
            assertEquals(ModelProtocolType.CUSTOM_JSON, state.form.protocolType)
        }
        composeRule.onNodeWithText("协议名称").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("声明式协议 JSON").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_settings_save").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun errorAndCredentialSavedStateAreVisible() {
        composeRule.setContent {
            SoImageManagerTheme {
                AiSettingsContent(
                    state = AiSettingsUiState(
                        form = AiSettingsForm(modelId = "model"),
                        loading = false,
                        credentialConfigured = true,
                        error = AiSettingsError.INVALID_FIELDS,
                    ),
                    onFormChange = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("ai_settings_error").assertIsDisplayed()
        composeRule.onNodeWithText("已安全保存，留空则不修改").assertIsDisplayed()
    }

    @Test
    fun saveConfirmationIsDisplayedBelowTheTopBar() {
        composeRule.setContent {
            SoImageManagerTheme {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { snackbar.showSnackbar("配置已保存") }
                AiSettingsContent(
                    state = AiSettingsUiState(loading = false),
                    snackbarHostState = snackbar,
                    onFormChange = {},
                    onSave = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("配置已保存").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_settings_snackbar")
            .assertTopPositionInRootIsEqualTo(64.dp)
    }
}

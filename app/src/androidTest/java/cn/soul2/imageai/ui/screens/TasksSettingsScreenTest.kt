package cn.soul2.imageai.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.paging.PagingData
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.app.SoImageManagerApp
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TasksSettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun tasksShowsMappedProgressErrorAndDispatchesOnlyVisibleRetry() {
        val syncRuns = MutableStateFlow<MediaSyncRunEntity?>(
            syncRun(
                mode = "RECONCILE",
                state = "PAUSED_ERROR",
                discovered = 19,
                indexed = 17,
                unavailable = 2,
                errorCode = "IOException",
                errorMessage = "disk unavailable",
            ),
        )
        var retries = 0
        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = MutableGalleryRepository(17),
                    syncRuns = syncRuns,
                    lastSyncCompletedAt = flowOf(1_720_598_400_000L),
                    galleryUnavailableCounts = flowOf(2),
                    galleryAccessState = GalleryAccessState.Full,
                    galleryAccessStates = flowOf(GalleryAccessState.Full),
                    onRetryGallerySync = { retries += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("destination_tasks").performClick()

        composeRule.onNodeWithTag("screen_tasks").assertIsDisplayed()
        composeRule.onNodeWithText("校准扫描").assertIsDisplayed()
        composeRule.onNodeWithText("读取已暂停").assertIsDisplayed()
        composeRule.onNodeWithText("19").assertIsDisplayed()
        composeRule.onNodeWithText("17").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText("设备图库读取失败").assertIsDisplayed()
        composeRule.onNodeWithText("AI 分析进度").assertIsDisplayed()
        composeRule.onNodeWithText("暂无分析任务").assertIsDisplayed()
        composeRule.onNodeWithText("全图库分析").assertIsDisplayed()
        composeRule.onNodeWithText("分析全部图库").assertIsDisplayed()
        composeRule.onNodeWithText("预计将分析 17 张图片。").assertIsDisplayed()
        composeRule.onNodeWithText("最近完成", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("重试任务").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }

        composeRule.runOnIdle {
            syncRuns.value = syncRun(mode = "INCREMENTAL", state = "RUNNING")
        }

        composeRule.onNodeWithText("增量扫描").assertIsDisplayed()
        composeRule.onNodeWithText("正在扫描").assertIsDisplayed()
        composeRule.onNodeWithText("重试任务").assertDoesNotExist()
    }

    @Test
    fun settingsReflectsPermissionAndCountsAndDispatchesAllMaintenanceActions() {
        val accessStates = MutableStateFlow<GalleryAccessState>(GalleryAccessState.Partial)
        val shellAccessState = mutableStateOf<GalleryAccessState>(GalleryAccessState.Partial)
        val repository = MutableGalleryRepository(12)
        val unavailableCounts = MutableStateFlow(3)
        val actions = mutableListOf<String>()
        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = repository,
                    syncRuns = flowOf(null),
                    lastSyncCompletedAt = flowOf(null),
                    galleryUnavailableCounts = unavailableCounts,
                    galleryAccessState = shellAccessState.value,
                    galleryAccessStates = accessStates,
                    onSelectDocumentImages = { actions += "import" },
                    onRequestGalleryReconciliation = { actions += "rescan" },
                    onOpenAppSettings = { actions += "settings" },
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()

        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        composeRule.onNodeWithText("重新选择照片").assertDoesNotExist()
        composeRule.onNodeWithText("添加图片").assertIsDisplayed()
        composeRule.onNodeWithText("未处理图片").assertIsDisplayed()
        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        listOf("添加图片", "重新扫描", "前往系统设置").forEach { action ->
            composeRule.onNodeWithText(action).assertIsDisplayed().performClick()
        }
        composeRule.onNodeWithText("已请求重新扫描").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf("import", "rescan", "settings"), actions)
            shellAccessState.value = GalleryAccessState.Full
            accessStates.value = GalleryAccessState.Full
            repository.count.value = 14
            unavailableCounts.value = 1
        }

        composeRule.onNodeWithText("14").assertIsDisplayed()
        composeRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun committedGalleryContentDoesNotRepeatBackgroundSyncProgress() {
        composeRule.setContent {
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = MutableGalleryRepository(12),
                    syncRuns = flowOf(syncRun(mode = "INCREMENTAL", state = "RUNNING")),
                    galleryAccessState = GalleryAccessState.Full,
                )
            }
        }

        composeRule.onNodeWithTag("gallery_sync_progress").assertDoesNotExist()
        composeRule.onNodeWithTag("destination_library").performClick()
        composeRule.onNodeWithTag("gallery_sync_progress").assertDoesNotExist()
    }

    @Test
    fun injectedShellUsesCurrentActionCallbackAfterSavedStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val callbackGeneration = mutableStateOf(0)
        val invokedGenerations = mutableListOf<Int>()
        restorationTester.setContent {
            val generation = callbackGeneration.value
            SoImageManagerTheme {
                SoImageManagerApp(
                    galleryRepository = MutableGalleryRepository(0),
                    syncRuns = flowOf(null),
                    lastSyncCompletedAt = flowOf(null),
                    galleryUnavailableCounts = flowOf(0),
                    galleryAccessState = GalleryAccessState.Full,
                    galleryAccessStates = flowOf(GalleryAccessState.Full),
                    onOpenAppSettings = { invokedGenerations += generation },
                )
            }
        }

        composeRule.onNodeWithTag("destination_settings").performClick()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        composeRule.runOnIdle { callbackGeneration.value = 1 }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        composeRule.onNodeWithText("前往系统设置").performClick()
        composeRule.runOnIdle { assertEquals(listOf(1), invokedGenerations) }
    }

    private class MutableGalleryRepository(initialCount: Int) : GalleryRepository {
        val count = MutableStateFlow(initialCount)

        override fun observe(query: GalleryQuery): Flow<PagingData<GalleryImage>> =
            flowOf(PagingData.empty())

        override fun observeCount(): Flow<Int> = count

        override fun observeUnprocessedCount(): Flow<Int> = count

        override fun observeImage(localId: Long): Flow<GalleryImage?> = flowOf(null)
    }

    private fun syncRun(
        mode: String,
        state: String,
        discovered: Int = 0,
        indexed: Int = 0,
        unavailable: Int = 0,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) = MediaSyncRunEntity(
        runId = 9L,
        mode = mode,
        state = state,
        currentVolumeName = "external_primary",
        discoveredCount = discovered,
        indexedCount = indexed,
        unavailableCount = unavailable,
        errorCode = errorCode,
        errorMessage = errorMessage,
        startedAtEpochMillis = 100L,
        updatedAtEpochMillis = 200L,
        completedAtEpochMillis = null,
    )
}

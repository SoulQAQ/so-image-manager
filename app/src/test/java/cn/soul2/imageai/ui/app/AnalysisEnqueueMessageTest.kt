package cn.soul2.imageai.ui.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.entity.BatchAnalysisEnqueueResult
import cn.soul2.imageai.data.db.entity.BatchAnalysisRunEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AnalysisEnqueueMessageTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun reportsStartedAlreadyQueuedEmptyAndFailureStates() {
        val run = BatchAnalysisRunEntity(
            runId = 7L,
            state = "QUEUED",
            totalCount = 2,
            completedCount = 0,
            failedCount = 0,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
            completedAtEpochMillis = null,
        )

        assertEquals(
            "已将 2 张图片加入分析任务",
            analysisEnqueueMessage(context, BatchAnalysisEnqueueResult(run, 2), false),
        )
        assertEquals(
            "所选图片已在分析任务中",
            analysisEnqueueMessage(context, BatchAnalysisEnqueueResult(run, 0), false),
        )
        assertEquals(
            "没有可加入分析任务的图片",
            analysisEnqueueMessage(context, BatchAnalysisEnqueueResult(null, 0), false),
        )
        assertEquals(
            "分析任务启动失败，请重试",
            analysisEnqueueMessage(context, null, true),
        )
    }
}

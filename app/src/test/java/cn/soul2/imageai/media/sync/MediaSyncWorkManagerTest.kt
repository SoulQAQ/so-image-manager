package cn.soul2.imageai.media.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaSyncWorkManagerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: MediaSyncScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(RetryingWorkerFactory)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = MediaSyncScheduler(WorkManagerSyncWorkBackend(workManager))
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun keepCoalescesImmediateRequestsAndRetryReplacesTheChain() {
        scheduler.requestInitial()
        scheduler.requestIncremental()
        val kept = immediateInfos().single()
        assertEquals(WorkInfo.State.ENQUEUED, kept.state)

        scheduler.retry()
        val replacement = immediateInfos().first { it.state == WorkInfo.State.ENQUEUED }
        assertNotEquals(kept.id, replacement.id)
    }

    @Test
    fun periodicReconciliationUsesOneUniqueRequest() {
        scheduler.ensurePeriodicReconciliation()
        scheduler.ensurePeriodicReconciliation()

        val infos = workManager
            .getWorkInfosForUniqueWork(SyncPolicy.PERIODIC_UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
        assertEquals(1, infos.count { it.state == WorkInfo.State.ENQUEUED })
    }

    private fun immediateInfos(): List<WorkInfo> = workManager
        .getWorkInfosForUniqueWork(SyncPolicy.IMMEDIATE_UNIQUE_WORK_NAME)
        .get(5, TimeUnit.SECONDS)

    private object RetryingWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = RetryingWorker(appContext, workerParameters)
    }

    private class RetryingWorker(
        appContext: Context,
        workerParameters: WorkerParameters,
    ) : Worker(appContext, workerParameters) {
        override fun doWork(): Result = Result.retry()
    }
}

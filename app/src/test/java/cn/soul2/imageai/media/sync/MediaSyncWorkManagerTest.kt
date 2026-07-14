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
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private lateinit var testDriver: TestDriver

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
        testDriver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        scheduler = MediaSyncScheduler(WorkManagerSyncWorkBackend(workManager))
        RetryingWorkerFactory.reset()
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun explicitRetryAppendsWithoutCancellingRetainedRequests() {
        scheduler.requestInitial()
        scheduler.requestInitial()
        val retained = immediateInfos()
        assertEquals(2, retained.size)
        assertEquals(1, retained.count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(1, retained.count { it.state == WorkInfo.State.BLOCKED })

        scheduler.retry()
        val withRetry = immediateInfos()
        assertEquals(3, withRetry.size)
        assertTrue(
            withRetry.map(WorkInfo::id).toSet().containsAll(retained.map(WorkInfo::id)),
        )
        assertEquals(1, withRetry.count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(2, withRetry.count { it.state == WorkInfo.State.BLOCKED })

        repeat(3) {
            val active = immediateInfos().single { it.state == WorkInfo.State.ENQUEUED }
            testDriver.setInitialDelayMet(active.id)
        }
        assertEquals(
            listOf(
                SyncMode.INITIAL.name,
                SyncMode.INITIAL.name,
                SyncMode.INITIAL.name,
                SyncMode.INITIAL.name,
                MediaSyncWorker.MODE_RETRY,
                MediaSyncWorker.MODE_RETRY,
            ),
            RetryingWorkerFactory.executions,
        )
    }

    @Test
    fun requestedModesAndGenericContinuationExecuteInChainOrder() {
        scheduler.requestReconciliation()
        scheduler.requestIncremental()
        scheduler.continueScan()

        assertEquals(3, immediateInfos().size)
        assertEquals(1, immediateInfos().count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(2, immediateInfos().count { it.state == WorkInfo.State.BLOCKED })

        repeat(3) {
            val active = immediateInfos().single { it.state == WorkInfo.State.ENQUEUED }
            testDriver.setInitialDelayMet(active.id)
        }
        assertEquals(
            setOf(WorkInfo.State.SUCCEEDED),
            immediateInfos().map(WorkInfo::state).toSet(),
        )
        assertEquals(
            listOf(
                SyncMode.RECONCILE.name,
                SyncMode.RECONCILE.name,
                SyncMode.INCREMENTAL.name,
                SyncMode.INCREMENTAL.name,
                COORDINATOR_EXECUTION,
                COORDINATOR_EXECUTION,
            ),
            RetryingWorkerFactory.executions,
        )
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
        val executions = mutableListOf<String>()

        fun reset() {
            executions.clear()
        }

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
        override fun doWork(): Result {
            RetryingWorkerFactory.executions +=
                inputData.getString(MediaSyncWorker.INPUT_MODE) ?: COORDINATOR_EXECUTION
            return if (runAttemptCount == 0) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val COORDINATOR_EXECUTION = "COORDINATOR"
    }
}

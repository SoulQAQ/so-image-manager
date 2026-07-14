package cn.soul2.imageai.media.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncSchedulerTest {
    @Test
    fun persistedBaselineSelectsIncrementalWhileFirstAccessSelectsInitial() {
        val policyClass = Class.forName(
            "cn.soul2.imageai.media.sync.GallerySyncAccessPolicy",
        )
        val instance = policyClass.getField("INSTANCE").get(null)
        val mode = policyClass.getMethod("mode", Boolean::class.javaPrimitiveType)

        assertEquals(SyncMode.INITIAL, mode.invoke(instance, false))
        assertEquals(SyncMode.INCREMENTAL, mode.invoke(instance, true))
    }

    @Test
    fun externalRequestsAndExplicitRetryAppendToTheSingleChain() {
        val backend = RecordingSyncWorkBackend()
        val scheduler = MediaSyncScheduler(backend)

        scheduler.requestInitial()
        scheduler.requestIncremental()
        scheduler.requestReconciliation()
        scheduler.retry()

        assertEquals(
            listOf(
                RecordedImmediate(
                    ImmediateSyncWork.RequestedMode(SyncMode.INITIAL),
                    ExistingWorkPolicy.KEEP,
                ),
                RecordedImmediate(
                    ImmediateSyncWork.RequestedMode(SyncMode.INCREMENTAL),
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                ),
                RecordedImmediate(
                    ImmediateSyncWork.RequestedMode(SyncMode.RECONCILE),
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                ),
                RecordedImmediate(ImmediateSyncWork.Retry, ExistingWorkPolicy.APPEND_OR_REPLACE),
            ),
            backend.immediate,
        )
    }

    @Test
    fun continuationAppendsToTheUniqueChainAndPeriodicIsTwentyFourHours() {
        val backend = RecordingSyncWorkBackend()
        val scheduler = MediaSyncScheduler(backend)

        scheduler.continueScan()
        scheduler.ensurePeriodicReconciliation()

        assertEquals(
            RecordedImmediate(
                ImmediateSyncWork.Coordinator,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
            ),
            backend.immediate.single(),
        )
        assertEquals(listOf(SyncPolicy.RECONCILIATION_INTERVAL_HOURS), backend.periodicHours)
    }

    private data class RecordedImmediate(
        val work: ImmediateSyncWork,
        val policy: ExistingWorkPolicy,
    )

    private class RecordingSyncWorkBackend : SyncWorkBackend {
        val immediate = mutableListOf<RecordedImmediate>()
        val periodicHours = mutableListOf<Long>()

        override fun enqueueImmediate(work: ImmediateSyncWork, policy: ExistingWorkPolicy) {
            immediate += RecordedImmediate(work, policy)
        }

        override fun enqueuePeriodic(intervalHours: Long) {
            periodicHours += intervalHours
        }
    }
}

package cn.soul2.imageai.media.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncSchedulerTest {
    @Test
    fun immediateRequestsUseModeSpecificChainPolicies() {
        val backend = RecordingSyncWorkBackend()
        val scheduler = MediaSyncScheduler(backend)

        scheduler.requestInitial()
        scheduler.requestIncremental()
        scheduler.requestReconciliation()
        scheduler.retry()

        assertEquals(
            listOf(
                RecordedImmediate(SyncMode.INITIAL, ExistingWorkPolicy.KEEP),
                RecordedImmediate(SyncMode.INCREMENTAL, ExistingWorkPolicy.APPEND_OR_REPLACE),
                RecordedImmediate(SyncMode.RECONCILE, ExistingWorkPolicy.KEEP),
                RecordedImmediate(mode = null, ExistingWorkPolicy.REPLACE),
            ),
            backend.immediate,
        )
    }

    @Test
    fun continuationAppendsToTheUniqueChainAndPeriodicIsTwentyFourHours() {
        val backend = RecordingSyncWorkBackend()
        val scheduler = MediaSyncScheduler(backend)

        scheduler.continueScan(SyncMode.INCREMENTAL)
        scheduler.ensurePeriodicReconciliation()

        assertEquals(
            RecordedImmediate(SyncMode.INCREMENTAL, ExistingWorkPolicy.APPEND_OR_REPLACE),
            backend.immediate.single(),
        )
        assertEquals(listOf(SyncPolicy.RECONCILIATION_INTERVAL_HOURS), backend.periodicHours)
    }

    private data class RecordedImmediate(
        val mode: SyncMode?,
        val policy: ExistingWorkPolicy,
    )

    private class RecordingSyncWorkBackend : SyncWorkBackend {
        val immediate = mutableListOf<RecordedImmediate>()
        val periodicHours = mutableListOf<Long>()

        override fun enqueueImmediate(mode: SyncMode?, policy: ExistingWorkPolicy) {
            immediate += RecordedImmediate(mode, policy)
        }

        override fun enqueuePeriodic(intervalHours: Long) {
            periodicHours += intervalHours
        }
    }
}

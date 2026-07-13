package cn.soul2.imageai.media.sync

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    @Test
    fun slicesAreBoundedByOneThousandItemsAndTwentySeconds() {
        assertEquals(1_000, SyncPolicy.MAX_ITEMS_PER_SLICE)
        assertEquals(20_000L, SyncPolicy.MAX_DURATION_MILLIS)
    }

    @Test
    fun transientFailuresRetryOnlyWithinFiveTotalAttempts() {
        assertTrue(SyncPolicy.shouldRetry(IOException("temporary"), runAttemptCount = 0))
        assertTrue(SyncPolicy.shouldRetry(IOException("temporary"), runAttemptCount = 3))
        assertFalse(SyncPolicy.shouldRetry(IOException("temporary"), runAttemptCount = 4))
        assertEquals(5, SyncPolicy.MAX_WORKER_ATTEMPTS)
    }

    @Test
    fun permissionFailuresPauseWithoutWorkerFailure() {
        assertEquals(
            SyncFailureDisposition.PAUSE_PERMISSION,
            SyncPolicy.classify(SecurityException("permission revoked")),
        )
        assertEquals(
            SyncFailureDisposition.RETRY_TRANSIENT,
            SyncPolicy.classify(IOException("provider unavailable")),
        )
    }

    @Test
    fun schedulerUsesStableUniqueNamesAndRequiredIntervals() {
        assertEquals("media-sync-immediate", SyncPolicy.IMMEDIATE_UNIQUE_WORK_NAME)
        assertEquals("media-sync-reconciliation", SyncPolicy.PERIODIC_UNIQUE_WORK_NAME)
        assertEquals(24L, SyncPolicy.RECONCILIATION_INTERVAL_HOURS)
        assertEquals(2_000L, SyncPolicy.OBSERVER_DEBOUNCE_MILLIS)
    }
}

package cn.soul2.imageai

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SoImApplicationMaintenanceTest {
    @Test
    fun cleanupFailureIsContainedWithoutCancellingMaintenanceScope() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val warnings = mutableListOf<String>()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(
            supervisor +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> uncaught += error },
        )
        var siblingCompleted = false

        val cleanup = launchLegacyDatabaseCleanup(
            scope = scope,
            cleanup = { error("sensitive/database/path") },
            logWarning = warnings::add,
        )
        scope.launch { siblingCompleted = true }

        advanceUntilIdle()

        assertTrue(cleanup.isCompleted)
        assertTrue(supervisor.isActive)
        assertTrue(siblingCompleted)
        assertTrue(uncaught.isEmpty())
        assertEquals(listOf("Legacy database cleanup failed; will retry"), warnings)
        assertFalse(warnings.single().contains("sensitive"))
        scope.cancel()
    }
}

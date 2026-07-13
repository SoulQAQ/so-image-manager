package cn.soul2.imageai.ui.onboarding

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPermissionRequestCoordinatorTest {
    @Test
    fun `concurrent request is rejected while first persistence is in flight`() = runBlocking {
        val coordinator = GalleryPermissionRequestCoordinator()
        val persistenceStarted = CompletableDeferred<Unit>()
        val finishPersistence = CompletableDeferred<Unit>()
        val persistenceCalls = AtomicInteger(0)
        val launchCalls = AtomicInteger(0)
        val firstRequest = async(Dispatchers.Default) {
            coordinator.persistThenLaunch(
                persistRequestHistory = {
                    persistenceCalls.incrementAndGet()
                    persistenceStarted.complete(Unit)
                    finishPersistence.await()
                },
                launchRequest = { launchCalls.incrementAndGet() },
            )
        }
        persistenceStarted.await()

        val secondRequestAccepted = coordinator.persistThenLaunch(
            persistRequestHistory = { persistenceCalls.incrementAndGet() },
            launchRequest = { launchCalls.incrementAndGet() },
        )

        assertFalse(secondRequestAccepted)
        assertTrue(coordinator.inFlight.value)
        assertEquals(1, persistenceCalls.get())
        assertEquals(0, launchCalls.get())

        finishPersistence.complete(Unit)

        assertTrue(firstRequest.await())
        assertEquals(1, persistenceCalls.get())
        assertEquals(1, launchCalls.get())
        assertTrue(coordinator.inFlight.value)
    }

    @Test
    fun `persistence failure releases in flight state`() = runBlocking {
        val coordinator = GalleryPermissionRequestCoordinator()
        var failure: IllegalStateException? = null

        try {
            coordinator.persistThenLaunch(
                persistRequestHistory = { error("database unavailable") },
                launchRequest = { error("must not launch") },
            )
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertEquals("database unavailable", failure?.message)
        assertFalse(coordinator.inFlight.value)
        assertTrue(coordinator.persistThenLaunch({}, {}))
    }

    @Test
    fun `launch failure releases in flight state`() = runBlocking {
        val coordinator = GalleryPermissionRequestCoordinator()
        var failure: IllegalStateException? = null

        try {
            coordinator.persistThenLaunch(
                persistRequestHistory = {},
                launchRequest = { error("launcher unavailable") },
            )
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertEquals("launcher unavailable", failure?.message)
        assertFalse(coordinator.inFlight.value)
        assertTrue(coordinator.persistThenLaunch({}, {}))
    }

    @Test
    fun `result completion releases successful request for another launch`() = runBlocking {
        val coordinator = GalleryPermissionRequestCoordinator()
        var launchCalls = 0

        assertTrue(coordinator.persistThenLaunch({}, { launchCalls += 1 }))
        assertFalse(coordinator.persistThenLaunch({}, { launchCalls += 1 }))

        coordinator.complete()

        assertFalse(coordinator.inFlight.value)
        assertTrue(coordinator.persistThenLaunch({}, { launchCalls += 1 }))
        assertEquals(2, launchCalls)
    }
}

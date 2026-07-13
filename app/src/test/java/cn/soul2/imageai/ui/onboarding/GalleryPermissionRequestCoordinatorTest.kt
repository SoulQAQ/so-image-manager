package cn.soul2.imageai.ui.onboarding

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GalleryPermissionRequestCoordinatorTest {
    @Test
    fun `request history is persisted before system request launches`() = runBlocking {
        val events = mutableListOf<String>()

        GalleryPermissionRequestCoordinator.persistThenLaunch(
            persistRequestHistory = { events += "persist" },
            launchRequest = { events += "launch" },
        )

        assertEquals(listOf("persist", "launch"), events)
    }

    @Test
    fun `failed request history persistence prevents system request`() = runBlocking {
        var launched = false

        try {
            GalleryPermissionRequestCoordinator.persistThenLaunch(
                persistRequestHistory = { error("database unavailable") },
                launchRequest = { launched = true },
            )
        } catch (_: IllegalStateException) {
            // Expected persistence failure.
        }

        assertFalse(launched)
    }
}

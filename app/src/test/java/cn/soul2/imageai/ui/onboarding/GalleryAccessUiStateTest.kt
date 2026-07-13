package cn.soul2.imageai.ui.onboarding

import cn.soul2.imageai.media.permission.GalleryAccessState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryAccessUiStateTest {
    @Test
    fun `root remains loading until both histories are loaded and permission is refreshed`() {
        assertTrue(state(handled = null, requested = true, historyApplied = true).isLoading)
        assertTrue(state(handled = false, requested = null, historyApplied = true).isLoading)
        assertTrue(state(handled = false, requested = true, historyApplied = false).isLoading)
        assertFalse(state(handled = false, requested = true, historyApplied = true).isLoading)
    }

    private fun state(
        handled: Boolean?,
        requested: Boolean?,
        historyApplied: Boolean,
    ) = GalleryAccessUiState(
        galleryAccessState = GalleryAccessState.Denied(canRequestAgain = false),
        onboardingHandled = handled,
        permissionRequested = requested,
        permissionHistoryApplied = historyApplied,
        isPermissionRecovery = false,
    )
}

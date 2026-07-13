package cn.soul2.imageai.media.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPermissionRequestHistoryPolicyTest {
    @Test
    fun `never requested can request without rationale`() {
        assertTrue(
            GalleryPermissionRequestHistoryPolicy.canRequestAgain(
                permissionRequested = false,
                rationaleResults = emptyList(),
            ),
        )
    }

    @Test
    fun `requested can retry when any required permission shows rationale`() {
        assertTrue(
            GalleryPermissionRequestHistoryPolicy.canRequestAgain(
                permissionRequested = true,
                rationaleResults = listOf(false, true),
            ),
        )
    }

    @Test
    fun `requested cannot retry when no required permission shows rationale`() {
        assertFalse(
            GalleryPermissionRequestHistoryPolicy.canRequestAgain(
                permissionRequested = true,
                rationaleResults = listOf(false, false),
            ),
        )
    }

    @Test
    fun `persisted request history preserves permanent denial after process recreation`() {
        val canRequestAgain = GalleryPermissionRequestHistoryPolicy.canRequestAgain(
            permissionRequested = true,
            rationaleResults = listOf(false, false),
        )

        assertEquals(
            GalleryAccessState.Denied(canRequestAgain = false),
            GalleryPermissionPolicy.resolve(
                sdkInt = 34,
                granted = emptySet(),
                canRequestAgain = canRequestAgain,
            ),
        )
    }
}

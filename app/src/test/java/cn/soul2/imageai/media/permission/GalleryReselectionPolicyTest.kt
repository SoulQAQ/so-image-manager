package cn.soul2.imageai.media.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryReselectionPolicyTest {
    @Test
    fun partialOrRequestableDeniedAccessCanUseThePermissionDialog() {
        assertEquals(
            GalleryReselectionDestination.PermissionRequest,
            GalleryReselectionPolicy.destination(34, GalleryAccessState.Partial),
        )
        assertEquals(
            GalleryReselectionDestination.PermissionRequest,
            GalleryReselectionPolicy.destination(
                33,
                GalleryAccessState.Denied(canRequestAgain = true),
            ),
        )
    }

    @Test
    fun alreadyFullOrPermanentlyDeniedAccessOpensVisibleSystemSettings() {
        assertEquals(
            GalleryReselectionDestination.AppSettings,
            GalleryReselectionPolicy.destination(34, GalleryAccessState.Full),
        )
        assertEquals(
            GalleryReselectionDestination.AppSettings,
            GalleryReselectionPolicy.destination(
                34,
                GalleryAccessState.Denied(canRequestAgain = false),
            ),
        )
    }
}

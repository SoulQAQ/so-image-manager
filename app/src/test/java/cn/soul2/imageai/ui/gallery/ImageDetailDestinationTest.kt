package cn.soul2.imageai.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDetailDestinationTest {
    @Test
    fun detailRouteIsPushedWithOnlyTheLocalDatabaseId() {
        assertEquals("image/{localId}", ImageDetailDestination.route)
        assertEquals("localId", ImageDetailDestination.localIdArgument)
        assertEquals("image/42", ImageDetailDestination.createRoute(42L))
    }
}

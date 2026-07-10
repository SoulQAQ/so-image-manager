package cn.soul2.imageai.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppDestinationTest {
    @Test
    fun destinationsAreStableUniqueAndHomeFirst() {
        assertEquals(listOf("home", "library", "tasks", "settings"), AppDestination.entries.map { it.route })
        assertEquals(AppDestination.entries.size, AppDestination.entries.map { it.route }.toSet().size)
        assertSame(AppDestination.HOME, AppDestination.start)
    }
}

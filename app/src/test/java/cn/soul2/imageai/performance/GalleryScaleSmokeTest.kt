package cn.soul2.imageai.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryScaleSmokeTest {
    @Test
    fun generatesDeterministicTenThousandImageFixtureWithinBudget() {
        val started = System.nanoTime()
        val values = GalleryScaleFixture.images(10_000)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L

        assertEquals(10_000, values.size)
        assertEquals("fixture-1", values.first().quickFingerprint)
        assertEquals("fixture-10000", values.last().quickFingerprint)
        assertTrue("fixture generation took ${elapsedMillis}ms", elapsedMillis < 3_000L)
    }

    @Test
    fun generatorSupportsLongTermHundredThousandTarget() {
        assertEquals(100_000, GalleryScaleFixture.images(100_000).size)
    }
}

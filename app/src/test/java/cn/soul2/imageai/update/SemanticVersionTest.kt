package cn.soul2.imageai.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun parsesStrictReleaseTagsAndOrdersComponentsNumerically() {
        assertEquals(SemanticVersion(0, 17, 0), SemanticVersion.parse("v0.17.0"))
        assertEquals(SemanticVersion(12, 3, 45), SemanticVersion.parse("12.3.45"))
        assertTrue(requireNotNull(SemanticVersion.parse("0.17.0")) > SemanticVersion(0, 16, 9))
    }

    @Test
    fun rejectsAmbiguousOrPrereleaseVersions() {
        listOf("0.17", "v0.17.0-beta", "01.2.3", "latest", "1.2.3.4").forEach { value ->
            assertNull(value, SemanticVersion.parse(value))
        }
    }
}

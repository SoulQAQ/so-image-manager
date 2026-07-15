package cn.soul2.imageai.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MetadataNormalizerTest {
    @Test
    fun normalizesCompatibilityCaseAndUnicodeWhitespaceWithOneSharedRule() {
        val normalized = MetadataNormalizer.normalizeTerm("  Ｃａｔ\u00a0  相册  ")

        assertEquals("Cat 相册", normalized.displayValue)
        assertEquals("cat 相册", normalized.normalizedKey)
    }

    @Test
    fun rejectsBlankUnpairedSurrogateAndOverlongTerms() {
        listOf("  \n ", "bad\uD800", "x".repeat(129)).forEach { value ->
            assertThrows(CanonicalValidationException::class.java) {
                MetadataNormalizer.normalizeTerm(value)
            }
        }
    }
}

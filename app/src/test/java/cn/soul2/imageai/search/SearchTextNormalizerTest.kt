package cn.soul2.imageai.search

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchTextNormalizerTest {
    @Test
    fun normalizesCompatibilityCaseAndWhitespaceWithLocaleRoot() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val normalized = SearchTextNormalizer.normalizeQuery("  Ｉstanbul\u00a0  相册\n")

            assertEquals("istanbul 相册", normalized.text)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun blankQueryNormalizesToEmptyText() {
        assertEquals("", SearchTextNormalizer.normalizeQuery(" \t\r\n\u3000").text)
    }

    @Test
    fun rejectsUnpairedUtf16Surrogates() {
        listOf("bad\uD800", "bad\uDC00").forEach { raw ->
            assertThrows(SearchValidationException::class.java) {
                SearchTextNormalizer.normalizeQuery(raw)
            }
        }
    }

    @Test
    fun acceptsExactlyOneHundredTwentyEightCodePoints() {
        val raw = "😀".repeat(SearchLimits.QUERY_CODE_POINTS)

        assertEquals(raw, SearchTextNormalizer.normalizeQuery(raw).text)
    }

    @Test
    fun rejectsOneHundredTwentyNineCodePoints() {
        val raw = "😀".repeat(SearchLimits.QUERY_CODE_POINTS + 1)

        assertThrows(SearchValidationException::class.java) {
            SearchTextNormalizer.normalizeQuery(raw)
        }
    }
}

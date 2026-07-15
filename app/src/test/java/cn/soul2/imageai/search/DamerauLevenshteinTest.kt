package cn.soul2.imageai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DamerauLevenshteinTest {
    @Test
    fun measuresInsertionDeletionAndSubstitution() {
        assertEquals(1, DamerauLevenshtein.withinDistance("cat", "cart", 1))
        assertEquals(1, DamerauLevenshtein.withinDistance("cart", "cat", 1))
        assertEquals(1, DamerauLevenshtein.withinDistance("cat", "cut", 1))
    }

    @Test
    fun adjacentTranspositionCostsOneEdit() {
        assertEquals(1, DamerauLevenshtein.withinDistance("form", "from", 1))
    }

    @Test
    fun worksOnUnicodeCodePointsInsteadOfUtf16Units() {
        assertEquals(1, DamerauLevenshtein.withinDistance("a😀b", "a😃b", 1))
    }

    @Test
    fun returnsNullWhenLengthOrRowMinimumExceedsTheLimit() {
        assertNull(DamerauLevenshtein.withinDistance("a", "abcdef", 2))
        assertNull(DamerauLevenshtein.withinDistance("kitten", "sitting", 2))
        assertEquals(3, DamerauLevenshtein.withinDistance("kitten", "sitting", 3))
    }

    @Test
    fun typoDistanceRuleMatchesNormalizedQueryLength() {
        assertNull(DamerauLevenshtein.maximumDistanceFor(0))
        assertNull(DamerauLevenshtein.maximumDistanceFor(2))
        assertEquals(1, DamerauLevenshtein.maximumDistanceFor(3))
        assertEquals(1, DamerauLevenshtein.maximumDistanceFor(5))
        assertEquals(2, DamerauLevenshtein.maximumDistanceFor(6))
        assertEquals(2, DamerauLevenshtein.maximumDistanceFor(128))
    }

    @Test
    fun validatesLimitLengthAndUtf16() {
        assertThrows(IllegalArgumentException::class.java) {
            DamerauLevenshtein.withinDistance("a", "b", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DamerauLevenshtein.withinDistance("a", "b", Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DamerauLevenshtein.maximumDistanceFor(129)
        }
        assertThrows(SearchValidationException::class.java) {
            DamerauLevenshtein.withinDistance("bad\uD800", "bad", 1)
        }
    }
}

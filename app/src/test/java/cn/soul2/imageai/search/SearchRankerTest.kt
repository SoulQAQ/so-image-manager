package cn.soul2.imageai.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankerTest {
    @Test
    fun tierOrderAndStableTieBreakFollowTheFrozenContract() {
        val results = listOf(
            result(6, SearchTier.PINYIN, 600.0, 1.0, 100, 10, "b", 6),
            result(5, SearchTier.TYPO, 600.0, 1.0, 100, 10, "b", 5),
            result(4, SearchTier.SUBSTRING, 600.0, 1.0, 100, 10, "b", 4),
            result(3, SearchTier.FTS4, 600.0, 1.0, 100, 10, "b", 3),
            result(2, SearchTier.EXACT_STRUCTURED, 600.0, 1.0, 100, 10, "b", 2),
            result(1, SearchTier.USER, 300.0, 0.5, 1, 1, "a", 1),
        ).shuffled()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), SearchRanker.rank(results).map { it.imageLocalId })
    }

    @Test
    fun tiesUseWeightScoreSortTimeMediaIdentityAndLocalIdInThatOrder() {
        val results = listOf(
            result(1, SearchTier.FTS4, 300.0, 0.8, 100, 10, "a", 1),
            result(2, SearchTier.FTS4, 500.0, 0.7, 90, 9, "a", 2),
            result(3, SearchTier.FTS4, 500.0, 0.9, 80, 8, "a", 3),
            result(4, SearchTier.FTS4, 500.0, 0.9, 110, 7, "a", 4),
            result(5, SearchTier.FTS4, 500.0, 0.9, 110, 12, "a", 5),
            result(6, SearchTier.FTS4, 500.0, 0.9, 110, 12, "z", 6),
            result(7, SearchTier.FTS4, 500.0, 0.9, 110, 12, "z", 7),
        )

        assertEquals(listOf(7L, 6L, 5L, 4L, 3L, 2L, 1L), SearchRanker.rank(results).map { it.imageLocalId })
    }

    @Test
    fun duplicateImageKeepsItsBestHitOnly() {
        val ranked = SearchRanker.rank(
            listOf(
                result(1, SearchTier.PINYIN, 300.0, 1.0, 100, 1, "a", 1),
                result(1, SearchTier.EXACT_STRUCTURED, 500.0, 1.0, 100, 1, "a", 1),
            ),
        )

        assertEquals(1, ranked.size)
        assertEquals(SearchTier.EXACT_STRUCTURED, ranked.single().tier)
    }

    private fun result(
        imageLocalId: Long,
        tier: SearchTier,
        weight: Double,
        score: Double,
        sortTime: Long,
        mediaStoreId: Long,
        volumeName: String,
        localId: Long,
    ) = SearchResult(
        imageLocalId = imageLocalId,
        tier = tier,
        field = SearchField.CAPTION,
        fieldWeight = weight,
        matchScore = score,
        sortTimeEpochMillis = sortTime,
        mediaStoreId = mediaStoreId,
        volumeName = volumeName,
        stableLocalId = localId,
        reason = "test",
    )
}

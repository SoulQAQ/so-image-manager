package cn.soul2.imageai.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisRetentionPolicyTest {
    @Test
    fun countsDiagnosticsWithinTheTwoHistoryLimitButPrefersToRetainThem() {
        val candidates = listOf(
            candidate("a1", image = 1L, completed = 10L),
            candidate("a2", image = 1L, completed = 20L),
            candidate("a3", image = 1L, completed = 30L),
            candidate("a4", image = 1L, completed = 5L, protected = true),
            candidate("b1", image = 2L, completed = 10L),
        )

        assertEquals(
            setOf("a1", "a2"),
            AnalysisRetentionPolicy.selectForDeletion(candidates).toSet(),
        )
    }

    @Test
    fun trimsOldestUnprotectedHistoryToTheGlobalBudget() {
        val candidates = listOf(
            candidate("old", image = 1L, completed = 10L, bytes = 60L),
            candidate("new", image = 2L, completed = 20L, bytes = 60L),
            candidate("protected", image = 3L, completed = 1L, bytes = 80L, protected = true),
        )

        assertEquals(
            listOf("old"),
            AnalysisRetentionPolicy.selectForDeletion(candidates, globalBudgetBytes = 150L),
        )
    }

    @Test
    fun trimsOldestDiagnosticWhenDiagnosticsAloneExceedGlobalBudget() {
        val candidates = listOf(
            candidate("old", image = 1L, completed = 10L, bytes = 60L, protected = true),
            candidate("middle", image = 2L, completed = 20L, bytes = 60L, protected = true),
            candidate("new", image = 3L, completed = 30L, bytes = 60L, protected = true),
        )

        assertEquals(
            listOf("old"),
            AnalysisRetentionPolicy.selectForDeletion(candidates, globalBudgetBytes = 120L),
        )
    }

    private fun candidate(
        id: String,
        image: Long,
        completed: Long,
        bytes: Long = 1L,
        protected: Boolean = false,
    ) = AnalysisRetentionCandidate(
        analysisId = id,
        imageLocalId = image,
        completedAtEpochMillis = completed,
        estimatedBytes = bytes,
        hasDiagnostic = protected,
    )
}

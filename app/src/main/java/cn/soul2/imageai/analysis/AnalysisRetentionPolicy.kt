package cn.soul2.imageai.analysis

data class AnalysisRetentionCandidate(
    val analysisId: String,
    val imageLocalId: Long,
    val completedAtEpochMillis: Long,
    val estimatedBytes: Long,
    val hasDiagnostic: Boolean,
)

object AnalysisRetentionPolicy {
    const val MAX_INACTIVE_PER_IMAGE = 2
    const val DEFAULT_GLOBAL_BUDGET_BYTES = 256L * 1_024L * 1_024L

    fun selectForDeletion(
        candidates: List<AnalysisRetentionCandidate>,
        maxInactivePerImage: Int = MAX_INACTIVE_PER_IMAGE,
        globalBudgetBytes: Long = DEFAULT_GLOBAL_BUDGET_BYTES,
    ): List<String> {
        require(maxInactivePerImage >= 0)
        require(globalBudgetBytes >= 0L)
        require(candidates.all { it.estimatedBytes >= 0L })

        val deleted = linkedSetOf<String>()
        candidates.groupBy(AnalysisRetentionCandidate::imageLocalId).values.forEach { imageRows ->
            imageRows
                .sortedWith(
                    compareByDescending<AnalysisRetentionCandidate> { it.hasDiagnostic }
                        .thenByDescending { it.completedAtEpochMillis }
                        .thenByDescending { it.analysisId },
                )
                .drop(maxInactivePerImage)
                .forEach { deleted += it.analysisId }
        }

        val retained = candidates.filterNot { it.analysisId in deleted }
        var retainedBytes = retained.sumOf(AnalysisRetentionCandidate::estimatedBytes)
        if (retainedBytes > globalBudgetBytes) {
            retained
                .sortedWith(
                    compareBy<AnalysisRetentionCandidate> { it.hasDiagnostic }
                        .thenBy { it.completedAtEpochMillis }
                        .thenBy { it.analysisId },
                )
                .forEach { candidate ->
                    if (retainedBytes <= globalBudgetBytes) return@forEach
                    deleted += candidate.analysisId
                    retainedBytes -= candidate.estimatedBytes
                }
        }
        return deleted.toList()
    }
}

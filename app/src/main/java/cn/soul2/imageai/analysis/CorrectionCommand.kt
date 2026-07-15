package cn.soul2.imageai.analysis

import cn.soul2.imageai.data.db.entity.AnalysisTermKind

sealed interface CorrectionCommand {
    data class SetCaption(val value: String) : CorrectionCommand
    data object ClearCaption : CorrectionCommand
    data object InheritCaption : CorrectionCommand
    data class AddTerm(val kind: AnalysisTermKind, val value: String) : CorrectionCommand
    data class DeleteTerm(val kind: AnalysisTermKind, val value: String) : CorrectionCommand
    data class RestoreTerm(val kind: AnalysisTermKind, val value: String) : CorrectionCommand
}

sealed interface CorrectionResult {
    data class Applied(val projectionGeneration: Long) : CorrectionResult
    data class NoChange(val projectionGeneration: Long) : CorrectionResult
}

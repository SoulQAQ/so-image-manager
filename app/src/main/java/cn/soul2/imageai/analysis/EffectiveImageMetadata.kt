package cn.soul2.imageai.analysis

import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.CaptionCorrectionMode
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class EffectiveImageMetadata(
    val imageLocalId: Long,
    val activeAnalysis: ActiveAnalysisProvenance?,
    val caption: String?,
    val captionSource: EffectiveCaptionSource,
    val projectionGeneration: Long,
    val updatedAtEpochMillis: Long?,
    val terms: List<EffectiveMetadataTerm>,
    val captionCorrection: CaptionCorrectionState?,
    val termOverrides: List<TermOverrideState>,
    val history: List<AnalysisHistorySummary>,
)

data class ActiveAnalysisProvenance(
    val analysisId: String,
    val schemaVersion: Int,
    val providerProfileId: String,
    val modelProfileId: String,
    val protocolDefinitionId: String,
    val promptTemplateId: String,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
)

data class EffectiveMetadataTerm(
    val kind: AnalysisTermKind,
    val normalizedKey: String,
    val displayValue: String,
    val source: EffectiveTermSource,
    val confidence: Double?,
    val sourceAnalysisId: String?,
)

data class CaptionCorrectionState(
    val mode: CaptionCorrectionMode,
    val value: String?,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

data class TermOverrideState(
    val kind: AnalysisTermKind,
    val normalizedKey: String,
    val action: UserTermOverrideAction,
    val displayValue: String?,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

data class AnalysisHistorySummary(
    val analysisId: String,
    val isActive: Boolean,
    val schemaVersion: Int,
    val providerProfileId: String,
    val modelProfileId: String,
    val completedAtEpochMillis: Long,
    val diagnosticCode: String?,
)

fun interface EffectiveMetadataReader {
    fun observeEffectiveMetadata(imageLocalId: Long): Flow<EffectiveImageMetadata?>

    data object Empty : EffectiveMetadataReader {
        override fun observeEffectiveMetadata(imageLocalId: Long): Flow<EffectiveImageMetadata?> =
            flowOf(null)
    }
}

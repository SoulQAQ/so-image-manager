package cn.soul2.imageai.analysis

import cn.soul2.imageai.data.db.entity.AnalysisTermEntity
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.CaptionCorrectionMode
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveImageMetadataEntity
import cn.soul2.imageai.data.db.entity.EffectiveImageTermEntity
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.ImageAnalysisEntity
import cn.soul2.imageai.data.db.entity.ImageUserCorrectionEntity
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction
import cn.soul2.imageai.data.db.entity.UserTermOverrideEntity

sealed interface EffectiveProjectionBuildResult {
    data class Ready(
        val metadata: EffectiveImageMetadataEntity,
        val terms: List<EffectiveImageTermEntity>,
    ) : EffectiveProjectionBuildResult

    data class Blocked(val code: String, val detail: String) : EffectiveProjectionBuildResult
}

object EffectiveProjectionBuilder {
    const val MAX_EFFECTIVE_TAGS = 256
    const val MAX_EFFECTIVE_CATEGORIES = 64

    fun build(
        imageLocalId: Long,
        activeAnalysis: ImageAnalysisEntity?,
        analysisTerms: List<AnalysisTermEntity>,
        correction: ImageUserCorrectionEntity?,
        overrides: List<UserTermOverrideEntity>,
        projectionGeneration: Long,
        nowEpochMillis: Long,
    ): EffectiveProjectionBuildResult {
        if (imageLocalId <= 0L) invalid("projection imageLocalId must be positive")
        if (projectionGeneration <= 0L) invalid("projection generation must be positive")
        if (nowEpochMillis < 0L) invalid("projection timestamp must not be negative")
        if (activeAnalysis != null && activeAnalysis.imageLocalId != imageLocalId) {
            invalid("active analysis belongs to another image")
        }
        if (correction != null && correction.imageLocalId != imageLocalId) {
            invalid("caption correction belongs to another image")
        }
        if (analysisTerms.any { it.analysisId != activeAnalysis?.analysisId }) {
            invalid("analysis term does not belong to the active analysis")
        }
        if (overrides.any { it.imageLocalId != imageLocalId }) {
            invalid("term override belongs to another image")
        }
        overrides.forEach { override ->
            if (override.kind == AnalysisTermKind.SEARCH_TOKEN) {
                invalid("search tokens are not user editable")
            }
            val normalizedKey = MetadataNormalizer.normalizeTerm(override.normalizedKey).normalizedKey
            if (normalizedKey != override.normalizedKey) {
                invalid("term override key is not normalized")
            }
            if (
                override.action == UserTermOverrideAction.TOMBSTONE &&
                override.displayValue != null
            ) {
                invalid("term tombstone must not have a display value")
            }
        }

        val caption = effectiveCaption(activeAnalysis, correction)
        val tombstones = overrides
            .filter { it.action == UserTermOverrideAction.TOMBSTONE }
            .groupBy(UserTermOverrideEntity::kind)
            .mapValues { (_, values) -> values.mapTo(mutableSetOf()) { it.normalizedKey } }
        val tokenTombstones = buildSet {
            addAll(tombstones[AnalysisTermKind.TAG].orEmpty())
            addAll(tombstones[AnalysisTermKind.CATEGORY].orEmpty())
        }
        val effective = linkedMapOf<Pair<AnalysisTermKind, String>, EffectiveImageTermEntity>()

        analysisTerms.forEach { term ->
            val suppressed = when (term.kind) {
                AnalysisTermKind.TAG,
                AnalysisTermKind.CATEGORY,
                -> term.normalizedKey in tombstones[term.kind].orEmpty()
                AnalysisTermKind.SEARCH_TOKEN -> term.normalizedKey in tokenTombstones
            }
            if (!suppressed) {
                effective[term.kind to term.normalizedKey] = EffectiveImageTermEntity(
                    imageLocalId = imageLocalId,
                    kind = term.kind,
                    normalizedKey = term.normalizedKey,
                    displayValue = term.displayValue,
                    source = EffectiveTermSource.AI,
                    confidence = term.confidence,
                    sourceAnalysisId = requireNotNull(activeAnalysis).analysisId,
                )
            }
        }

        overrides.filter { it.action == UserTermOverrideAction.ADD }.forEach { addition ->
            if (addition.kind == AnalysisTermKind.SEARCH_TOKEN) {
                invalid("users cannot add search tokens")
            }
            val displayValue = addition.displayValue
                ?: invalid("user addition must have a display value")
            val normalized = MetadataNormalizer.normalizeTerm(displayValue)
            if (normalized.normalizedKey != addition.normalizedKey) {
                invalid("user addition key does not match its display value")
            }
            effective[addition.kind to addition.normalizedKey] = EffectiveImageTermEntity(
                imageLocalId = imageLocalId,
                kind = addition.kind,
                normalizedKey = addition.normalizedKey,
                displayValue = normalized.displayValue,
                source = EffectiveTermSource.USER,
                confidence = null,
                sourceAnalysisId = null,
            )
        }

        val tagCount = effective.keys.count { it.first == AnalysisTermKind.TAG }
        if (tagCount > MAX_EFFECTIVE_TAGS) {
            return EffectiveProjectionBuildResult.Blocked(
                code = "EFFECTIVE_TAG_LIMIT",
                detail = "$tagCount effective tags exceed $MAX_EFFECTIVE_TAGS",
            )
        }
        val categoryCount = effective.keys.count { it.first == AnalysisTermKind.CATEGORY }
        if (categoryCount > MAX_EFFECTIVE_CATEGORIES) {
            return EffectiveProjectionBuildResult.Blocked(
                code = "EFFECTIVE_CATEGORY_LIMIT",
                detail = "$categoryCount effective categories exceed $MAX_EFFECTIVE_CATEGORIES",
            )
        }

        val sortedTerms = effective.values.sortedWith(
            compareBy<EffectiveImageTermEntity>({ it.kind.ordinal }, { it.normalizedKey }),
        )
        return EffectiveProjectionBuildResult.Ready(
            metadata = EffectiveImageMetadataEntity(
                imageLocalId = imageLocalId,
                caption = caption.value,
                captionSource = caption.source,
                projectionGeneration = projectionGeneration,
                updatedAtEpochMillis = nowEpochMillis,
            ),
            terms = sortedTerms,
        )
    }

    private fun effectiveCaption(
        analysis: ImageAnalysisEntity?,
        correction: ImageUserCorrectionEntity?,
    ): EffectiveCaption = when (correction?.captionMode ?: CaptionCorrectionMode.INHERIT) {
        CaptionCorrectionMode.INHERIT -> {
            if (correction?.captionValue != null) {
                invalid("INHERIT caption correction must not store a value")
            }
            if (analysis == null) {
                EffectiveCaption(null, EffectiveCaptionSource.NONE)
            } else {
                EffectiveCaption(analysis.caption, EffectiveCaptionSource.AI)
            }
        }
        CaptionCorrectionMode.SET -> EffectiveCaption(
            value = MetadataNormalizer.normalizeCaption(
                correction?.captionValue ?: invalid("SET caption correction requires a value"),
            ),
            source = EffectiveCaptionSource.USER,
        )
        CaptionCorrectionMode.CLEARED -> {
            if (correction?.captionValue != null) {
                invalid("CLEARED caption correction must not store a value")
            }
            EffectiveCaption(null, EffectiveCaptionSource.USER_CLEARED)
        }
    }

    private data class EffectiveCaption(
        val value: String?,
        val source: EffectiveCaptionSource,
    )
}

package cn.soul2.imageai.analysis

import androidx.room.withTransaction
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.ActiveImageAnalysisEntity
import cn.soul2.imageai.data.db.entity.AnalysisActivationDiagnosticEntity
import cn.soul2.imageai.data.db.entity.AnalysisTermEntity
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.CaptionCorrectionMode
import cn.soul2.imageai.data.db.entity.EffectiveImageMetadataEntity
import cn.soul2.imageai.data.db.entity.EffectiveImageTermEntity
import cn.soul2.imageai.data.db.entity.ImageAnalysisEntity
import cn.soul2.imageai.data.db.entity.ImageUserCorrectionEntity
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction
import cn.soul2.imageai.data.db.entity.UserTermOverrideEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

sealed interface ActivationResult {
    data class Activated(val projectionGeneration: Long) : ActivationResult
    data class AlreadyActive(val projectionGeneration: Long) : ActivationResult
    data class Blocked(val code: String) : ActivationResult
}

data class EffectiveProjectionSnapshot(
    val metadata: EffectiveImageMetadataEntity,
    val terms: List<EffectiveImageTermEntity>,
)

data class EffectiveMetadataSnapshot(
    val activeAnalysisId: String?,
    val metadata: EffectiveImageMetadataEntity,
    val terms: List<EffectiveImageTermEntity>,
)

class CanonicalMetadataRepository(
    private val database: AppDatabase,
    private val searchProjectionWriter: SearchProjectionWriter = SearchProjectionWriter.NoOp,
    private val maintenanceWarning: (String) -> Unit = {},
) : EffectiveMetadataReader {
    private val analysisDao = database.analysisDao()
    private val effectiveDao = database.effectiveMetadataDao()

    suspend fun activateAnalysis(draft: CanonicalAnalysisDraft): ActivationResult {
        val validated = draft.validate()
        val result = database.withTransaction {
            if (database.imageDao().getById(validated.imageLocalId) == null) {
                invalid("analysis image does not exist")
            }
            val expectedAnalysis = validated.toEntity()
            val expectedTerms = validated.toTermEntities()
            val existing = analysisDao.getAnalysis(validated.analysisId)
            if (existing == null) {
                check(analysisDao.insertAnalysis(expectedAnalysis) != -1L) {
                    "analysis insert was unexpectedly ignored"
                }
                if (expectedTerms.isNotEmpty()) analysisDao.insertTerms(expectedTerms)
            } else {
                if (existing != expectedAnalysis || analysisDao.getTerms(validated.analysisId) != expectedTerms) {
                    invalid("analysisId already exists with different canonical content")
                }
            }

            val currentMetadata = effectiveDao.getMetadata(validated.imageLocalId)
            val active = analysisDao.getActive(validated.imageLocalId)
            if (active?.analysisId == validated.analysisId) {
                return@withTransaction ActivationResult.AlreadyActive(
                    currentMetadata?.projectionGeneration ?: 0L,
                )
            }

            val nextGeneration = (currentMetadata?.projectionGeneration ?: 0L) + 1L
            when (
                val projection = EffectiveProjectionBuilder.build(
                    imageLocalId = validated.imageLocalId,
                    activeAnalysis = expectedAnalysis,
                    analysisTerms = expectedTerms,
                    correction = effectiveDao.getCorrection(validated.imageLocalId),
                    overrides = effectiveDao.getOverrides(validated.imageLocalId),
                    projectionGeneration = nextGeneration,
                    nowEpochMillis = validated.completedAtEpochMillis,
                )
            ) {
                is EffectiveProjectionBuildResult.Blocked -> {
                    analysisDao.upsertDiagnostic(
                        AnalysisActivationDiagnosticEntity(
                            analysisId = validated.analysisId,
                            code = projection.code,
                            detail = projection.detail,
                            updatedAtEpochMillis = validated.completedAtEpochMillis,
                        ),
                    )
                    ActivationResult.Blocked(projection.code)
                }
                is EffectiveProjectionBuildResult.Ready -> {
                    val snapshot = EffectiveProjectionSnapshot(
                        projection.metadata,
                        projection.terms,
                    )
                    when (
                        val searchPreparation = searchProjectionWriter.prepareForImage(
                            validated.imageLocalId,
                            snapshot,
                        )
                    ) {
                        is SearchProjectionPreparation.Blocked -> {
                            analysisDao.upsertDiagnostic(
                                AnalysisActivationDiagnosticEntity(
                                    analysisId = validated.analysisId,
                                    code = searchPreparation.code,
                                    detail = searchPreparation.detail,
                                    updatedAtEpochMillis = validated.completedAtEpochMillis,
                                ),
                            )
                            ActivationResult.Blocked(searchPreparation.code)
                        }
                        is SearchProjectionPreparation.Ready -> {
                            analysisDao.upsertActive(
                                ActiveImageAnalysisEntity(
                                    imageLocalId = validated.imageLocalId,
                                    analysisId = validated.analysisId,
                                ),
                            )
                            effectiveDao.deleteTerms(validated.imageLocalId)
                            effectiveDao.upsertMetadata(projection.metadata)
                            if (projection.terms.isNotEmpty()) {
                                effectiveDao.upsertTerms(projection.terms)
                            }
                            searchProjectionWriter.replaceForImage(searchPreparation)
                            analysisDao.deleteDiagnostic(validated.analysisId)
                            ActivationResult.Activated(nextGeneration)
                        }
                    }
                }
            }
        }
        if (result is ActivationResult.Activated || result is ActivationResult.Blocked) {
            runCatching { enforceRetention() }
                .onFailure { maintenanceWarning("Canonical analysis retention will retry") }
        }
        return result
    }

    suspend fun getEffectiveSnapshot(imageLocalId: Long): EffectiveMetadataSnapshot? =
        database.withTransaction {
            val metadata = effectiveDao.getMetadata(imageLocalId) ?: return@withTransaction null
            EffectiveMetadataSnapshot(
                activeAnalysisId = analysisDao.getActive(imageLocalId)?.analysisId,
                metadata = metadata,
                terms = effectiveDao.getTerms(imageLocalId),
            )
        }

    suspend fun countCompletedAnalysesSince(dayStartEpochMillis: Long): Int =
        analysisDao.countCompletedSince(dayStartEpochMillis)

    suspend fun promoteUnprocessedToMain(imageLocalId: Long): Int =
        database.imageDao().promoteUnprocessedToMain(imageLocalId)

    override fun observeEffectiveMetadata(imageLocalId: Long): Flow<EffectiveImageMetadata?> {
        if (imageLocalId <= 0L) invalid("effective metadata imageLocalId must be positive")
        return combine(
            effectiveDao.observeMetadata(imageLocalId),
            analysisDao.observeHistory(imageLocalId),
        ) { _, _ -> Unit }
            .map { loadEffectiveMetadata(imageLocalId) }
            .distinctUntilChanged()
    }

    private suspend fun loadEffectiveMetadata(imageLocalId: Long): EffectiveImageMetadata? =
        database.withTransaction {
            val metadata = effectiveDao.getMetadata(imageLocalId)
            val active = analysisDao.getActiveAnalysis(imageLocalId)
            val correction = effectiveDao.getCorrection(imageLocalId)
            val overrides = effectiveDao.getOverrides(imageLocalId)
            val terms = effectiveDao.getTerms(imageLocalId)
            val history = analysisDao.getHistory(imageLocalId)
            if (
                metadata == null && active == null && correction == null &&
                overrides.isEmpty() && terms.isEmpty() && history.isEmpty()
            ) {
                return@withTransaction null
            }
            EffectiveImageMetadata(
                imageLocalId = imageLocalId,
                activeAnalysis = active?.let { analysis ->
                    ActiveAnalysisProvenance(
                        analysisId = analysis.analysisId,
                        schemaVersion = analysis.schemaVersion,
                        providerProfileId = analysis.providerProfileId,
                        modelProfileId = analysis.modelProfileId,
                        protocolDefinitionId = analysis.protocolDefinitionId,
                        promptTemplateId = analysis.promptTemplateId,
                        createdAtEpochMillis = analysis.createdAtEpochMillis,
                        completedAtEpochMillis = analysis.completedAtEpochMillis,
                    )
                },
                caption = metadata?.caption,
                captionSource = metadata?.captionSource
                    ?: cn.soul2.imageai.data.db.entity.EffectiveCaptionSource.NONE,
                projectionGeneration = metadata?.projectionGeneration ?: 0L,
                updatedAtEpochMillis = metadata?.updatedAtEpochMillis,
                terms = terms.map { term ->
                    EffectiveMetadataTerm(
                        kind = term.kind,
                        normalizedKey = term.normalizedKey,
                        displayValue = term.displayValue,
                        source = term.source,
                        confidence = term.confidence,
                        sourceAnalysisId = term.sourceAnalysisId,
                    )
                },
                captionCorrection = correction?.let { value ->
                    CaptionCorrectionState(
                        mode = value.captionMode,
                        value = value.captionValue,
                        revision = value.revision,
                        updatedAtEpochMillis = value.updatedAtEpochMillis,
                    )
                },
                termOverrides = overrides.map { override ->
                    TermOverrideState(
                        kind = override.kind,
                        normalizedKey = override.normalizedKey,
                        action = override.action,
                        displayValue = override.displayValue,
                        revision = override.revision,
                        updatedAtEpochMillis = override.updatedAtEpochMillis,
                    )
                },
                history = history.map { row ->
                    AnalysisHistorySummary(
                        analysisId = row.analysis.analysisId,
                        isActive = row.isActive,
                        schemaVersion = row.analysis.schemaVersion,
                        providerProfileId = row.analysis.providerProfileId,
                        modelProfileId = row.analysis.modelProfileId,
                        completedAtEpochMillis = row.analysis.completedAtEpochMillis,
                        diagnosticCode = row.diagnosticCode,
                    )
                },
            )
        }

    suspend fun applyCorrection(
        imageLocalId: Long,
        command: CorrectionCommand,
        nowEpochMillis: Long,
    ): CorrectionResult {
        if (imageLocalId <= 0L) invalid("correction imageLocalId must be positive")
        if (nowEpochMillis < 0L) invalid("correction timestamp must not be negative")
        val prepared = command.prepare()
        return database.withTransaction {
            if (database.imageDao().getById(imageLocalId) == null) {
                invalid("correction image does not exist")
            }
            val currentMetadata = effectiveDao.getMetadata(imageLocalId)
            val active = analysisDao.getActiveAnalysis(imageLocalId)
            val analysisTerms = active?.let { analysisDao.getTerms(it.analysisId) }.orEmpty()
            var correction = effectiveDao.getCorrection(imageLocalId)
            val overrides = effectiveDao.getOverrides(imageLocalId)
                .associateBy { it.kind to it.normalizedKey }
                .toMutableMap()
            val revision = maxOf(
                correction?.revision ?: 0L,
                overrides.values.maxOfOrNull(UserTermOverrideEntity::revision) ?: 0L,
            ) + 1L
            var changed = false

            when (prepared) {
                is PreparedCorrection.SetCaption -> {
                    val next = ImageUserCorrectionEntity(
                        imageLocalId,
                        CaptionCorrectionMode.SET,
                        prepared.value,
                        revision,
                        nowEpochMillis,
                    )
                    changed = correction != next.copy(
                        revision = correction?.revision ?: revision,
                        updatedAtEpochMillis = correction?.updatedAtEpochMillis ?: nowEpochMillis,
                    )
                    correction = next
                }
                PreparedCorrection.ClearCaption -> {
                    changed = correction?.captionMode != CaptionCorrectionMode.CLEARED
                    correction = ImageUserCorrectionEntity(
                        imageLocalId,
                        CaptionCorrectionMode.CLEARED,
                        null,
                        revision,
                        nowEpochMillis,
                    )
                }
                PreparedCorrection.InheritCaption -> {
                    changed = correction != null
                    correction = null
                }
                is PreparedCorrection.AddTerm -> {
                    val key = prepared.kind to prepared.term.normalizedKey
                    val next = UserTermOverrideEntity(
                        imageLocalId,
                        prepared.kind,
                        prepared.term.normalizedKey,
                        UserTermOverrideAction.ADD,
                        prepared.term.displayValue,
                        revision,
                        nowEpochMillis,
                    )
                    changed = overrides[key]?.let { current ->
                        current.action != next.action || current.displayValue != next.displayValue
                    } ?: true
                    overrides[key] = next
                }
                is PreparedCorrection.DeleteTerm -> {
                    val key = prepared.kind to prepared.term.normalizedKey
                    val current = overrides[key]
                    val aiContains = analysisTerms.any {
                        it.kind == prepared.kind && it.normalizedKey == prepared.term.normalizedKey
                    }
                    when {
                        current?.action == UserTermOverrideAction.TOMBSTONE -> Unit
                        aiContains -> {
                            overrides[key] = UserTermOverrideEntity(
                                imageLocalId,
                                prepared.kind,
                                prepared.term.normalizedKey,
                                UserTermOverrideAction.TOMBSTONE,
                                null,
                                revision,
                                nowEpochMillis,
                            )
                            changed = true
                        }
                        current?.action == UserTermOverrideAction.ADD -> {
                            overrides.remove(key)
                            changed = true
                        }
                    }
                }
                is PreparedCorrection.RestoreTerm -> {
                    val key = prepared.kind to prepared.term.normalizedKey
                    if (overrides[key]?.action == UserTermOverrideAction.TOMBSTONE) {
                        overrides.remove(key)
                        changed = true
                    }
                }
            }

            val currentGeneration = currentMetadata?.projectionGeneration ?: 0L
            if (!changed) return@withTransaction CorrectionResult.NoChange(currentGeneration)
            validateOverrideCounts(overrides.values)
            val nextGeneration = currentGeneration + 1L
            val projection = EffectiveProjectionBuilder.build(
                imageLocalId = imageLocalId,
                activeAnalysis = active,
                analysisTerms = analysisTerms,
                correction = correction,
                overrides = overrides.values.toList(),
                projectionGeneration = nextGeneration,
                nowEpochMillis = nowEpochMillis,
            )
            if (projection is EffectiveProjectionBuildResult.Blocked) {
                invalid(projection.detail)
            }
            projection as EffectiveProjectionBuildResult.Ready
            val searchPreparation = searchProjectionWriter.prepareForImage(
                imageLocalId,
                EffectiveProjectionSnapshot(projection.metadata, projection.terms),
            )
            if (searchPreparation is SearchProjectionPreparation.Blocked) {
                invalid("${searchPreparation.code}: ${searchPreparation.detail}")
            }
            searchPreparation as SearchProjectionPreparation.Ready

            effectiveDao.deleteCorrection(imageLocalId)
            if (correction != null) effectiveDao.upsertCorrection(correction)
            effectiveDao.deleteOverrides(imageLocalId)
            if (overrides.isNotEmpty()) effectiveDao.upsertOverrides(overrides.values.toList())
            effectiveDao.deleteTerms(imageLocalId)
            effectiveDao.upsertMetadata(projection.metadata)
            if (projection.terms.isNotEmpty()) effectiveDao.upsertTerms(projection.terms)
            searchProjectionWriter.replaceForImage(searchPreparation)
            CorrectionResult.Applied(nextGeneration)
        }
    }

    suspend fun enforceRetention(): Int = database.withTransaction {
        val candidates = analysisDao.getInactiveRetentionCandidates()
        val selected = AnalysisRetentionPolicy.selectForDeletion(candidates)
        selected.count { analysisId ->
            analysisDao.deleteInactiveAnalysis(analysisId) == 1
        }
    }

}

private sealed interface PreparedCorrection {
    data class SetCaption(val value: String) : PreparedCorrection
    data object ClearCaption : PreparedCorrection
    data object InheritCaption : PreparedCorrection
    data class AddTerm(
        val kind: AnalysisTermKind,
        val term: NormalizedMetadataTerm,
    ) : PreparedCorrection
    data class DeleteTerm(
        val kind: AnalysisTermKind,
        val term: NormalizedMetadataTerm,
    ) : PreparedCorrection
    data class RestoreTerm(
        val kind: AnalysisTermKind,
        val term: NormalizedMetadataTerm,
    ) : PreparedCorrection
}

private fun CorrectionCommand.prepare(): PreparedCorrection = when (this) {
    is CorrectionCommand.SetCaption -> PreparedCorrection.SetCaption(
        MetadataNormalizer.normalizeCaption(value),
    )
    CorrectionCommand.ClearCaption -> PreparedCorrection.ClearCaption
    CorrectionCommand.InheritCaption -> PreparedCorrection.InheritCaption
    is CorrectionCommand.AddTerm -> PreparedCorrection.AddTerm(
        validateEditableKind(kind),
        MetadataNormalizer.normalizeTerm(value),
    )
    is CorrectionCommand.DeleteTerm -> PreparedCorrection.DeleteTerm(
        validateEditableKind(kind),
        MetadataNormalizer.normalizeTerm(value),
    )
    is CorrectionCommand.RestoreTerm -> PreparedCorrection.RestoreTerm(
        validateEditableKind(kind),
        MetadataNormalizer.normalizeTerm(value),
    )
}

private fun validateEditableKind(kind: AnalysisTermKind): AnalysisTermKind {
    if (kind == AnalysisTermKind.SEARCH_TOKEN) invalid("search tokens are not user editable")
    return kind
}

private fun validateOverrideCounts(overrides: Collection<UserTermOverrideEntity>) {
    val tagTombstones = overrides.count {
        it.kind == AnalysisTermKind.TAG && it.action == UserTermOverrideAction.TOMBSTONE
    }
    if (tagTombstones > 512) invalid("tag tombstone limit exceeded")
    val categoryTombstones = overrides.count {
        it.kind == AnalysisTermKind.CATEGORY && it.action == UserTermOverrideAction.TOMBSTONE
    }
    if (categoryTombstones > 128) invalid("category tombstone limit exceeded")
}

private fun ValidatedCanonicalAnalysis.toEntity() = ImageAnalysisEntity(
    analysisId = analysisId,
    imageLocalId = imageLocalId,
    schemaVersion = schemaVersion,
    caption = caption,
    extensionJson = extensionJson,
    contentHash = contentHash,
    providerProfileId = providerProfileId,
    modelProfileId = modelProfileId,
    protocolDefinitionId = protocolDefinitionId,
    promptTemplateId = promptTemplateId,
    createdAtEpochMillis = createdAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
)

private fun ValidatedCanonicalAnalysis.toTermEntities(): List<AnalysisTermEntity> = buildList {
    fun addTerms(kind: AnalysisTermKind, values: List<ValidatedCanonicalTerm>) {
        values.forEach { term ->
            add(
                AnalysisTermEntity(
                    analysisId = analysisId,
                    kind = kind,
                    normalizedKey = term.normalizedKey,
                    displayValue = term.displayValue,
                    confidence = term.confidence,
                ),
            )
        }
    }
    addTerms(AnalysisTermKind.TAG, tags)
    addTerms(AnalysisTermKind.CATEGORY, categories)
    addTerms(AnalysisTermKind.SEARCH_TOKEN, searchTokens)
}.sortedWith(compareBy({ it.kind.name }, { it.normalizedKey }))

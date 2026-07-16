package cn.soul2.imageai.search

import android.database.sqlite.SQLiteException
import cn.soul2.imageai.data.db.dao.SearchIndexDao
import cn.soul2.imageai.data.db.dao.SearchMappingCandidate
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.SearchDocumentEntity
import cn.soul2.imageai.data.db.entity.SearchGramOwnerType
import cn.soul2.imageai.data.db.entity.SearchTermOwnership
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

internal class RoomImageSearchRepository(
    private val dao: SearchIndexDao,
    private val clock: SearchMonotonicClock = SearchMonotonicClock.System,
    private val generationGuard: SearchGenerationGuard = SearchGenerationGuard(),
) : ImageSearchRepository {
    override fun search(request: SearchRequest): Flow<SearchProgress> = flow {
        require(request.pageSize in 1..200) { "search pageSize must be between 1 and 200" }
        generationGuard.begin(request.generation)
        val query = SearchTextNormalizer.normalizeQuery(request.rawQuery).text
        if (query.isEmpty()) {
            emit(
                SearchProgress(
                    generation = request.generation,
                    items = emptyList(),
                    completedStages = SearchStage.entries.toSet(),
                    isRefining = false,
                    partialReasons = emptySet(),
                ),
            )
            return@flow
        }

        val candidates = mutableListOf<SearchResult>()
        val completed = linkedSetOf<SearchStage>()
        val partial = linkedSetOf<SearchPartialReason>()
        val overallDeadline = SearchDeadline(clock, TOTAL_BUDGET_MILLIS)

        val structuredDeadline = SearchDeadline(clock, STRUCTURED_BUDGET_MILLIS)
        val structuredCompleted = withTimeoutOrNull(STRUCTURED_BUDGET_MILLIS) {
            try {
                runStructuredAndFts(
                    request,
                    query,
                    candidates,
                    completed,
                    partial,
                    structuredDeadline,
                )
                true
            } catch (_: SearchBudgetExceeded) {
                false
            }
        } ?: false
        if (!structuredCompleted || overallDeadline.expired()) {
            partial += SearchPartialReason.STRUCTURED_TIMEOUT
        }
        generationGuard.checkpoint(request.generation)
        emit(progress(request, candidates, completed, partial, isRefining = true))

        if (!overallDeadline.expired()) {
            val substringDeadline = SearchDeadline(clock, SUBSTRING_BUDGET_MILLIS)
            val substringCompleted = withTimeoutOrNull(SUBSTRING_BUDGET_MILLIS) {
                try {
                    runSubstring(request, query, candidates, partial, substringDeadline)
                    true
                } catch (_: SearchBudgetExceeded) {
                    false
                }
            } ?: false
            if (substringCompleted) completed += SearchStage.SUBSTRING
            else partial += SearchPartialReason.SUBSTRING_TIMEOUT
        } else {
            partial += SearchPartialReason.SUBSTRING_TIMEOUT
        }
        generationGuard.checkpoint(request.generation)

        if (!overallDeadline.expired()) {
            val fuzzyDeadline = SearchDeadline(clock, FUZZY_BUDGET_MILLIS)
            val fuzzyCompleted = withTimeoutOrNull(FUZZY_BUDGET_MILLIS) {
                try {
                    runFuzzy(
                        request,
                        query,
                        candidates,
                        completed,
                        partial,
                        fuzzyDeadline,
                    )
                    true
                } catch (_: SearchBudgetExceeded) {
                    false
                }
            } ?: false
            if (!fuzzyCompleted) partial += SearchPartialReason.FUZZY_TIMEOUT
        } else {
            partial += SearchPartialReason.FUZZY_TIMEOUT
        }
        generationGuard.checkpoint(request.generation)
        emit(progress(request, candidates, completed, partial, isRefining = false))
    }

    private suspend fun runStructuredAndFts(
        request: SearchRequest,
        query: String,
        candidates: MutableList<SearchResult>,
        completed: MutableSet<SearchStage>,
        partial: MutableSet<SearchPartialReason>,
        deadline: SearchDeadline,
    ) {
        try {
            val exact = dao.findExactMappings(query, SearchLimits.IMAGE_CANDIDATES + 1)
            generationGuard.checkpoint(request.generation)
            deadline.check()
            candidates += cappedMappings(exact, partial).map { mapping ->
                mapping.toResult(
                    tier = if (mapping.ownership == SearchTermOwnership.USER) {
                        SearchTier.USER
                    } else {
                        SearchTier.EXACT_STRUCTURED
                    },
                    score = 1.0,
                    reason = "精确匹配 ${SearchField.fromMask(mapping.fieldMask).name}",
                )
            }
            completed += SearchStage.STRUCTURED
        } catch (_: SQLiteException) {
            partial += SearchPartialReason.INDEX_DEGRADED
        }

        val ftsQuery = FtsQueryBuilder.prefixQuery(query) ?: return
        try {
            val ids = dao.findFtsCandidateIds(ftsQuery, SearchLimits.IMAGE_CANDIDATES + 1)
            generationGuard.checkpoint(request.generation)
            deadline.check()
            val cappedIds = capImages(ids, partial)
            val hydrationLimit = max(DAO_PAGE_SIZE, request.pageSize * FTS_PREFETCH_MULTIPLIER)
                .coerceAtMost(SearchLimits.IMAGE_CANDIDATES)
            val hydratedIds = cappedIds.take(hydrationLimit)
            val documents = loadPaged(hydratedIds, request, deadline, dao::getDocuments)
                .associateBy(SearchDocumentEntity::imageLocalId)
            val images = availableImages(hydratedIds, request, deadline)
            deadline.check()
            candidates += hydratedIds.mapNotNullChecking(request, deadline) { imageId ->
                val document = documents[imageId]
                val image = images[imageId]
                if (document == null || image == null) {
                    null
                } else {
                    val field = bestDocumentField(document, query)
                    image.toResult(
                        tier = SearchTier.FTS4,
                        field = field,
                        weight = field.defaultWeight,
                        score = 1.0,
                        reason = "全文前缀匹配 ${field.name}",
                    )
                }
            }
            completed += SearchStage.FTS4
        } catch (_: SQLiteException) {
            partial += SearchPartialReason.REBUILD_REQUIRED
        }
    }

    private suspend fun runSubstring(
        request: SearchRequest,
        query: String,
        candidates: MutableList<SearchResult>,
        partial: MutableSet<SearchPartialReason>,
        deadline: SearchDeadline,
    ) {
        val grams = SearchGramGenerator.grams(query).toList()
        if (grams.isEmpty()) return
        try {
            val termOwners = cappedGramOwners(
                dao.findGramCandidates(
                    SearchGramOwnerType.TERM,
                    grams,
                    SearchLimits.GRAM_TERM_CANDIDATES + 1,
                ),
                SearchLimits.GRAM_TERM_CANDIDATES,
                partial,
            )
            generationGuard.checkpoint(request.generation)
            deadline.check()
            val matchingTerms = loadPaged(
                termOwners.map { it.ownerId },
                request,
                deadline,
                dao::getTerms,
            )
                .filterChecking(request, deadline) { it.normalizedKey.contains(query) }
            candidates += mappingsForTerms(
                request,
                matchingTerms.map { it.termId },
                partial,
            ).map { mapping ->
                mapping.toResult(
                    SearchTier.SUBSTRING,
                    containmentScore(query, mapping.normalizedKey),
                    "结构化字段包含匹配",
                )
            }

            val chunkOwners = cappedGramOwners(
                dao.findGramCandidates(
                    SearchGramOwnerType.SOURCE_CHUNK,
                    grams,
                    SearchLimits.IMAGE_CANDIDATES + 1,
                ),
                SearchLimits.IMAGE_CANDIDATES,
                partial,
            )
            generationGuard.checkpoint(request.generation)
            deadline.check()
            val matchingChunks = loadPaged(
                chunkOwners.map { it.ownerId },
                request,
                deadline,
                dao::getSourceChunks,
            )
                .filterChecking(request, deadline) { it.normalizedText.contains(query) }
            val images = availableImages(
                matchingChunks.map { it.imageLocalId },
                request,
                deadline,
            )
            deadline.check()
            candidates += matchingChunks.mapNotNullChecking(request, deadline) { chunk ->
                val image = images[chunk.imageLocalId]
                if (image == null) {
                    null
                } else {
                    val field = SearchField.valueOf(chunk.field)
                    image.toResult(
                        SearchTier.SUBSTRING,
                        field,
                        field.defaultWeight,
                        containmentScore(query, chunk.normalizedText),
                        "字段包含匹配 ${field.name}",
                    )
                }
            }
        } catch (_: SQLiteException) {
            partial += SearchPartialReason.INDEX_DEGRADED
        }
    }

    private suspend fun runFuzzy(
        request: SearchRequest,
        query: String,
        candidates: MutableList<SearchResult>,
        completed: MutableSet<SearchStage>,
        partial: MutableSet<SearchPartialReason>,
        deadline: SearchDeadline,
    ) {
        val codePoints = query.codePointCount(0, query.length)
        val grams = SearchGramGenerator.grams(query).toList()
        if (grams.isEmpty()) {
            completed += SearchStage.TYPO
            completed += SearchStage.PINYIN
            return
        }
        try {
            val maximum = DamerauLevenshtein.maximumDistanceFor(codePoints)
            if (maximum != null) {
                val termOwners = cappedGramOwners(
                    dao.findGramCandidates(
                        SearchGramOwnerType.TERM,
                        grams,
                        SearchLimits.GRAM_TERM_CANDIDATES + 1,
                    ),
                    SearchLimits.GRAM_TERM_CANDIDATES,
                    partial,
                )
                generationGuard.checkpoint(request.generation)
                deadline.check()
                val typoTerms = loadPaged(
                    termOwners.map { it.ownerId },
                    request,
                    deadline,
                    dao::getTerms,
                )
                    .mapNotNullChecking(request, deadline) { term ->
                        DamerauLevenshtein.withinDistance(query, term.normalizedKey, maximum)
                            ?.let { distance -> term to distance }
                    }
                    .sortedBy { it.second }
                if (typoTerms.size > SearchLimits.TYPO_TERMS) {
                    partial += SearchPartialReason.TYPO_TERM_CAP
                }
                val accepted = typoTerms.take(SearchLimits.TYPO_TERMS)
                val distanceByTerm = accepted.associate { (term, distance) -> term.termId to distance }
                candidates += mappingsForTerms(request, accepted.map { it.first.termId }, partial)
                    .map { mapping ->
                        val distance = distanceByTerm.getValue(mapping.termId)
                        mapping.toResult(
                            SearchTier.TYPO,
                            1.0 - distance.toDouble() / max(1, codePoints),
                            "近似拼写，编辑距离 $distance",
                        )
                    }
            }
            completed += SearchStage.TYPO

            val aliasOwners = cappedGramOwners(
                dao.findGramCandidates(
                    SearchGramOwnerType.TERM_ALIAS,
                    grams,
                    SearchLimits.GRAM_TERM_CANDIDATES + 1,
                ),
                SearchLimits.GRAM_TERM_CANDIDATES,
                partial,
            )
            generationGuard.checkpoint(request.generation)
            deadline.check()
            val matchingAliases = loadPaged(
                aliasOwners.map { it.ownerId },
                request,
                deadline,
                dao::getAliases,
            )
                .filterChecking(request, deadline) { it.aliasText.contains(query) }
            candidates += mappingsForTerms(
                request,
                matchingAliases.map { it.termId }.distinct(),
                partial,
            ).map { mapping ->
                mapping.toResult(SearchTier.PINYIN, 1.0, "拼音别名匹配")
            }

            val textAliasOwners = cappedGramOwners(
                dao.findGramCandidates(
                    SearchGramOwnerType.TEXT_ALIAS_CHUNK,
                    grams,
                    SearchLimits.IMAGE_CANDIDATES + 1,
                ),
                SearchLimits.IMAGE_CANDIDATES,
                partial,
            )
            generationGuard.checkpoint(request.generation)
            deadline.check()
            val matchingChunks = loadPaged(
                textAliasOwners.map { it.ownerId },
                request,
                deadline,
                dao::getTextAliasChunks,
            )
                .filterChecking(request, deadline) { it.aliasText.contains(query) }
            val images = availableImages(
                matchingChunks.map { it.imageLocalId },
                request,
                deadline,
            )
            deadline.check()
            candidates += matchingChunks.mapNotNullChecking(request, deadline) { chunk ->
                val image = images[chunk.imageLocalId]
                if (image == null) {
                    null
                } else {
                    val field = SearchField.valueOf(chunk.field)
                    image.toResult(
                        SearchTier.PINYIN,
                        field,
                        field.defaultWeight,
                        containmentScore(query, chunk.aliasText),
                        "拼音全文匹配 ${field.name}",
                    )
                }
            }
            completed += SearchStage.PINYIN
        } catch (_: SQLiteException) {
            partial += SearchPartialReason.INDEX_DEGRADED
        }
    }

    private suspend fun mappingsForTerms(
        request: SearchRequest,
        termIds: List<Long>,
        partial: MutableSet<SearchPartialReason>,
    ): List<SearchMappingCandidate> {
        if (termIds.isEmpty()) return emptyList()
        val mappings = dao.findMappingsForTerms(
            termIds.distinct(),
            SearchLimits.IMAGE_CANDIDATES + 1,
        )
        generationGuard.checkpoint(request.generation)
        return cappedMappings(mappings, partial)
    }

    private suspend fun availableImages(
        ids: List<Long>,
        request: SearchRequest,
        deadline: SearchDeadline,
    ): Map<Long, ImageEntity> {
        if (ids.isEmpty()) return emptyMap()
        return loadPaged(ids.distinct(), request, deadline, dao::getImages)
            .filter { it.availability == ImageAvailability.AVAILABLE }
            .associateBy(ImageEntity::localId)
    }

    private suspend fun <T> loadPaged(
        ids: List<Long>,
        request: SearchRequest,
        deadline: SearchDeadline,
        load: suspend (List<Long>) -> List<T>,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        return buildList {
            ids.distinct().chunked(DAO_PAGE_SIZE).forEach { page ->
                addAll(load(page))
                generationGuard.checkpoint(request.generation)
                deadline.check()
            }
        }
    }

    private fun cappedMappings(
        mappings: List<SearchMappingCandidate>,
        partial: MutableSet<SearchPartialReason>,
    ): List<SearchMappingCandidate> {
        if (mappings.size > SearchLimits.IMAGE_CANDIDATES) {
            partial += SearchPartialReason.IMAGE_CANDIDATE_CAP
        }
        return mappings.take(SearchLimits.IMAGE_CANDIDATES)
    }

    private fun capImages(
        ids: List<Long>,
        partial: MutableSet<SearchPartialReason>,
    ): List<Long> {
        if (ids.size > SearchLimits.IMAGE_CANDIDATES) {
            partial += SearchPartialReason.IMAGE_CANDIDATE_CAP
        }
        return ids.take(SearchLimits.IMAGE_CANDIDATES)
    }

    private fun cappedGramOwners(
        candidates: List<cn.soul2.imageai.data.db.dao.SearchGramCandidate>,
        limit: Int,
        partial: MutableSet<SearchPartialReason>,
    ): List<cn.soul2.imageai.data.db.dao.SearchGramCandidate> {
        if (candidates.size > limit) {
            partial += if (limit == SearchLimits.GRAM_TERM_CANDIDATES) {
                SearchPartialReason.GRAM_TERM_CAP
            } else {
                SearchPartialReason.IMAGE_CANDIDATE_CAP
            }
        }
        return candidates.take(limit)
    }

    private fun progress(
        request: SearchRequest,
        candidates: List<SearchResult>,
        completed: Set<SearchStage>,
        partial: Set<SearchPartialReason>,
        isRefining: Boolean,
    ) = SearchProgress(
        generation = request.generation,
        items = SearchRanker.rank(candidates).take(request.pageSize),
        completedStages = completed.toSet(),
        isRefining = isRefining,
        partialReasons = partial.toSet(),
    )

    private fun SearchMappingCandidate.toResult(
        tier: SearchTier,
        score: Double,
        reason: String,
    ) = SearchResult(
        imageLocalId = imageLocalId,
        tier = tier,
        field = SearchField.fromMask(fieldMask),
        fieldWeight = weight,
        matchScore = score,
        sortTimeEpochMillis = sortTimeEpochMillis,
        mediaStoreId = mediaStoreId,
        volumeName = volumeName,
        stableLocalId = imageLocalId,
        reason = reason,
    )

    private fun ImageEntity.toResult(
        tier: SearchTier,
        field: SearchField,
        weight: Double,
        score: Double,
        reason: String,
    ) = SearchResult(
        imageLocalId = localId,
        tier = tier,
        field = field,
        fieldWeight = weight,
        matchScore = score,
        sortTimeEpochMillis = sortTimeEpochMillis,
        mediaStoreId = mediaStoreId,
        volumeName = volumeName,
        stableLocalId = localId,
        reason = reason,
    )

    private fun bestDocumentField(document: SearchDocumentEntity, query: String): SearchField =
        listOf(
            SearchField.TAG to document.tags,
            SearchField.CATEGORY to document.categories,
            SearchField.FILE_NAME to document.fileName,
            SearchField.ALBUM to document.album,
            SearchField.SEARCH_TOKEN to document.searchTokens,
            SearchField.CAPTION to document.caption,
            SearchField.MEDIA_TEXT to document.mediaText,
        ).firstOrNull { (_, value) -> value.contains(query) }?.first ?: SearchField.CAPTION

    private fun containmentScore(query: String, value: String): Double =
        query.codePointCount(0, query.length).toDouble() /
            max(1, value.codePointCount(0, value.length))

    private suspend inline fun <T> Iterable<T>.filterChecking(
        request: SearchRequest,
        deadline: SearchDeadline,
        predicate: (T) -> Boolean,
    ): List<T> {
        val output = mutableListOf<T>()
        forEachIndexed { index, value ->
            if (predicate(value)) output += value
            if ((index + 1) % CANCELLATION_CHECK_CANDIDATES == 0) {
                generationGuard.checkpoint(request.generation)
                deadline.check()
            }
        }
        return output
    }

    private suspend inline fun <T, R : Any> Iterable<T>.mapNotNullChecking(
        request: SearchRequest,
        deadline: SearchDeadline,
        transform: (T) -> R?,
    ): List<R> {
        val output = mutableListOf<R>()
        forEachIndexed { index, value ->
            transform(value)?.let(output::add)
            if ((index + 1) % CANCELLATION_CHECK_CANDIDATES == 0) {
                generationGuard.checkpoint(request.generation)
                deadline.check()
            }
        }
        return output
    }

    private companion object {
        const val STRUCTURED_BUDGET_MILLIS = 1_000L
        const val SUBSTRING_BUDGET_MILLIS = 2_000L
        const val FUZZY_BUDGET_MILLIS = 2_000L
        const val TOTAL_BUDGET_MILLIS = 5_000L
        const val CANCELLATION_CHECK_CANDIDATES = 256
        const val DAO_PAGE_SIZE = 200
        const val FTS_PREFETCH_MULTIPLIER = 5
    }
}

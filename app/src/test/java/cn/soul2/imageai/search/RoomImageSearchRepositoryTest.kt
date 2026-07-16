package cn.soul2.imageai.search

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.analysis.EffectiveProjectionSnapshot
import cn.soul2.imageai.analysis.SearchProjectionPreparation
import cn.soul2.imageai.analysis.CanonicalAnalysisDraft
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.analysis.CanonicalTermInput
import cn.soul2.imageai.analysis.CorrectionCommand
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveImageMetadataEntity
import cn.soul2.imageai.data.db.entity.EffectiveImageTermEntity
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImageSearchTermEntity
import cn.soul2.imageai.data.db.entity.SearchTermEntity
import cn.soul2.imageai.data.db.entity.SearchTermOwnership
import cn.soul2.imageai.data.db.entity.SearchTermUnitType
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomImageSearchRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ImageSearchRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val fixtures = listOf(
            fixture(1, "重庆夜景", tag = "旅行", token = "山城"),
            fixture(2, "银行大楼", tag = "建筑"),
            fixture(3, "orange sunset", tag = "cat"),
            fixture(4, "mountain trail", tag = "travel"),
            fixture(5, "上海街道", tag = "favorite", userTag = true),
        )
        database.imageDao().upsert(fixtures.map(Fixture::image))
        val writer = RoomSearchProjectionWriter(database.searchIndexDao())
        fixtures.forEach { fixture ->
            database.withTransaction {
                val prepared = writer.prepareForImage(
                    fixture.image.localId,
                    fixture.snapshot,
                ) as SearchProjectionPreparation.Ready
                writer.replaceForImage(prepared)
            }
        }
        repository = RoomImageSearchRepository(database.searchIndexDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactUserAndAiTermsUseTheirFrozenTiers() = runBlocking {
        val user = search("favorite", 1)
        val ai = search("cat", 2)

        assertEquals(5L, user.items.first().imageLocalId)
        assertEquals(SearchTier.USER, user.items.first().tier)
        assertEquals(3L, ai.items.first().imageLocalId)
        assertEquals(SearchTier.EXACT_STRUCTURED, ai.items.first().tier)
    }

    @Test
    fun ftsPrefixLatinAndCjkSubstringAreProgressivelySearchable() = runBlocking {
        val prefix = search("oran", 3)
        val latinSubstring = search("ange", 4)
        val cjkSubstring = search("重庆夜", 5)

        assertEquals(3L, prefix.items.first().imageLocalId)
        assertEquals(SearchTier.FTS4, prefix.items.first().tier)
        assertEquals(3L, latinSubstring.items.first().imageLocalId)
        assertEquals(SearchTier.SUBSTRING, latinSubstring.items.first().tier)
        assertEquals(1L, cjkSubstring.items.first().imageLocalId)
        assertTrue(cjkSubstring.completedStages.contains(SearchStage.SUBSTRING))
    }

    @Test
    fun typoFullPinyinAndInitialsResolveToTheIndexedImage() = runBlocking {
        val typo = search("catt", 6)
        val full = search("chongqing", 7)
        val initials = search("cqyj", 8)

        assertEquals(3L, typo.items.first().imageLocalId)
        assertEquals(SearchTier.TYPO, typo.items.first().tier)
        assertEquals(1L, full.items.first().imageLocalId)
        assertEquals(SearchTier.PINYIN, full.items.first().tier)
        assertEquals(1L, initials.items.first().imageLocalId)
        assertEquals(SearchTier.PINYIN, initials.items.first().tier)
    }

    @Test
    fun longCaptionPinyinAndInitialsQueriesCrossChunkBoundariesWithoutLoss() = runBlocking {
        val caption = "重庆".repeat(400)
        val fixture = fixture(6, caption = caption, tag = "长文本")
        database.imageDao().upsert(listOf(fixture.image))
        val writer = RoomSearchProjectionWriter(database.searchIndexDao())
        database.withTransaction {
            writer.replaceForImage(
                writer.prepareForImage(6, fixture.snapshot) as SearchProjectionPreparation.Ready,
            )
        }
        val streams = PinyinTransliterator.completeStreams(caption)
        val fullBoundaryQuery = streams.full.substring(500, 628)
        val initialsBoundaryQuery = streams.initials.substring(500, 628)

        assertEquals(6L, search(fullBoundaryQuery, 15).items.first().imageLocalId)
        assertEquals(6L, search(initialsBoundaryQuery, 16).items.first().imageLocalId)
    }

    @Test
    fun tombstonedTagAndSameNamedTokenDisappearUntilExplicitRestore() = runBlocking {
        val fixture = fixture(6, caption = "宠物照片", tag = "seed")
        database.imageDao().upsert(listOf(fixture.image))
        val canonical = CanonicalMetadataRepository(
            database,
            RoomSearchProjectionWriter(database.searchIndexDao()),
        )
        canonical.activateAnalysis(canonicalDraft(tag = "kitten", token = "kitten"))
        assertTrue(search("kitten", 17).items.any { it.imageLocalId == 6L })

        canonical.applyCorrection(
            6,
            CorrectionCommand.DeleteTerm(AnalysisTermKind.TAG, "kitten"),
            30,
        )
        assertFalse(search("kitten", 18).items.any { it.imageLocalId == 6L })

        canonical.applyCorrection(
            6,
            CorrectionCommand.RestoreTerm(AnalysisTermKind.TAG, "kitten"),
            40,
        )
        assertTrue(search("kitten", 19).items.any { it.imageLocalId == 6L })
    }

    @Test
    fun blankQueryAndShortSubstringRulesDoNotScanCaptionGrams() = runBlocking {
        val blank = search("   ", 9)
        val shortLatin = search("ra", 10)

        assertTrue(blank.items.isEmpty())
        assertFalse(blank.isRefining)
        assertTrue(shortLatin.items.none { it.tier == SearchTier.SUBSTRING })
    }

    @Test
    fun gramCandidateCapIsReportedWithoutAnUnboundedFallbackScan() = runBlocking {
        val image = fixture(6, caption = "", tag = "seed").image
        database.imageDao().upsert(listOf(image))
        val terms = List(SearchLimits.GRAM_TERM_CANDIDATES + 1) { index ->
            EffectiveImageTermEntity(
                imageLocalId = image.localId,
                kind = AnalysisTermKind.TAG,
                normalizedKey = "tag$index",
                displayValue = "tag$index",
                source = EffectiveTermSource.AI,
                confidence = null,
                sourceAnalysisId = null,
            )
        }
        val writer = RoomSearchProjectionWriter(database.searchIndexDao())
        database.withTransaction {
            val prepared = writer.prepareForImage(
                image.localId,
                EffectiveProjectionSnapshot(
                    EffectiveImageMetadataEntity(
                        image.localId,
                        null,
                        EffectiveCaptionSource.NONE,
                        1,
                        1,
                    ),
                    terms,
                ),
            ) as SearchProjectionPreparation.Ready
            writer.replaceForImage(prepared)
        }

        val result = search("tag", 11)

        assertTrue(result.partialReasons.contains(SearchPartialReason.GRAM_TERM_CAP))
        assertTrue(result.items.any { it.imageLocalId == image.localId })
    }

    @Test
    fun typoTermCapIsReportedAfterAcceptingOnly64Corrections() = runBlocking {
        val image = fixture(6, caption = "", tag = "seed").image
        database.imageDao().upsert(listOf(image))
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val terms = List(SearchLimits.TYPO_TERMS + 1) { index ->
            val suffix = "${alphabet[index / alphabet.length]}${alphabet[index % alphabet.length]}"
            EffectiveImageTermEntity(
                imageLocalId = image.localId,
                kind = AnalysisTermKind.TAG,
                normalizedKey = "abcdef$suffix",
                displayValue = "abcdef$suffix",
                source = EffectiveTermSource.AI,
                confidence = null,
                sourceAnalysisId = null,
            )
        }
        val writer = RoomSearchProjectionWriter(database.searchIndexDao())
        database.withTransaction {
            writer.replaceForImage(
                writer.prepareForImage(
                    image.localId,
                    EffectiveProjectionSnapshot(
                        EffectiveImageMetadataEntity(
                            image.localId,
                            null,
                            EffectiveCaptionSource.NONE,
                            1,
                            1,
                        ),
                        terms,
                    ),
                ) as SearchProjectionPreparation.Ready,
            )
        }

        val result = search("abcdef", 20)

        assertTrue(result.partialReasons.contains(SearchPartialReason.TYPO_TERM_CAP))
    }

    @Test
    fun imageCandidateCapStopsAt5000AndReportsPartialResults() = runBlocking {
        val images = List(SearchLimits.IMAGE_CANDIDATES + 1) { index ->
            fixture(
                id = 1_000L + index,
                caption = "",
                tag = "unused",
            ).image
        }
        database.imageDao().upsert(images)
        val dao = database.searchIndexDao()
        val termId = dao.insertTerm(
            SearchTermEntity(
                normalizedKey = "common",
                displayValue = "common",
                unitType = SearchTermUnitType.LATIN_DIGIT,
            ),
        )
        dao.upsertImageTerms(
            images.map { image ->
                ImageSearchTermEntity(
                    imageLocalId = image.localId,
                    termId = termId,
                    fieldMask = SearchField.TAG.mask,
                    ownership = SearchTermOwnership.AI,
                    weight = SearchField.TAG.defaultWeight,
                )
            },
        )

        val result = search("common", 21)

        assertEquals(40, result.items.size)
        assertTrue(result.partialReasons.contains(SearchPartialReason.IMAGE_CANDIDATE_CAP))
    }

    @Test
    fun fuzzyIndexFailureRetainsExactAndFtsResultsWithDegradedReason() = runBlocking {
        database.openHelper.writableDatabase.execSQL("DROP TABLE search_gram")

        val result = search("cat", 12)

        assertEquals(3L, result.items.first().imageLocalId)
        assertEquals(SearchTier.EXACT_STRUCTURED, result.items.first().tier)
        assertTrue(result.partialReasons.contains(SearchPartialReason.INDEX_DEGRADED))
    }

    @Test
    fun ftsFailureRetainsExactResultsAndRequestsRebuild() = runBlocking {
        database.openHelper.writableDatabase.execSQL("DROP TABLE search_document_fts")

        val result = search("cat", 13)

        assertEquals(3L, result.items.first().imageLocalId)
        assertEquals(SearchTier.EXACT_STRUCTURED, result.items.first().tier)
        assertTrue(result.partialReasons.contains(SearchPartialReason.REBUILD_REQUIRED))
    }

    @Test
    fun injectedMonotonicClockReportsStructuredTimeout() = runBlocking {
        var now = 0L
        val advancingClock = SearchMonotonicClock {
            now.also { now += 1_500L }
        }
        val timedRepository = RoomImageSearchRepository(
            database.searchIndexDao(),
            clock = advancingClock,
        )

        val result = timedRepository.search(SearchRequest("cat", 14)).last()

        assertTrue(result.partialReasons.contains(SearchPartialReason.STRUCTURED_TIMEOUT))
    }

    @Test
    fun injectedMonotonicClockReportsSubstringTimeoutWithoutFallingBackToScan() = runBlocking {
        val timedRepository = RoomImageSearchRepository(
            database.searchIndexDao(),
            clock = ThresholdClock(expireAtCall = 11, expiredMillis = 2_500),
        )

        val result = timedRepository.search(SearchRequest("cat", 22)).last()

        assertTrue(result.partialReasons.contains(SearchPartialReason.SUBSTRING_TIMEOUT))
        assertFalse(result.partialReasons.contains(SearchPartialReason.STRUCTURED_TIMEOUT))
        assertEquals(3L, result.items.first().imageLocalId)
    }

    @Test
    fun injectedMonotonicClockReportsFuzzyTimeoutAndKeepsHigherTiers() = runBlocking {
        val timedRepository = RoomImageSearchRepository(
            database.searchIndexDao(),
            clock = ThresholdClock(expireAtCall = 19, expiredMillis = 2_500),
        )

        val result = timedRepository.search(SearchRequest("cat", 23)).last()

        assertTrue(result.partialReasons.contains(SearchPartialReason.FUZZY_TIMEOUT))
        assertFalse(result.partialReasons.contains(SearchPartialReason.STRUCTURED_TIMEOUT))
        assertFalse(result.partialReasons.contains(SearchPartialReason.SUBSTRING_TIMEOUT))
        assertEquals(3L, result.items.first().imageLocalId)
    }

    @Test
    fun newerGenerationCancelsAnOlderGenerationAtTheNextCheckpoint() {
        val guard = SearchGenerationGuard()
        guard.begin(1)
        guard.begin(2)

        assertThrows(CancellationException::class.java) {
            runBlocking { guard.checkpoint(1) }
        }

        guard.begin(0)
        runBlocking { guard.checkpoint(0) }
        assertThrows(CancellationException::class.java) {
            runBlocking { guard.checkpoint(2) }
        }
    }

    private suspend fun search(query: String, generation: Long): SearchProgress =
        repository.search(SearchRequest(query, generation)).last()

    private fun fixture(
        id: Long,
        caption: String,
        tag: String,
        token: String? = null,
        userTag: Boolean = false,
    ): Fixture {
        val terms = buildList {
            add(
                EffectiveImageTermEntity(
                    id,
                    AnalysisTermKind.TAG,
                    tag,
                    tag,
                    if (userTag) EffectiveTermSource.USER else EffectiveTermSource.AI,
                    null,
                    null,
                ),
            )
            token?.let {
                add(
                    EffectiveImageTermEntity(
                        id,
                        AnalysisTermKind.SEARCH_TOKEN,
                        it,
                        it,
                        EffectiveTermSource.AI,
                        null,
                        null,
                    ),
                )
            }
        }
        return Fixture(
            image = ImageEntity(
                localId = id,
                volumeName = "external",
                mediaStoreId = 100 + id,
                contentUri = "content://media/$id",
                displayName = "photo-$id.jpg",
                mimeType = "image/jpeg",
                width = 100,
                height = 100,
                sizeBytes = 1_000,
                capturedAtEpochMillis = null,
                addedAtEpochMillis = id,
                modifiedAtEpochMillis = id,
                sortTimeEpochMillis = id,
                bucketId = null,
                bucketName = "测试图集",
                isFavorite = false,
                quickFingerprint = "1000:$id",
                availability = ImageAvailability.AVAILABLE,
                lastSeenSyncRunId = null,
                missingCandidateSinceEpochMillis = null,
                missingObservationCount = 0,
            ),
            snapshot = EffectiveProjectionSnapshot(
                EffectiveImageMetadataEntity(
                    imageLocalId = id,
                    caption = caption,
                    captionSource = EffectiveCaptionSource.AI,
                    projectionGeneration = 1,
                    updatedAtEpochMillis = 1,
                ),
                terms,
            ),
        )
    }

    private data class Fixture(
        val image: ImageEntity,
        val snapshot: EffectiveProjectionSnapshot,
    )

    private fun canonicalDraft(tag: String, token: String) = CanonicalAnalysisDraft(
        analysisId = "123e4567-e89b-12d3-a456-426614174006",
        imageLocalId = 6,
        schemaVersion = 1,
        caption = "宠物照片",
        tags = listOf(CanonicalTermInput(tag, 0.9)),
        categories = emptyList(),
        searchTokens = listOf(token),
        extensionJson = null,
        providerProfileId = "provider",
        modelProfileId = "model",
        protocolDefinitionId = "protocol",
        promptTemplateId = "prompt",
        createdAtEpochMillis = 10,
        completedAtEpochMillis = 20,
    )

    private class ThresholdClock(
        private val expireAtCall: Int,
        private val expiredMillis: Long,
    ) : SearchMonotonicClock {
        private var calls = 0

        override fun nowMillis(): Long {
            calls++
            return if (calls >= expireAtCall) expiredMillis else 0L
        }
    }
}

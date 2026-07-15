package cn.soul2.imageai.analysis

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.CaptionCorrectionMode
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction
import cn.soul2.imageai.data.db.entity.UserTermOverrideEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CanonicalMetadataRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CanonicalMetadataRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.imageDao().upsert(listOf(image()))
        repository = CanonicalMetadataRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rerunKeepsHistoryAndReplacesOnlyTheActiveProjection() = runTest {
        val first = repository.activateAnalysis(
            draft(
                analysisId = ANALYSIS_ONE,
                caption = "old caption",
                tags = listOf("old"),
                tokens = listOf("old-token"),
            ),
        )
        val second = repository.activateAnalysis(
            draft(
                analysisId = ANALYSIS_TWO,
                caption = "new caption",
                tags = listOf("new"),
                tokens = listOf("new-token"),
            ),
        )
        val snapshot = requireNotNull(repository.getEffectiveSnapshot(IMAGE_ID))

        assertEquals(ActivationResult.Activated(1L), first)
        assertEquals(ActivationResult.Activated(2L), second)
        assertEquals(ANALYSIS_TWO, snapshot.activeAnalysisId)
        assertEquals("new caption", snapshot.metadata.caption)
        assertEquals(
            setOf("new", "new-token"),
            snapshot.terms.map { it.normalizedKey }.toSet(),
        )
        assertEquals(2, database.analysisDao().countAnalyses(IMAGE_ID))

        assertEquals(
            ActivationResult.AlreadyActive(2L),
            repository.activateAnalysis(
                draft(
                    analysisId = ANALYSIS_TWO,
                    caption = "new caption",
                    tags = listOf("new"),
                    tokens = listOf("new-token"),
                ),
            ),
        )
        assertEquals(2L, repository.getEffectiveSnapshot(IMAGE_ID)?.metadata?.projectionGeneration)
    }

    @Test
    fun effectiveLimitLeavesProjectionUntouchedAndBoundsBlockedDiagnosticHistory() = runTest {
        repository.activateAnalysis(draft(ANALYSIS_ONE, "baseline", listOf("baseline")))
        database.effectiveMetadataDao().upsertOverrides(
            List(129) { index ->
                UserTermOverrideEntity(
                    imageLocalId = IMAGE_ID,
                    kind = AnalysisTermKind.TAG,
                    normalizedKey = "user-$index",
                    action = UserTermOverrideAction.ADD,
                    displayValue = "user-$index",
                    revision = 1L,
                    updatedAtEpochMillis = 50L,
                )
            },
        )

        val result = repository.activateAnalysis(
            draft(
                analysisId = ANALYSIS_TWO,
                caption = "blocked",
                tags = List(128) { "ai-$it" },
            ),
        )
        val snapshot = requireNotNull(repository.getEffectiveSnapshot(IMAGE_ID))

        assertEquals(ActivationResult.Blocked("EFFECTIVE_TAG_LIMIT"), result)
        assertEquals(ANALYSIS_ONE, snapshot.activeAnalysisId)
        assertEquals("baseline", snapshot.metadata.caption)
        assertEquals(1L, snapshot.metadata.projectionGeneration)
        assertEquals(
            "EFFECTIVE_TAG_LIMIT",
            database.analysisDao().getDiagnostic(ANALYSIS_TWO)?.code,
        )

        val laterBlockedIds = listOf(
            "123e4567-e89b-12d3-a456-426614174002",
            "123e4567-e89b-12d3-a456-426614174003",
        )
        laterBlockedIds.forEach { analysisId ->
            assertEquals(
                ActivationResult.Blocked("EFFECTIVE_TAG_LIMIT"),
                repository.activateAnalysis(
                    draft(analysisId, "blocked", List(128) { "ai-$it" }),
                ),
            )
        }

        assertEquals(3, database.analysisDao().countAnalyses(IMAGE_ID))
        assertNull(database.analysisDao().getAnalysis(ANALYSIS_TWO))
        laterBlockedIds.forEach { analysisId ->
            assertEquals(
                "EFFECTIVE_TAG_LIMIT",
                database.analysisDao().getDiagnostic(analysisId)?.code,
            )
        }
    }

    @Test
    fun writerFailureRollsBackInsertedAnalysisPointerAndProjection() = runTest {
        repository.activateAnalysis(draft(ANALYSIS_ONE, "baseline", listOf("baseline")))
        val failingRepository = CanonicalMetadataRepository(
            database,
            SearchProjectionWriter { _, _ -> error("index failed") },
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                failingRepository.activateAnalysis(
                    draft(ANALYSIS_TWO, "new", listOf("new")),
                )
            }
        }

        val snapshot = requireNotNull(repository.getEffectiveSnapshot(IMAGE_ID))
        assertEquals(ANALYSIS_ONE, snapshot.activeAnalysisId)
        assertEquals("baseline", snapshot.metadata.caption)
        assertNull(database.analysisDao().getAnalysis(ANALYSIS_TWO))
    }

    @Test
    fun reusingAnAnalysisIdWithDifferentContentIsRejected() = runTest {
        repository.activateAnalysis(draft(ANALYSIS_ONE, "first", listOf("one")))

        assertThrows(CanonicalValidationException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.activateAnalysis(draft(ANALYSIS_ONE, "changed", listOf("two")))
            }
        }
    }

    @Test
    fun tombstoneSurvivesRerunSuppressesMatchingTokenAndRestoresExplicitly() = runTest {
        repository.activateAnalysis(
            draft(
                ANALYSIS_ONE,
                "first",
                tags = listOf("cat"),
                tokens = listOf("cat", "pet"),
            ),
        )

        repository.applyCorrection(
            IMAGE_ID,
            CorrectionCommand.DeleteTerm(AnalysisTermKind.TAG, "ＣＡＴ"),
            nowEpochMillis = 30L,
        )
        assertEquals(
            setOf("pet"),
            repository.getEffectiveSnapshot(IMAGE_ID)?.terms?.map { it.normalizedKey }?.toSet(),
        )

        repository.activateAnalysis(
            draft(
                ANALYSIS_TWO,
                "second",
                tags = listOf("cat", "city"),
                tokens = listOf("cat", "pet"),
            ),
        )
        assertEquals(
            setOf("city", "pet"),
            repository.getEffectiveSnapshot(IMAGE_ID)?.terms?.map { it.normalizedKey }?.toSet(),
        )

        repository.applyCorrection(
            IMAGE_ID,
            CorrectionCommand.RestoreTerm(AnalysisTermKind.TAG, "cat"),
            nowEpochMillis = 40L,
        )
        assertEquals(
            setOf("cat", "city", "pet"),
            repository.getEffectiveSnapshot(IMAGE_ID)?.terms?.map { it.normalizedKey }?.toSet(),
        )
    }

    @Test
    fun captionSetAndClearSurviveRerunUntilReturningToInherit() = runTest {
        repository.activateAnalysis(draft(ANALYSIS_ONE, "AI one", emptyList()))
        repository.applyCorrection(IMAGE_ID, CorrectionCommand.SetCaption("用户描述"), 30L)
        repository.activateAnalysis(draft(ANALYSIS_TWO, "AI two", emptyList()))
        assertEquals("用户描述", repository.getEffectiveSnapshot(IMAGE_ID)?.metadata?.caption)

        repository.applyCorrection(IMAGE_ID, CorrectionCommand.ClearCaption, 40L)
        assertNull(repository.getEffectiveSnapshot(IMAGE_ID)?.metadata?.caption)

        repository.applyCorrection(IMAGE_ID, CorrectionCommand.InheritCaption, 50L)
        assertEquals("AI two", repository.getEffectiveSnapshot(IMAGE_ID)?.metadata?.caption)
    }

    @Test
    fun userOnlyProjectionWorksWithoutAnActiveAnalysis() = runTest {
        val result = repository.applyCorrection(
            IMAGE_ID,
            CorrectionCommand.AddTerm(AnalysisTermKind.TAG, "本地收藏"),
            nowEpochMillis = 30L,
        )
        val snapshot = requireNotNull(repository.getEffectiveSnapshot(IMAGE_ID))

        assertEquals(CorrectionResult.Applied(1L), result)
        assertNull(snapshot.activeAnalysisId)
        assertEquals("本地收藏", snapshot.terms.single().displayValue)
    }

    @Test
    fun emptyProjectionKeepsMonotonicGenerationAcrossLaterCorrections() = runTest {
        assertEquals(
            CorrectionResult.Applied(1L),
            repository.applyCorrection(
                IMAGE_ID,
                CorrectionCommand.AddTerm(AnalysisTermKind.TAG, "本地收藏"),
                nowEpochMillis = 30L,
            ),
        )
        assertEquals(
            CorrectionResult.Applied(2L),
            repository.applyCorrection(
                IMAGE_ID,
                CorrectionCommand.DeleteTerm(AnalysisTermKind.TAG, "本地收藏"),
                nowEpochMillis = 40L,
            ),
        )
        val emptySnapshot = requireNotNull(repository.getEffectiveSnapshot(IMAGE_ID))
        assertEquals(2L, emptySnapshot.metadata.projectionGeneration)
        assertTrue(emptySnapshot.terms.isEmpty())

        assertEquals(
            CorrectionResult.Applied(3L),
            repository.applyCorrection(
                IMAGE_ID,
                CorrectionCommand.AddTerm(AnalysisTermKind.TAG, "再次添加"),
                nowEpochMillis = 50L,
            ),
        )
    }

    @Test
    fun correctionWriterFailureRollsBackCorrectionAndProjection() = runTest {
        repository.activateAnalysis(draft(ANALYSIS_ONE, "AI", emptyList()))
        val failingRepository = CanonicalMetadataRepository(
            database,
            SearchProjectionWriter { _, _ -> error("index failed") },
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                failingRepository.applyCorrection(
                    IMAGE_ID,
                    CorrectionCommand.SetCaption("用户描述"),
                    30L,
                )
            }
        }

        assertEquals("AI", repository.getEffectiveSnapshot(IMAGE_ID)?.metadata?.caption)
        assertNull(database.effectiveMetadataDao().getCorrection(IMAGE_ID))
    }

    @Test
    fun concurrentCorrectionsSerializeWithoutLosingTermsOrGeneration() = runTest {
        repository.activateAnalysis(draft(ANALYSIS_ONE, "AI", emptyList()))

        val results = coroutineScope {
            listOf(
                async {
                    repository.applyCorrection(
                        IMAGE_ID,
                        CorrectionCommand.AddTerm(AnalysisTermKind.TAG, "alpha"),
                        30L,
                    )
                },
                async {
                    repository.applyCorrection(
                        IMAGE_ID,
                        CorrectionCommand.AddTerm(AnalysisTermKind.TAG, "beta"),
                        31L,
                    )
                },
            ).awaitAll()
        }
        val snapshot = requireNotNull(repository.getEffectiveSnapshot(IMAGE_ID))

        assertEquals(
            setOf(CorrectionResult.Applied(2L), CorrectionResult.Applied(3L)),
            results.toSet(),
        )
        assertEquals(3L, snapshot.metadata.projectionGeneration)
        assertEquals(setOf("alpha", "beta"), snapshot.terms.map { it.normalizedKey }.toSet())
    }

    @Test
    fun concurrentActivationAndCorrectionCommitAsWholeOrderedGenerations() = runTest {
        repository.activateAnalysis(
            draft(ANALYSIS_ONE, "AI one", tags = listOf("old"), tokens = listOf("old-token")),
        )

        val results = coroutineScope {
            listOf(
                async {
                    repository.activateAnalysis(
                        draft(
                            ANALYSIS_TWO,
                            "AI two",
                            tags = listOf("new"),
                            tokens = listOf("new-token"),
                        ),
                    )
                },
                async {
                    repository.applyCorrection(
                        IMAGE_ID,
                        CorrectionCommand.SetCaption("用户描述"),
                        30L,
                    )
                },
            ).awaitAll()
        }
        val snapshot = requireNotNull(repository.getEffectiveSnapshot(IMAGE_ID))

        assertEquals(
            setOf(2L, 3L),
            results.map { result ->
                when (result) {
                    is ActivationResult.Activated -> result.projectionGeneration
                    is CorrectionResult.Applied -> result.projectionGeneration
                    else -> error("Unexpected concurrent result: $result")
                }
            }.toSet(),
        )
        assertEquals(ANALYSIS_TWO, snapshot.activeAnalysisId)
        assertEquals("用户描述", snapshot.metadata.caption)
        assertEquals(3L, snapshot.metadata.projectionGeneration)
        assertEquals(
            setOf("new", "new-token"),
            snapshot.terms.map { it.normalizedKey }.toSet(),
        )
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observableDetailPublishesProvenanceCorrectionsTermsAndHistoryTogether() = runTest {
        repository.activateAnalysis(
            draft(ANALYSIS_ONE, "AI one", tags = listOf("old"), tokens = listOf("old-token")),
        )
        val updated = backgroundScope.async {
            repository.observeEffectiveMetadata(IMAGE_ID).first { detail ->
                detail?.projectionGeneration == 3L
            }
        }
        runCurrent()

        repository.applyCorrection(IMAGE_ID, CorrectionCommand.SetCaption("用户描述"), 30L)
        repository.activateAnalysis(
            draft(ANALYSIS_TWO, "AI two", tags = listOf("new"), tokens = listOf("new-token")),
        )
        val detail = requireNotNull(updated.await())

        assertEquals(ANALYSIS_TWO, detail.activeAnalysis?.analysisId)
        assertEquals("model", detail.activeAnalysis?.modelProfileId)
        assertEquals("用户描述", detail.caption)
        assertEquals(CaptionCorrectionMode.SET, detail.captionCorrection?.mode)
        assertEquals(
            setOf("new", "new-token"),
            detail.terms.map { term -> term.normalizedKey }.toSet(),
        )
        assertEquals(2, detail.history.size)
        assertTrue(detail.history.single { it.analysisId == ANALYSIS_TWO }.isActive)
        assertFalse(detail.history.single { it.analysisId == ANALYSIS_ONE }.isActive)
    }

    @Test
    fun activationKeepsOnlyTwoUnprotectedHistoricalAnalyses() = runTest {
        val ids = listOf(
            ANALYSIS_ONE,
            ANALYSIS_TWO,
            "123e4567-e89b-12d3-a456-426614174002",
            "123e4567-e89b-12d3-a456-426614174003",
        )
        ids.forEachIndexed { index, id ->
            repository.activateAnalysis(
                draft(id, "caption-$index", listOf("tag-$index")),
            )
        }

        assertEquals(3, database.analysisDao().countAnalyses(IMAGE_ID))
        assertEquals(ids.last(), repository.getEffectiveSnapshot(IMAGE_ID)?.activeAnalysisId)
    }

    private fun draft(
        analysisId: String,
        caption: String,
        tags: List<String>,
        tokens: List<String> = emptyList(),
    ) = CanonicalAnalysisDraft(
        analysisId = analysisId,
        imageLocalId = IMAGE_ID,
        schemaVersion = 1,
        caption = caption,
        tags = tags.map { CanonicalTermInput(it, 0.8) },
        categories = emptyList(),
        searchTokens = tokens,
        extensionJson = null,
        providerProfileId = "provider",
        modelProfileId = "model",
        protocolDefinitionId = "protocol",
        promptTemplateId = "prompt",
        createdAtEpochMillis = 10L,
        completedAtEpochMillis = 20L,
    )

    private fun image() = ImageEntity(
        localId = IMAGE_ID,
        volumeName = "external",
        mediaStoreId = 42L,
        contentUri = "content://media/external/images/media/42",
        displayName = "42.jpg",
        mimeType = "image/jpeg",
        width = 1_920,
        height = 1_080,
        sizeBytes = 1_024L,
        capturedAtEpochMillis = 100L,
        addedAtEpochMillis = 100L,
        modifiedAtEpochMillis = 100L,
        sortTimeEpochMillis = 100L,
        bucketId = 1L,
        bucketName = "相机",
        isFavorite = false,
        quickFingerprint = "fingerprint",
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )

    private companion object {
        const val IMAGE_ID = 42L
        const val ANALYSIS_ONE = "123e4567-e89b-12d3-a456-426614174000"
        const val ANALYSIS_TWO = "123e4567-e89b-12d3-a456-426614174001"
    }
}

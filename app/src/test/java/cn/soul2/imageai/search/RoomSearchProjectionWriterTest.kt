package cn.soul2.imageai.search

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import cn.soul2.imageai.analysis.CanonicalAnalysisDraft
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.analysis.CanonicalTermInput
import cn.soul2.imageai.analysis.CorrectionCommand
import cn.soul2.imageai.analysis.EffectiveProjectionSnapshot
import cn.soul2.imageai.analysis.SearchProjectionPreparation
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveImageMetadataEntity
import cn.soul2.imageai.data.db.entity.EffectiveImageTermEntity
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomSearchProjectionWriterTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.imageDao().upsert(listOf(image()))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun prepareBlocks769BeforeAnyDaoMutationAndAcceptsOnlyOwnToken() = runBlocking {
        val writer = RoomSearchProjectionWriter(database.searchIndexDao())
        val base = writer.prepareForImage(IMAGE_ID, snapshot()) as SearchProjectionPreparation.Ready
        assertEquals(0, count("search_document"))

        val emptySnapshot = snapshot(caption = null)
        val baseRelationshipCount = SearchProjectionPlanner().plan(image(), emptySnapshot).relationshipCount
        fun termsFor(totalRelationships: Int) =
            List(totalRelationships - baseRelationshipCount) { index ->
                EffectiveImageTermEntity(
                    imageLocalId = IMAGE_ID,
                    kind = AnalysisTermKind.TAG,
                    normalizedKey = "tag-$index",
                    displayValue = "Tag $index",
                    source = EffectiveTermSource.AI,
                    confidence = null,
                    sourceAnalysisId = null,
                )
            }
        val atLimit = snapshot(
            caption = null,
            terms = termsFor(SearchLimits.RELATIONSHIPS_PER_IMAGE),
        )
        val oversized = snapshot(
            caption = null,
            terms = termsFor(SearchLimits.RELATIONSHIPS_PER_IMAGE + 1),
        )
        assertTrue(writer.prepareForImage(IMAGE_ID, atLimit) is SearchProjectionPreparation.Ready)
        val blocked = writer.prepareForImage(IMAGE_ID, oversized)
        assertTrue(blocked is SearchProjectionPreparation.Blocked)
        assertEquals("SEARCH_INDEX_LIMIT", (blocked as SearchProjectionPreparation.Blocked).code)
        assertEquals(0, count("search_document"))

        val foreignWriter = RoomSearchProjectionWriter(database.searchIndexDao())
        assertThrows(IllegalArgumentException::class.java) {
            foreignWriter.replaceForImage(base)
        }
        Unit
    }

    @Test
    fun replacementDeletesOldImageOwnedRowsAndKeepsFtsAndFuzzyTablesCurrent() = runBlocking {
        val writer = RoomSearchProjectionWriter(database.searchIndexDao())
        database.withTransaction {
            writer.replaceForImage(writer.prepareForImage(IMAGE_ID, snapshot("重庆 old")) as SearchProjectionPreparation.Ready)
        }
        val oldCounts = listOf("search_source_chunk", "search_text_alias_chunk", "search_gram")
            .associateWith(::count)

        database.withTransaction {
            writer.replaceForImage(writer.prepareForImage(IMAGE_ID, snapshot("上海 new")) as SearchProjectionPreparation.Ready)
        }

        assertEquals(1, count("search_document"))
        assertEquals(1, rawCount("SELECT COUNT(*) FROM search_document_fts WHERE search_document_fts MATCH 'new'"))
        assertEquals(0, rawCount("SELECT COUNT(*) FROM search_document_fts WHERE search_document_fts MATCH 'old'"))
        assertTrue(count("search_source_chunk") <= oldCounts.getValue("search_source_chunk"))
        assertTrue(count("search_text_alias_chunk") <= oldCounts.getValue("search_text_alias_chunk"))
        assertTrue(count("search_gram") > 0)
        assertEquals(0, database.searchIndexDao().deleteOrphanGrams())
    }

    @Test
    fun injectedWriterFailureRollsBackDocumentFtsAndEveryFuzzyTable() = runBlocking {
        val stable = RoomSearchProjectionWriter(database.searchIndexDao())
        database.withTransaction {
            stable.replaceForImage(stable.prepareForImage(IMAGE_ID, snapshot("stable")) as SearchProjectionPreparation.Ready)
        }
        val before = tableCounts()
        val failing = RoomSearchProjectionWriter(
            dao = database.searchIndexDao(),
            afterDocumentWrite = { error("injected index failure") },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.withTransaction {
                    failing.replaceForImage(
                        failing.prepareForImage(IMAGE_ID, snapshot("broken")) as SearchProjectionPreparation.Ready,
                    )
                }
            }
        }

        assertEquals(before, tableCounts())
        assertEquals(1, rawCount("SELECT COUNT(*) FROM search_document_fts WHERE search_document_fts MATCH 'stable'"))
        assertEquals(0, rawCount("SELECT COUNT(*) FROM search_document_fts WHERE search_document_fts MATCH 'broken'"))
    }

    @Test
    fun canonicalRerunAndCorrectionReplaceFtsAndActiveTokensInTheSameGeneration() = runBlocking {
        val writer = RoomSearchProjectionWriter(database.searchIndexDao())
        val repository = CanonicalMetadataRepository(database, writer)

        repository.activateAnalysis(draft(ANALYSIS_ONE, "old caption", "oldtag", "oldtoken"))
        repository.activateAnalysis(draft(ANALYSIS_TWO, "new caption", "newtag", "newtoken"))

        assertEquals(0, ftsCount("oldtoken"))
        assertEquals(1, ftsCount("newtoken"))
        assertEquals(0, ftsCount("oldtag"))
        assertEquals(1, ftsCount("newtag"))
        assertEquals(2L, repository.getEffectiveSnapshot(IMAGE_ID)?.metadata?.projectionGeneration)

        repository.applyCorrection(
            IMAGE_ID,
            CorrectionCommand.SetCaption("用户描述"),
            nowEpochMillis = 30L,
        )

        assertEquals(0, ftsCount("new caption"))
        assertEquals(1, ftsCount("用户描述"))
        assertEquals(1, ftsCount("newtoken"))
        assertEquals(3L, repository.getEffectiveSnapshot(IMAGE_ID)?.metadata?.projectionGeneration)
    }

    private fun tableCounts() = listOf(
        "search_document",
        "search_term",
        "image_search_term",
        "search_term_alias",
        "search_source_chunk",
        "search_text_alias_chunk",
        "search_gram",
    ).associateWith(::count)

    private fun count(table: String): Int = rawCount("SELECT COUNT(*) FROM `$table`")

    private fun rawCount(sql: String): Int = database.openHelper.writableDatabase.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun ftsCount(query: String): Int = rawCount(
        "SELECT COUNT(*) FROM search_document_fts WHERE search_document_fts MATCH '$query'",
    )

    private fun draft(
        analysisId: String,
        caption: String,
        tag: String,
        token: String,
    ) = CanonicalAnalysisDraft(
        analysisId = analysisId,
        imageLocalId = IMAGE_ID,
        schemaVersion = 1,
        caption = caption,
        tags = listOf(CanonicalTermInput(tag, 0.8)),
        categories = emptyList(),
        searchTokens = listOf(token),
        extensionJson = null,
        providerProfileId = "provider",
        modelProfileId = "model",
        protocolDefinitionId = "protocol",
        promptTemplateId = "prompt",
        createdAtEpochMillis = 10L,
        completedAtEpochMillis = 20L,
    )

    private fun snapshot(
        caption: String? = "重庆 caption",
        terms: List<EffectiveImageTermEntity> = emptyList(),
    ) = EffectiveProjectionSnapshot(
        EffectiveImageMetadataEntity(
            IMAGE_ID,
            caption,
            EffectiveCaptionSource.AI,
            1,
            1,
        ),
        terms,
    )

    private fun image() = ImageEntity(
        localId = IMAGE_ID,
        volumeName = "external",
        mediaStoreId = 10,
        contentUri = "content://media/10",
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        sizeBytes = 1000,
        capturedAtEpochMillis = null,
        addedAtEpochMillis = 1,
        modifiedAtEpochMillis = 1,
        sortTimeEpochMillis = 1,
        bucketId = null,
        bucketName = "Album",
        isFavorite = false,
        quickFingerprint = "1000:1",
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )

    private companion object {
        const val IMAGE_ID = 1L
        const val ANALYSIS_ONE = "123e4567-e89b-12d3-a456-426614174000"
        const val ANALYSIS_TWO = "123e4567-e89b-12d3-a456-426614174001"
    }
}

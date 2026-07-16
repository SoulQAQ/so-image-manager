package cn.soul2.imageai.search

import cn.soul2.imageai.analysis.EffectiveProjectionSnapshot
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveImageMetadataEntity
import cn.soul2.imageai.data.db.entity.EffectiveImageTermEntity
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.SearchTermOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProjectionPlannerTest {
    private val planner = SearchProjectionPlanner()

    @Test
    fun projectsMediaEffectiveTermsOwnershipMasksAndFrozenWeights() {
        val plan = planner.plan(
            image = image(),
            snapshot = snapshot(
                terms = listOf(
                    term(AnalysisTermKind.TAG, "cat", "Cat", EffectiveTermSource.AI),
                    term(AnalysisTermKind.TAG, "favorite", "Favorite", EffectiveTermSource.USER),
                    term(AnalysisTermKind.CATEGORY, "travel", "Travel", EffectiveTermSource.AI),
                    term(AnalysisTermKind.SEARCH_TOKEN, "night city", "Night City", EffectiveTermSource.AI),
                ),
            ),
        )

        assertEquals("photo 2026.jpg", plan.document.fileName)
        assertEquals("summer album", plan.document.album)
        assertEquals("user caption", plan.document.caption)
        assertEquals("cat favorite", plan.document.tags)
        assertEquals("travel", plan.document.categories)
        assertEquals("night city", plan.document.searchTokens)
        listOf("1920", "1080", "4096", "111", "222", "333").forEach {
            assertTrue(plan.document.mediaText.contains(it))
        }

        val mappings = plan.mappings.associateBy { it.normalizedKey to it.field }
        assertMapping(mappings.getValue("photo 2026.jpg" to SearchField.FILE_NAME), 1, SearchTermOwnership.MEDIA, 450)
        assertMapping(mappings.getValue("summer album" to SearchField.ALBUM), 2, SearchTermOwnership.MEDIA, 450)
        assertMapping(mappings.getValue("cat" to SearchField.TAG), 8, SearchTermOwnership.AI, 500)
        assertMapping(mappings.getValue("favorite" to SearchField.TAG), 8, SearchTermOwnership.USER, 600)
        assertMapping(mappings.getValue("travel" to SearchField.CATEGORY), 16, SearchTermOwnership.AI, 500)
        assertMapping(mappings.getValue("night city" to SearchField.SEARCH_TOKEN), 32, SearchTermOwnership.AI, 350)
        assertTrue(plan.sourceChunks.any { it.field == SearchField.CAPTION })
        assertTrue(plan.sourceChunks.any { it.field == SearchField.MEDIA_TEXT })
    }

    @Test
    fun consumesOnlyEffectiveSnapshotTermsSoHistoricalAndTombstonedValuesStayExcluded() {
        val plan = planner.plan(
            image(),
            snapshot(
                terms = listOf(
                    term(AnalysisTermKind.TAG, "visible", "Visible", EffectiveTermSource.USER),
                    term(AnalysisTermKind.SEARCH_TOKEN, "active token", "Active Token", EffectiveTermSource.AI),
                ),
            ),
        )

        assertTrue(plan.document.tags.contains("visible"))
        assertTrue(plan.document.searchTokens.contains("active token"))
        assertFalse(plan.terms.any { it.normalizedKey in setOf("historical token", "tombstoned") })
    }

    @Test
    fun completeFourKiBCaptionStreamsKeepEverySourcePinyinAndInitialsSuffix() {
        val caption = "重庆".repeat(682) + "test"
        assertEquals(4 * 1_024, caption.toByteArray(Charsets.UTF_8).size)
        val plan = planner.plan(image(), snapshot(caption = caption))

        val source = plan.sourceChunks.filter { it.field == SearchField.CAPTION }
        val full = plan.textAliasChunks.filter {
            it.field == SearchField.CAPTION && it.aliasType == PinyinAliasType.FULL
        }
        val initials = plan.textAliasChunks.filter {
            it.field == SearchField.CAPTION && it.aliasType == PinyinAliasType.INITIALS
        }
        assertTrue(source.size > 1)
        assertTrue(full.size > 1)
        assertTrue(initials.size > 1)
        assertTrue(source.any { caption.takeLast(128) in it.text })
        assertTrue(full.any { "chongqing".repeat(14).takeLast(128) in it.text })
        assertTrue(initials.any { "cq".repeat(64) in it.text })
        assertEquals(caption, reconstructCodePoints(source.map(PlannedSourceChunk::text)))
        val streams = PinyinTransliterator.completeStreams(caption)
        assertEquals(streams.full, reconstructCharacters(full.map(PlannedTextAliasChunk::text)))
        assertEquals(streams.initials, reconstructCharacters(initials.map(PlannedTextAliasChunk::text)))
    }

    @Test
    fun relationshipCountAcceptsExactly768Rejects769AndIgnoresDuplicates() {
        val base = planner.plan(image(displayName = "", bucketName = null), snapshot(caption = null, terms = emptyList()))
        val acceptedTerms = List(SearchLimits.RELATIONSHIPS_PER_IMAGE - base.relationshipCount) { index ->
            term(AnalysisTermKind.TAG, "tag-$index", "Tag $index", EffectiveTermSource.AI)
        }
        val accepted = planner.plan(
            image(displayName = "", bucketName = null),
            snapshot(caption = null, terms = acceptedTerms + acceptedTerms.take(2)),
        )
        val rejected = planner.plan(
            image(displayName = "", bucketName = null),
            snapshot(
                caption = null,
                terms = acceptedTerms + term(
                    AnalysisTermKind.TAG,
                    "one-too-many",
                    "One Too Many",
                    EffectiveTermSource.AI,
                ),
            ),
        )

        assertEquals(768, accepted.relationshipCount)
        assertEquals(769, rejected.relationshipCount)
        assertEquals(accepted.mappings.distinct(), accepted.mappings)
        assertEquals(accepted.sourceChunks.distinct(), accepted.sourceChunks)
    }

    private fun assertMapping(
        mapping: PlannedSearchMapping,
        fieldMask: Int,
        ownership: SearchTermOwnership,
        weight: Int,
    ) {
        assertEquals(fieldMask, mapping.field.mask)
        assertEquals(ownership, mapping.ownership)
        assertEquals(weight.toDouble(), mapping.weight, 0.0)
    }

    private fun reconstructCodePoints(chunks: List<String>): String = buildString {
        chunks.forEachIndexed { index, text ->
            if (index == 0) append(text) else {
                val offset = text.offsetByCodePoints(0, SearchLimits.SOURCE_CHUNK_OVERLAP)
                append(text.substring(offset))
            }
        }
    }

    private fun reconstructCharacters(chunks: List<String>): String = buildString {
        chunks.forEachIndexed { index, text ->
            if (index == 0) append(text) else append(text.drop(SearchLimits.ALIAS_CHUNK_OVERLAP))
        }
    }

    private fun snapshot(
        caption: String? = "User Caption",
        terms: List<EffectiveImageTermEntity> = emptyList(),
    ) = EffectiveProjectionSnapshot(
        metadata = EffectiveImageMetadataEntity(
            imageLocalId = 1,
            caption = caption,
            captionSource = EffectiveCaptionSource.USER,
            projectionGeneration = 7,
            updatedAtEpochMillis = 999,
        ),
        terms = terms,
    )

    private fun term(
        kind: AnalysisTermKind,
        key: String,
        display: String,
        source: EffectiveTermSource,
    ) = EffectiveImageTermEntity(1, kind, key, display, source, null, null)

    private fun image(
        displayName: String = "Photo 2026.jpg",
        bucketName: String? = "Summer Album",
    ) = ImageEntity(
        localId = 1,
        volumeName = "external",
        mediaStoreId = 10,
        contentUri = "content://media/10",
        displayName = displayName,
        mimeType = "image/jpeg",
        width = 1920,
        height = 1080,
        sizeBytes = 4096,
        capturedAtEpochMillis = 111,
        addedAtEpochMillis = 222,
        modifiedAtEpochMillis = 333,
        sortTimeEpochMillis = 333,
        bucketId = 20,
        bucketName = bucketName,
        isFavorite = true,
        quickFingerprint = "4096:333",
        availability = ImageAvailability.AVAILABLE,
        lastSeenSyncRunId = null,
        missingCandidateSinceEpochMillis = null,
        missingObservationCount = 0,
    )
}

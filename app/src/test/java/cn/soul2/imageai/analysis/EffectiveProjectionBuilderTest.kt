package cn.soul2.imageai.analysis

import cn.soul2.imageai.data.db.entity.AnalysisTermEntity
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.CaptionCorrectionMode
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.ImageAnalysisEntity
import cn.soul2.imageai.data.db.entity.ImageUserCorrectionEntity
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction
import cn.soul2.imageai.data.db.entity.UserTermOverrideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveProjectionBuilderTest {
    @Test
    fun tombstoneSuppressesSameKindAiTermAndMatchingTokenButNotOtherKind() {
        val result = ready(
            build(
                terms = listOf(
                    term(AnalysisTermKind.TAG, "cat"),
                    term(AnalysisTermKind.CATEGORY, "cat"),
                    term(AnalysisTermKind.SEARCH_TOKEN, "cat"),
                    term(AnalysisTermKind.SEARCH_TOKEN, "pet"),
                ),
                overrides = listOf(
                    override(
                        kind = AnalysisTermKind.TAG,
                        key = "cat",
                        action = UserTermOverrideAction.TOMBSTONE,
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                AnalysisTermKind.CATEGORY to "cat",
                AnalysisTermKind.SEARCH_TOKEN to "pet",
            ),
            result.terms.map { it.kind to it.normalizedKey },
        )
    }

    @Test
    fun categoryTombstoneSuppressesCategoryAndMatchingTokenButNotTag() {
        val result = ready(
            build(
                terms = listOf(
                    term(AnalysisTermKind.TAG, "cat"),
                    term(AnalysisTermKind.CATEGORY, "cat"),
                    term(AnalysisTermKind.SEARCH_TOKEN, "cat"),
                ),
                overrides = listOf(
                    override(
                        kind = AnalysisTermKind.CATEGORY,
                        key = "cat",
                        action = UserTermOverrideAction.TOMBSTONE,
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(AnalysisTermKind.TAG to "cat"),
            result.terms.map { it.kind to it.normalizedKey },
        )
    }

    @Test
    fun matchingTokenReturnsOnlyAfterBothTagAndCategoryTombstonesAreRestored() {
        val terms = listOf(
            term(AnalysisTermKind.TAG, "cat"),
            term(AnalysisTermKind.CATEGORY, "cat"),
            term(AnalysisTermKind.SEARCH_TOKEN, "cat"),
        )
        val tagTombstone = override(
            kind = AnalysisTermKind.TAG,
            key = "cat",
            action = UserTermOverrideAction.TOMBSTONE,
        )
        val categoryTombstone = override(
            kind = AnalysisTermKind.CATEGORY,
            key = "cat",
            action = UserTermOverrideAction.TOMBSTONE,
        )

        val bothDeleted = ready(build(terms = terms, overrides = listOf(tagTombstone, categoryTombstone)))
        val tagRestored = ready(build(terms = terms, overrides = listOf(categoryTombstone)))
        val bothRestored = ready(build(terms = terms, overrides = emptyList()))

        assertTrue(bothDeleted.terms.isEmpty())
        assertEquals(
            listOf(AnalysisTermKind.TAG to "cat"),
            tagRestored.terms.map { it.kind to it.normalizedKey },
        )
        assertEquals(
            setOf(
                AnalysisTermKind.TAG to "cat",
                AnalysisTermKind.CATEGORY to "cat",
                AnalysisTermKind.SEARCH_TOKEN to "cat",
            ),
            bothRestored.terms.map { it.kind to it.normalizedKey }.toSet(),
        )
    }

    @Test
    fun userAdditionOverridesAiOwnershipAndSurvivesWithoutActiveAnalysis() {
        val overrides = listOf(
            override(
                kind = AnalysisTermKind.TAG,
                key = "cat",
                action = UserTermOverrideAction.ADD,
                display = "Cat",
            ),
        )
        val withAnalysis = ready(
            build(terms = listOf(term(AnalysisTermKind.TAG, "cat")), overrides = overrides),
        )
        val withoutAnalysis = ready(build(analysis = null, terms = emptyList(), overrides = overrides))

        listOf(withAnalysis, withoutAnalysis).forEach { result ->
            assertEquals(EffectiveTermSource.USER, result.terms.single().source)
            assertEquals("Cat", result.terms.single().displayValue)
            assertNull(result.terms.single().sourceAnalysisId)
        }
    }

    @Test
    fun captionCorrectionModesRemainExplicit() {
        val inherit = ready(build(correction = correction(CaptionCorrectionMode.INHERIT)))
        val set = ready(build(correction = correction(CaptionCorrectionMode.SET, "用户描述")))
        val cleared = ready(build(correction = correction(CaptionCorrectionMode.CLEARED)))

        assertEquals("AI caption", inherit.metadata.caption)
        assertEquals(EffectiveCaptionSource.AI, inherit.metadata.captionSource)
        assertEquals("用户描述", set.metadata.caption)
        assertEquals(EffectiveCaptionSource.USER, set.metadata.captionSource)
        assertNull(cleared.metadata.caption)
        assertEquals(EffectiveCaptionSource.USER_CLEARED, cleared.metadata.captionSource)
    }

    @Test
    fun effectiveLimitBlocksTheWholeProjection() {
        val aiTerms = List(128) { index -> term(AnalysisTermKind.TAG, "ai-$index") }
        val additions = List(129) { index ->
            override(
                kind = AnalysisTermKind.TAG,
                key = "user-$index",
                action = UserTermOverrideAction.ADD,
                display = "user-$index",
            )
        }

        val result = build(terms = aiTerms, overrides = additions)

        assertTrue(result is EffectiveProjectionBuildResult.Blocked)
        assertEquals(
            "EFFECTIVE_TAG_LIMIT",
            (result as EffectiveProjectionBuildResult.Blocked).code,
        )
    }

    private fun build(
        analysis: ImageAnalysisEntity? = analysis(),
        terms: List<AnalysisTermEntity> = emptyList(),
        correction: ImageUserCorrectionEntity? = null,
        overrides: List<UserTermOverrideEntity> = emptyList(),
    ): EffectiveProjectionBuildResult = EffectiveProjectionBuilder.build(
        imageLocalId = IMAGE_ID,
        activeAnalysis = analysis,
        analysisTerms = terms,
        correction = correction,
        overrides = overrides,
        projectionGeneration = 4L,
        nowEpochMillis = 100L,
    )

    private fun ready(result: EffectiveProjectionBuildResult): EffectiveProjectionBuildResult.Ready =
        result as EffectiveProjectionBuildResult.Ready

    private fun analysis() = ImageAnalysisEntity(
        analysisId = ANALYSIS_ID,
        imageLocalId = IMAGE_ID,
        schemaVersion = 1,
        caption = "AI caption",
        extensionJson = null,
        contentHash = "HASH",
        providerProfileId = "provider",
        modelProfileId = "model",
        protocolDefinitionId = "protocol",
        promptTemplateId = "prompt",
        createdAtEpochMillis = 10L,
        completedAtEpochMillis = 20L,
    )

    private fun term(kind: AnalysisTermKind, key: String) = AnalysisTermEntity(
        analysisId = ANALYSIS_ID,
        kind = kind,
        normalizedKey = key,
        displayValue = key,
        confidence = 0.8,
    )

    private fun correction(mode: CaptionCorrectionMode, value: String? = null) =
        ImageUserCorrectionEntity(
            imageLocalId = IMAGE_ID,
            captionMode = mode,
            captionValue = value,
            revision = 1L,
            updatedAtEpochMillis = 50L,
        )

    private fun override(
        kind: AnalysisTermKind,
        key: String,
        action: UserTermOverrideAction,
        display: String? = null,
    ) = UserTermOverrideEntity(
        imageLocalId = IMAGE_ID,
        kind = kind,
        normalizedKey = key,
        action = action,
        displayValue = display,
        revision = 1L,
        updatedAtEpochMillis = 50L,
    )

    private companion object {
        const val IMAGE_ID = 42L
        const val ANALYSIS_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}

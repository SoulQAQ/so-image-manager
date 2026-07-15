package cn.soul2.imageai.analysis

import android.app.Application
import java.util.UUID
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CanonicalAnalysisDraftTest {
    @Test
    fun validatesAndDeduplicatesCanonicalTermsByNormalizedKey() {
        val validated = draft(
            tags = listOf(
                CanonicalTermInput(" Cat ", confidence = 0.6),
                CanonicalTermInput("ＣＡＴ", confidence = 0.9),
                CanonicalTermInput("街景", confidence = null),
            ),
            categories = listOf(CanonicalTermInput("旅行")),
            searchTokens = listOf("Shanghai", "ＳＨＡＮＧＨＡＩ", "夜景"),
        ).validate()

        assertEquals(listOf("cat", "街景"), validated.tags.map { it.normalizedKey })
        assertEquals("Cat", validated.tags.first().displayValue)
        assertEquals(0.9, validated.tags.first().confidence)
        assertEquals(listOf("shanghai", "夜景"), validated.searchTokens.map { it.normalizedKey })
        assertTrue(validated.contentHash.matches(Regex("[0-9A-F]{64}")))
    }

    @Test
    fun equivalentDraftsProduceTheSameContentHash() {
        val first = draft(
            tags = listOf(CanonicalTermInput("Cat", 0.8)),
            extensionJson = "{\"a\":{\"x\":1,\"y\":2},\"b\":2}",
        ).validate()
        val second = draft(
            analysisId = "123e4567-e89b-12d3-a456-426614174001",
            tags = listOf(CanonicalTermInput("Ｃａｔ", 0.8)),
            extensionJson = "{\"b\":2,\"a\":{\"y\":2,\"x\":1}}",
        ).validate()

        assertEquals(first.contentHash, second.contentHash)
        assertEquals(first.extensionJson, second.extensionJson)
    }

    @Test
    fun enforcesCaptionTermCountAndExtensionJsonLimitsBeforePersistence() {
        assertThrows(CanonicalValidationException::class.java) {
            draft(caption = "你".repeat(1_366)).validate()
        }
        assertThrows(CanonicalValidationException::class.java) {
            draft(tags = List(129) { CanonicalTermInput("tag-$it") }).validate()
        }
        assertThrows(CanonicalValidationException::class.java) {
            draft(extensionJson = "{invalid").validate()
        }
        assertThrows(CanonicalValidationException::class.java) {
            draft(extensionJson = "[1, 2, 3]").validate()
        }
        assertThrows(CanonicalValidationException::class.java) {
            draft(extensionJson = "{\"data\":\"${"x".repeat(17_000)}\"}").validate()
        }
        assertTrue(
            draft(
                extensionJson = "{\"data\":\"${"x".repeat(17_000)}\"}",
                extensionPersistenceBudgetBytes = 64 * 1_024,
            ).validate().extensionJson?.isNotEmpty() == true,
        )
    }

    @Test
    fun rejectsExtensionJsonDeeperThanThirtyTwoLevels() {
        val tooDeep = buildString {
            append("{\"value\":")
            repeat(32) { append('[') }
            append('0')
            repeat(32) { append(']') }
            append('}')
        }

        val error = assertThrows(CanonicalValidationException::class.java) {
            draft(extensionJson = tooDeep).validate()
        }

        assertTrue(error.message.orEmpty().contains("depth"))
    }

    @Test
    fun rejectsExtensionJsonTreesWithMoreThanFiftyThousandNodes() {
        val values = JSONArray()
        repeat(50_001) { values.put(it) }

        val error = assertThrows(CanonicalValidationException::class.java) {
            CanonicalJsonValidator.validateParsedTree(values)
        }

        assertTrue(error.message.orEmpty().contains("node"))
    }

    @Test
    fun rechecksExtensionJsonBudgetAfterCanonicalEscaping() {
        val valueWithUnquotedKey = "{value:1}"

        val error = assertThrows(CanonicalValidationException::class.java) {
            draft(
                extensionJson = valueWithUnquotedKey,
                extensionPersistenceBudgetBytes = valueWithUnquotedKey.toByteArray().size,
            ).validate()
        }

        assertTrue(error.message.orEmpty().contains("after canonicalization"))
    }

    @Test
    fun rejectsInvalidIdentityProvenanceConfidenceAndTime() {
        assertThrows(CanonicalValidationException::class.java) {
            draft(imageLocalId = 0L).validate()
        }
        assertThrows(CanonicalValidationException::class.java) {
            draft(modelProfileId = "model\nsecret").validate()
        }
        assertThrows(CanonicalValidationException::class.java) {
            draft(tags = listOf(CanonicalTermInput("cat", 1.1))).validate()
        }
        assertThrows(CanonicalValidationException::class.java) {
            draft(createdAtEpochMillis = 20L, completedAtEpochMillis = 10L).validate()
        }
    }

    private fun draft(
        analysisId: String = "123e4567-e89b-12d3-a456-426614174000",
        imageLocalId: Long = 42L,
        caption: String = "夜晚的城市街道",
        tags: List<CanonicalTermInput> = listOf(CanonicalTermInput("街景", 0.8)),
        categories: List<CanonicalTermInput> = listOf(CanonicalTermInput("旅行")),
        searchTokens: List<String> = listOf("夜景"),
        extensionJson: String? = "{\"weather\":\"clear\"}",
        extensionPersistenceBudgetBytes: Int = 16 * 1_024,
        modelProfileId: String = "model.default",
        createdAtEpochMillis: Long = 10L,
        completedAtEpochMillis: Long = 20L,
    ) = CanonicalAnalysisDraft(
        analysisId = UUID.fromString(analysisId).toString(),
        imageLocalId = imageLocalId,
        schemaVersion = 1,
        caption = caption,
        tags = tags,
        categories = categories,
        searchTokens = searchTokens,
        extensionJson = extensionJson,
        extensionPersistenceBudgetBytes = extensionPersistenceBudgetBytes,
        providerProfileId = "provider.default",
        modelProfileId = modelProfileId,
        protocolDefinitionId = "protocol.openai.responses.v1",
        promptTemplateId = "prompt.image-analysis.v1",
        createdAtEpochMillis = createdAtEpochMillis,
        completedAtEpochMillis = completedAtEpochMillis,
    )
}

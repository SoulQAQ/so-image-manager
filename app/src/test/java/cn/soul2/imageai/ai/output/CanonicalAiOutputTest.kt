package cn.soul2.imageai.ai.output

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CanonicalAiOutputTest {
    @Test
    fun parsesStrictPayloadAndMapsItIntoValidatedCanonicalMetadata() {
        val payload = CanonicalAiPayloadParser.parse(VALID_PAYLOAD)
        val validated = payload.toDraft(context()).validate()

        assertEquals("夜晚的城市街道", validated.caption)
        assertEquals(listOf("街景", "夜景"), validated.tags.map { it.normalizedKey })
        assertEquals(0.92, validated.tags.first().confidence)
        assertEquals(listOf("旅行"), validated.categories.map { it.normalizedKey })
        assertEquals(listOf("shanghai", "城市灯光"), validated.searchTokens.map { it.normalizedKey })
        assertNull(validated.extensionJson)
        assertEquals(CanonicalAiOutputSchema.VERSION, validated.schemaVersion)
    }

    @Test
    fun rejectsUnknownMissingOrWrongTypeFields() {
        assertInvalid(VALID_PAYLOAD.dropLast(1) + ",\"extra\":true}")
        assertInvalid(VALID_PAYLOAD.replace(",\"search_tokens\":[\"Shanghai\",\"城市灯光\"]", ""))
        assertInvalid(VALID_PAYLOAD.replace("\"caption\":\"夜晚的城市街道\"", "\"caption\":42"))
        assertInvalid(VALID_PAYLOAD.replace("\"tags\":[", "\"tags\":{\"bad\":"))
    }

    @Test
    fun rejectsTermShapeConfidenceAndCountViolations() {
        assertInvalid(
            VALID_PAYLOAD.replace(
                "{\"value\":\"街景\",\"confidence\":0.92}",
                "{\"value\":\"街景\",\"confidence\":2}",
            ),
        )
        assertInvalid(
            VALID_PAYLOAD.replace(
                "{\"value\":\"街景\",\"confidence\":0.92}",
                "{\"value\":\"街景\",\"confidence\":null,\"extra\":1}",
            ),
        )
        val tooManyTokens = List(257) { "token-$it" }
            .joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
        assertInvalid(
            VALID_PAYLOAD.replace("[\"Shanghai\",\"城市灯光\"]", tooManyTokens),
        )
    }

    @Test
    fun schemaRequiresEveryFieldAndDisallowsAdditionalProperties() {
        val schema = JSONObject(CanonicalAiOutputSchema.jsonSchema)
        assertEquals("object", schema.getString("type"))
        assertEquals(false, schema.getBoolean("additionalProperties"))
        assertEquals(4, schema.getJSONArray("required").length())
        val termSchema = schema.getJSONObject("properties")
            .getJSONObject("tags")
            .getJSONObject("items")
        assertEquals(false, termSchema.getBoolean("additionalProperties"))
        assertTrue(termSchema.getJSONArray("required").toString().contains("confidence"))
    }

    private fun assertInvalid(value: String) {
        assertThrows(AiOutputValidationException::class.java) {
            CanonicalAiPayloadParser.parse(value)
        }
    }

    private fun context() = CanonicalAiDraftContext(
        analysisId = "123e4567-e89b-12d3-a456-426614174000",
        imageLocalId = 42L,
        providerProfileId = "provider",
        modelProfileId = "model",
        protocolDefinitionId = "openai-responses",
        promptTemplateId = "image-analysis-v1",
        createdAtEpochMillis = 10L,
        completedAtEpochMillis = 20L,
    )

    private companion object {
        val VALID_PAYLOAD = """
            {
              "caption":"夜晚的城市街道",
              "tags":[
                {"value":"街景","confidence":0.92},
                {"value":"夜景","confidence":null}
              ],
              "categories":[{"value":"旅行","confidence":0.8}],
              "search_tokens":["Shanghai","城市灯光"]
            }
        """.trimIndent().replace("\n", "").replace("  ", "")
    }
}

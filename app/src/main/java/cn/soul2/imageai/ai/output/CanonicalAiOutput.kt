package cn.soul2.imageai.ai.output

import cn.soul2.imageai.analysis.CanonicalAnalysisDraft
import cn.soul2.imageai.analysis.CanonicalLimits
import cn.soul2.imageai.analysis.CanonicalTermInput
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class CanonicalAiPayload(
    val caption: String,
    val tags: List<CanonicalTermInput>,
    val categories: List<CanonicalTermInput>,
    val searchTokens: List<String>,
    val usageTokens: Long? = null,
) {
    fun toDraft(context: CanonicalAiDraftContext): CanonicalAnalysisDraft =
        CanonicalAnalysisDraft(
            analysisId = context.analysisId,
            imageLocalId = context.imageLocalId,
            schemaVersion = CanonicalAiOutputSchema.VERSION,
            caption = caption,
            tags = tags,
            categories = categories,
            searchTokens = searchTokens,
            extensionJson = null,
            providerProfileId = context.providerProfileId,
            modelProfileId = context.modelProfileId,
            protocolDefinitionId = context.protocolDefinitionId,
            promptTemplateId = context.promptTemplateId,
            createdAtEpochMillis = context.createdAtEpochMillis,
            completedAtEpochMillis = context.completedAtEpochMillis,
        )
}

data class CanonicalAiDraftContext(
    val analysisId: String = UUID.randomUUID().toString(),
    val imageLocalId: Long,
    val providerProfileId: String,
    val modelProfileId: String,
    val protocolDefinitionId: String,
    val promptTemplateId: String,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
)

object CanonicalAiOutputSchema {
    const val VERSION = 1
    const val NAME = "soim_image_analysis_v1"

    val jsonSchema: String = JSONObject().apply {
        put("type", "object")
        put("additionalProperties", false)
        put("required", JSONArray(listOf("caption", "tags", "categories", "search_tokens")))
        put(
            "properties",
            JSONObject().apply {
                put("caption", stringSchema(maxLength = CanonicalLimits.CAPTION_UTF8_BYTES))
                put("tags", termArraySchema(CanonicalLimits.TAGS))
                put("categories", termArraySchema(CanonicalLimits.CATEGORIES))
                put(
                    "search_tokens",
                    JSONObject().apply {
                        put("type", "array")
                        put("maxItems", CanonicalLimits.SEARCH_TOKENS)
                        put("items", stringSchema(CanonicalLimits.TERM_CODE_POINTS))
                    },
                )
            },
        )
    }.toString()

    private fun termArraySchema(maxItems: Int) = JSONObject().apply {
        put("type", "array")
        put("maxItems", maxItems)
        put(
            "items",
            JSONObject().apply {
                put("type", "object")
                put("additionalProperties", false)
                put("required", JSONArray(listOf("value", "confidence")))
                put(
                    "properties",
                    JSONObject().apply {
                        put("value", stringSchema(CanonicalLimits.TERM_CODE_POINTS))
                        put(
                            "confidence",
                            JSONObject().apply {
                                put("type", JSONArray(listOf("number", "null")))
                                put("minimum", 0)
                                put("maximum", 1)
                            },
                        )
                    },
                )
            },
        )
    }

    private fun stringSchema(maxLength: Int) = JSONObject().apply {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", maxLength)
    }
}

class AiOutputValidationException(message: String) : IllegalArgumentException(message)

object CanonicalAiPayloadParser {
    fun parse(rawJson: String): CanonicalAiPayload {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_UTF8_BYTES) {
            invalid("AI output exceeds the payload byte limit")
        }
        val tokener = JSONTokener(rawJson)
        val root = try {
            tokener.nextValue()
        } catch (error: Exception) {
            throw AiOutputValidationException("AI output is not valid JSON")
        }
        if (root !is JSONObject || tokener.nextClean().code != 0) {
            invalid("AI output must contain exactly one JSON object")
        }
        requireKeys(root, ROOT_KEYS, "root")
        val caption = requireString(root, "caption")
        val tags = parseTerms(requireArray(root, "tags"), CanonicalLimits.TAGS, "tags")
        val categories = parseTerms(
            requireArray(root, "categories"),
            CanonicalLimits.CATEGORIES,
            "categories",
        )
        val searchTokens = parseSearchTokens(requireArray(root, "search_tokens"))
        return CanonicalAiPayload(caption, tags, categories, searchTokens)
    }

    private fun parseTerms(array: JSONArray, maximum: Int, field: String): List<CanonicalTermInput> {
        if (array.length() > maximum) invalid("$field exceeds $maximum items")
        return List(array.length()) { index ->
            val item = array.opt(index)
            if (item !is JSONObject) invalid("$field[$index] must be an object")
            requireKeys(item, TERM_KEYS, "$field[$index]")
            val value = requireString(item, "value")
            val confidenceValue = item.opt("confidence")
            val confidence = when (confidenceValue) {
                null, JSONObject.NULL -> null
                is Number -> confidenceValue.toDouble().also {
                    if (!it.isFinite() || it !in 0.0..1.0) {
                        invalid("$field[$index].confidence must be between 0 and 1")
                    }
                }
                else -> invalid("$field[$index].confidence must be a number or null")
            }
            CanonicalTermInput(value, confidence)
        }
    }

    private fun parseSearchTokens(array: JSONArray): List<String> {
        if (array.length() > CanonicalLimits.SEARCH_TOKENS) {
            invalid("search_tokens exceeds ${CanonicalLimits.SEARCH_TOKENS} items")
        }
        return List(array.length()) { index ->
            val value = array.opt(index)
            if (value !is String) invalid("search_tokens[$index] must be a string")
            value
        }
    }

    private fun requireKeys(value: JSONObject, expected: Set<String>, field: String) {
        val actual = value.keys().asSequence().toSet()
        val missing = expected - actual
        val unknown = actual - expected
        if (missing.isNotEmpty()) invalid("$field is missing fields: ${missing.sorted()}")
        if (unknown.isNotEmpty()) invalid("$field contains unknown fields: ${unknown.sorted()}")
    }

    private fun requireString(value: JSONObject, key: String): String =
        (value.opt(key) as? String) ?: invalid("$key must be a string")

    private fun requireArray(value: JSONObject, key: String): JSONArray =
        value.opt(key) as? JSONArray ?: invalid("$key must be an array")

    private fun invalid(message: String): Nothing = throw AiOutputValidationException(message)

    private val ROOT_KEYS = setOf("caption", "tags", "categories", "search_tokens")
    private val TERM_KEYS = setOf("value", "confidence")
    private const val MAX_PAYLOAD_UTF8_BYTES = 128 * 1_024
}

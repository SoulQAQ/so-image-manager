package cn.soul2.imageai.ai.protocol

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class CustomJsonProtocolDefinition(
    val endpointPath: String,
    val requestBodyTemplate: JSONObject,
    val responsePayloadPointer: JsonPointer,
) {
    fun renderRequest(context: CustomJsonTemplateContext): ByteArray {
        val rendered = renderNode(requestBodyTemplate, context, depth = 1)
        check(rendered is JSONObject)
        return rendered.toString().toByteArray(Charsets.UTF_8)
    }

    private fun renderNode(value: Any?, context: CustomJsonTemplateContext, depth: Int): Any? {
        if (depth > MAX_TEMPLATE_DEPTH) invalid("request template exceeds maximum depth")
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> if (value.has(SOURCE_KEY)) {
                renderExpression(value, context)
            } else {
                JSONObject().apply {
                    value.keys().forEach { key ->
                        put(key, renderNode(value.get(key), context, depth + 1))
                    }
                }
            }
            is JSONArray -> JSONArray().apply {
                repeat(value.length()) { index ->
                    put(renderNode(value.get(index), context, depth + 1))
                }
            }
            is String, is Number, is Boolean -> value
            else -> invalid("request template contains unsupported value")
        }
    }

    private fun renderExpression(
        expression: JSONObject,
        context: CustomJsonTemplateContext,
    ): Any {
        requireExactKeys(expression, setOf(SOURCE_KEY, TRANSFORM_KEY), allowMissing = setOf(TRANSFORM_KEY))
        val source = enumValue<TemplateSource>(requireString(expression, SOURCE_KEY), "template source")
        val transform = expression.optString(TRANSFORM_KEY, TemplateTransform.NONE.name)
            .let { enumValue<TemplateTransform>(it, "template transform") }
        validateTransform(source, transform)
        return when (source) {
            TemplateSource.MODEL_ID -> context.modelId
            TemplateSource.PROMPT -> context.prompt
            TemplateSource.IMAGE_MIME_TYPE -> context.imageMimeType
            TemplateSource.IMAGE_BYTES -> when (transform) {
                TemplateTransform.BASE64 -> Base64.getEncoder().encodeToString(context.imageBytes)
                TemplateTransform.DATA_URL -> "data:${context.imageMimeType};base64," +
                    Base64.getEncoder().encodeToString(context.imageBytes)
                else -> error("validated above")
            }
            TemplateSource.OUTPUT_SCHEMA -> when (transform) {
                TemplateTransform.PARSE_JSON -> JSONObject(context.outputSchemaJson)
                TemplateTransform.NONE -> context.outputSchemaJson
                else -> error("validated above")
            }
            TemplateSource.MAX_OUTPUT_TOKENS -> context.maxOutputTokens ?: JSONObject.NULL
            TemplateSource.TEMPERATURE -> context.temperature ?: JSONObject.NULL
        }
    }

    companion object {
        const val VERSION = 1
        private const val SOURCE_KEY = "\$source"
        private const val TRANSFORM_KEY = "transform"
        private const val MAX_DEFINITION_UTF8_BYTES = 128 * 1_024
        private const val MAX_TEMPLATE_DEPTH = 32
        private const val MAX_TEMPLATE_NODES = 10_000

        fun parse(rawJson: String): CustomJsonProtocolDefinition {
            if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_DEFINITION_UTF8_BYTES) {
                invalid("protocol definition exceeds byte limit")
            }
            val tokener = JSONTokener(rawJson)
            val root = try {
                tokener.nextValue()
            } catch (_: Exception) {
                invalid("protocol definition is not valid JSON")
            }
            if (root !is JSONObject || tokener.nextClean().code != 0) {
                invalid("protocol definition must contain one JSON object")
            }
            requireExactKeys(root, setOf("version", "endpoint_path", "request_body", "response"))
            if (root.optInt("version", -1) != VERSION || root.opt("version") !is Number) {
                invalid("unsupported protocol definition version")
            }
            val endpointPath = requireString(root, "endpoint_path")
            validateEndpointPath(endpointPath)
            val requestBody = root.opt("request_body") as? JSONObject
                ?: invalid("request_body must be an object")
            validateTemplate(requestBody)
            val response = root.opt("response") as? JSONObject
                ?: invalid("response must be an object")
            requireExactKeys(response, setOf("payload_pointer"))
            val pointer = JsonPointer.parse(requireString(response, "payload_pointer"))
            return CustomJsonProtocolDefinition(endpointPath, requestBody, pointer)
        }

        private fun validateTemplate(root: JSONObject) {
            var nodes = 0
            fun visit(value: Any?, depth: Int) {
                nodes += 1
                if (nodes > MAX_TEMPLATE_NODES) invalid("request template exceeds node limit")
                if (depth > MAX_TEMPLATE_DEPTH) invalid("request template exceeds maximum depth")
                when (value) {
                    is JSONObject -> if (value.has(SOURCE_KEY)) {
                        requireExactKeys(
                            value,
                            setOf(SOURCE_KEY, TRANSFORM_KEY),
                            allowMissing = setOf(TRANSFORM_KEY),
                        )
                        val source = enumValue<TemplateSource>(
                            requireString(value, SOURCE_KEY),
                            "template source",
                        )
                        val transform = value.optString(TRANSFORM_KEY, TemplateTransform.NONE.name)
                            .let { enumValue<TemplateTransform>(it, "template transform") }
                        validateTransform(source, transform)
                    } else {
                        value.keys().forEach { key -> visit(value.get(key), depth + 1) }
                    }
                    is JSONArray -> repeat(value.length()) { visit(value.get(it), depth + 1) }
                    null, JSONObject.NULL, is String, is Number, is Boolean -> Unit
                    else -> invalid("request template contains unsupported value")
                }
            }
            visit(root, 1)
        }

        private fun validateEndpointPath(path: String) {
            if (
                path.isBlank() || path.length > 512 || path.startsWith('/') ||
                path.contains("//") || path.contains('?') || path.contains('#') ||
                path.split('/').any { it == "." || it == ".." }
            ) {
                invalid("endpoint_path must be a safe relative path")
            }
        }

        private fun validateTransform(source: TemplateSource, transform: TemplateTransform) {
            val valid = when (source) {
                TemplateSource.IMAGE_BYTES -> transform in setOf(
                    TemplateTransform.BASE64,
                    TemplateTransform.DATA_URL,
                )
                TemplateSource.OUTPUT_SCHEMA -> transform in setOf(
                    TemplateTransform.NONE,
                    TemplateTransform.PARSE_JSON,
                )
                else -> transform == TemplateTransform.NONE
            }
            if (!valid) invalid("transform $transform is not allowed for source $source")
        }
    }
}

data class CustomJsonTemplateContext(
    val modelId: String,
    val prompt: String,
    val imageBytes: ByteArray,
    val imageMimeType: String,
    val outputSchemaJson: String,
    val maxOutputTokens: Int?,
    val temperature: Double?,
)

enum class TemplateSource {
    MODEL_ID,
    PROMPT,
    IMAGE_BYTES,
    IMAGE_MIME_TYPE,
    OUTPUT_SCHEMA,
    MAX_OUTPUT_TOKENS,
    TEMPERATURE,
}

enum class TemplateTransform {
    NONE,
    BASE64,
    DATA_URL,
    PARSE_JSON,
}

class ProtocolDefinitionException(message: String) : IllegalArgumentException(message)

internal fun invalid(message: String): Nothing = throw ProtocolDefinitionException(message)

private fun requireString(value: JSONObject, key: String): String =
    value.opt(key) as? String ?: invalid("$key must be a string")

private fun requireExactKeys(
    value: JSONObject,
    expected: Set<String>,
    allowMissing: Set<String> = emptySet(),
) {
    val actual = value.keys().asSequence().toSet()
    val missing = expected - allowMissing - actual
    val unknown = actual - expected
    if (missing.isNotEmpty()) invalid("missing fields: ${missing.sorted()}")
    if (unknown.isNotEmpty()) invalid("unknown fields: ${unknown.sorted()}")
}

private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: invalid("unsupported $field: $value")

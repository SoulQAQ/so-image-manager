package cn.soul2.imageai.ai.protocol

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomJsonProtocolDefinitionTest {
    @Test
    fun rendersOnlyTypedAllowlistedSourcesAndTransforms() {
        val definition = CustomJsonProtocolDefinition.parse(DEFINITION)
        val body = JSONObject(
            definition.renderRequest(
                CustomJsonTemplateContext(
                    modelId = "vision-model",
                    prompt = "中文描述",
                    imageBytes = byteArrayOf(1, 2, 3),
                    imageMimeType = "image/jpeg",
                    outputSchemaJson = "{\"type\":\"object\"}",
                    maxOutputTokens = 2_048,
                    temperature = 0.2,
                ),
            ).toString(Charsets.UTF_8),
        )

        assertEquals("vision-model", body.getString("model"))
        assertEquals("中文描述", body.getString("prompt"))
        assertEquals("data:image/jpeg;base64,AQID", body.getString("image"))
        assertEquals("object", body.getJSONObject("schema").getString("type"))
        assertEquals(2_048, body.getInt("max_tokens"))
        assertEquals(0.2, body.getDouble("temperature"), 0.0)
    }

    @Test
    fun rejectsScriptsUnknownFieldsUnsafePathsAndInvalidTransforms() {
        assertInvalid(DEFINITION.replace("\"endpoint_path\":\"analyze\"", "\"endpoint_path\":\"../analyze\""))
        assertInvalid(DEFINITION.dropLast(1) + ",\"script\":\"return 1\"}")
        assertInvalid(DEFINITION.replace("\"DATA_URL\"", "\"PARSE_JSON\""))
        assertInvalid(DEFINITION.replace("\"MODEL_ID\"", "\"ENVIRONMENT\""))
        assertInvalid(DEFINITION.replace("\"payload_pointer\":\"/result\"", "\"payload_pointer\":\"result\""))
    }

    @Test
    fun rfc6901PointerSupportsEscapesAndArraysAndRejectsBadTraversal() {
        val root = JSONObject("{\"a/b\":{\"~key\":[{\"value\":42}]}}")
        assertEquals(42, JsonPointer.parse("/a~1b/~0key/0/value").resolve(root))
        assertThrows(ProtocolDefinitionException::class.java) {
            JsonPointer.parse("/a~2b")
        }
        assertThrows(ProtocolDefinitionException::class.java) {
            JsonPointer.parse("/a~1b/~0key/4").resolve(root)
        }
    }

    @Test
    fun definitionContainsNoExecutableExpressionSurface() {
        val definition = CustomJsonProtocolDefinition.parse(DEFINITION)
        val rendered = definition.renderRequest(
            CustomJsonTemplateContext(
                modelId = "model",
                prompt = "prompt",
                imageBytes = byteArrayOf(),
                imageMimeType = "image/jpeg",
                outputSchemaJson = "{}",
                maxOutputTokens = null,
                temperature = null,
            ),
        ).toString(Charsets.UTF_8)
        assertTrue(rendered.contains("\"max_tokens\":null"))
        assertTrue(rendered.contains("\"temperature\":null"))
    }

    private fun assertInvalid(value: String) {
        assertThrows(ProtocolDefinitionException::class.java) {
            CustomJsonProtocolDefinition.parse(value)
        }
    }

    companion object {
        val DEFINITION = """
            {
              "version":1,
              "endpoint_path":"analyze",
              "request_body":{
                "model":{"${'$'}source":"MODEL_ID"},
                "prompt":{"${'$'}source":"PROMPT"},
                "image":{"${'$'}source":"IMAGE_BYTES","transform":"DATA_URL"},
                "schema":{"${'$'}source":"OUTPUT_SCHEMA","transform":"PARSE_JSON"},
                "max_tokens":{"${'$'}source":"MAX_OUTPUT_TOKENS"},
                "temperature":{"${'$'}source":"TEMPERATURE"}
              },
              "response":{"payload_pointer":"/result"}
            }
        """.trimIndent()
    }
}

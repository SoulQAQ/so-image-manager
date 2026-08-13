package cn.soul2.imageai.ai.protocol

import android.util.Base64
import cn.soul2.imageai.ai.analysis.AiModelClient
import cn.soul2.imageai.ai.analysis.AiModelException
import cn.soul2.imageai.ai.analysis.AiModelFailure
import cn.soul2.imageai.ai.analysis.AiModelInvocation
import cn.soul2.imageai.ai.output.CanonicalAiOutputSchema
import cn.soul2.imageai.ai.output.CanonicalAiPayload
import cn.soul2.imageai.ai.transport.AiHttpRequest
import cn.soul2.imageai.ai.transport.AiTransportException
import cn.soul2.imageai.ai.transport.SecureAiHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class OpenAiChatCompletionsAiModelClient(
    private val transport: SecureAiHttpTransport,
) : AiModelClient {
    override suspend fun analyze(invocation: AiModelInvocation): CanonicalAiPayload = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", invocation.configuration.model.modelId)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", invocation.configuration.runtime.promptText))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put(
                            "url",
                            "data:${invocation.image.mimeType};base64," +
                                Base64.encodeToString(invocation.image.bytes, Base64.NO_WRAP),
                        ),
                    ),
                )
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", CanonicalAiOutputSchema.NAME).put("strict", true)
                .put("schema", JSONObject(CanonicalAiOutputSchema.jsonSchema))))
            invocation.configuration.model.maxOutputTokens?.let { put("max_tokens", it) }
            invocation.configuration.model.temperature?.let { put("temperature", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        val response = try {
            transport.execute(AiHttpRequest(invocation.configuration.provider, invocation.quotaPolicy,
                resolvePresetEndpoint(invocation.configuration.provider.baseUrl, "chat/completions"),
                body = body, traceSink = invocation.traceSink))
        } catch (error: AiTransportException) {
            throw error.toPresetModelException()
        } finally { body.fill(0) }
        requireSuccessfulResponse(response)
        try {
            val root = JSONObject(response.body.toString(Charsets.UTF_8))
            val text = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val usage = root.optJSONObject("usage")
            parseCanonicalResponse(text).copy(usageTokens = usage?.optLong("total_tokens")
                ?.takeIf { it > 0L } ?: usage?.let {
                    (it.optLong("prompt_tokens") + it.optLong("completion_tokens")).takeIf { total -> total > 0L }
                })
        } catch (error: AiModelException) { throw error
        } catch (error: Exception) { throw AiModelException(AiModelFailure.RESPONSE_INVALID, error)
        } finally { response.body.fill(0) }
    }
}

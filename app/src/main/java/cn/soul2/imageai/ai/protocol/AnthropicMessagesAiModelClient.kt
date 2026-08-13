package cn.soul2.imageai.ai.protocol

import android.util.Base64
import cn.soul2.imageai.ai.analysis.*
import cn.soul2.imageai.ai.output.CanonicalAiPayload
import cn.soul2.imageai.ai.transport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AnthropicMessagesAiModelClient(private val transport: SecureAiHttpTransport) : AiModelClient {
    override suspend fun analyze(invocation: AiModelInvocation): CanonicalAiPayload = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", invocation.configuration.model.modelId)
            put("max_tokens", invocation.configuration.model.maxOutputTokens ?: 2_048)
            invocation.configuration.model.temperature?.let { put("temperature", it) }
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
                    .put("media_type", invocation.image.mimeType)
                    .put("data", Base64.encodeToString(invocation.image.bytes, Base64.NO_WRAP))))
                .put(JSONObject().put("type", "text").put("text", invocation.configuration.runtime.promptText +
                    "\n仅返回符合 SoIM 结构的 JSON 对象，不要使用 Markdown 代码块。")))))
        }.toString().toByteArray(Charsets.UTF_8)
        val response = try {
            transport.execute(AiHttpRequest(invocation.configuration.provider, invocation.quotaPolicy,
                resolvePresetEndpoint(invocation.configuration.provider.baseUrl, "messages"),
                headers = mapOf("anthropic-version" to "2023-06-01"), body = body,
                traceSink = invocation.traceSink))
        } catch (error: AiTransportException) { throw error.toPresetModelException() }
        finally { body.fill(0) }
        requireSuccessfulResponse(response)
        try {
            val root = JSONObject(response.body.toString(Charsets.UTF_8))
            val content = root.getJSONArray("content")
            val text = (0 until content.length()).asSequence().mapNotNull { content.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "text" }?.getString("text")
                ?: throw AiModelException(AiModelFailure.RESPONSE_INVALID)
            val usage = root.optJSONObject("usage")
            parseCanonicalResponse(text).copy(usageTokens = usage?.let {
                (it.optLong("input_tokens") + it.optLong("output_tokens")).takeIf { total -> total > 0L }
            })
        } catch (error: AiModelException) { throw error
        } catch (error: Exception) { throw AiModelException(AiModelFailure.RESPONSE_INVALID, error) }
        finally { response.body.fill(0) }
    }
}

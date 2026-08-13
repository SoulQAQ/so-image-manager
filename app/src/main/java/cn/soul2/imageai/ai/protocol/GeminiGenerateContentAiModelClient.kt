package cn.soul2.imageai.ai.protocol

import android.util.Base64
import cn.soul2.imageai.ai.analysis.*
import cn.soul2.imageai.ai.output.CanonicalAiOutputSchema
import cn.soul2.imageai.ai.output.CanonicalAiPayload
import cn.soul2.imageai.ai.transport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GeminiGenerateContentAiModelClient(private val transport: SecureAiHttpTransport) : AiModelClient {
    override suspend fun analyze(invocation: AiModelInvocation): CanonicalAiPayload = withContext(Dispatchers.IO) {
        val modelId = invocation.configuration.model.modelId
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("text", invocation.configuration.runtime.promptText))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", invocation.image.mimeType)
                    .put("data", Base64.encodeToString(invocation.image.bytes, Base64.NO_WRAP)))))))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json")
                .put("responseJsonSchema", JSONObject(CanonicalAiOutputSchema.jsonSchema)).apply {
                    invocation.configuration.model.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    invocation.configuration.model.temperature?.let { put("temperature", it) }
                })
        }.toString().toByteArray(Charsets.UTF_8)
        val endpoint = resolvePresetEndpoint(invocation.configuration.provider.baseUrl,
            "models/${java.net.URLEncoder.encode(modelId, Charsets.UTF_8.name())}:generateContent")
        val response = try {
            transport.execute(AiHttpRequest(invocation.configuration.provider, invocation.quotaPolicy,
                endpoint, body = body, traceSink = invocation.traceSink))
        } catch (error: AiTransportException) { throw error.toPresetModelException() }
        finally { body.fill(0) }
        requireSuccessfulResponse(response)
        try {
            val root = JSONObject(response.body.toString(Charsets.UTF_8))
            val text = root.getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            parseCanonicalResponse(text).copy(usageTokens = root.optJSONObject("usageMetadata")
                ?.optLong("totalTokenCount")?.takeIf { it > 0L })
        } catch (error: AiModelException) { throw error
        } catch (error: Exception) { throw AiModelException(AiModelFailure.RESPONSE_INVALID, error) }
        finally { response.body.fill(0) }
    }
}

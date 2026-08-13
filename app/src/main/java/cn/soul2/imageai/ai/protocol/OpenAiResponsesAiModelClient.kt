package cn.soul2.imageai.ai.protocol

import android.util.Base64
import cn.soul2.imageai.ai.analysis.AiModelClient
import cn.soul2.imageai.ai.analysis.AiModelException
import cn.soul2.imageai.ai.analysis.AiModelFailure
import cn.soul2.imageai.ai.analysis.AiModelInvocation
import cn.soul2.imageai.ai.output.CanonicalAiOutputSchema
import cn.soul2.imageai.ai.output.CanonicalAiPayload
import cn.soul2.imageai.ai.output.CanonicalAiPayloadParser
import cn.soul2.imageai.ai.transport.AiHttpRequest
import cn.soul2.imageai.ai.transport.AiTransportException
import cn.soul2.imageai.ai.transport.AiTransportFailure
import cn.soul2.imageai.ai.transport.SecureAiHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class OpenAiResponsesAiModelClient(
    private val transport: SecureAiHttpTransport,
) : AiModelClient {
    override suspend fun analyze(invocation: AiModelInvocation): CanonicalAiPayload =
        withContext(Dispatchers.IO) {
            val requestBody = buildRequestBody(invocation).toString().toByteArray(Charsets.UTF_8)
            val response = try {
                transport.execute(
                    AiHttpRequest(
                        provider = invocation.configuration.provider,
                        quotaPolicy = invocation.quotaPolicy,
                        url = resolveEndpoint(invocation.configuration.provider.baseUrl),
                        body = requestBody,
                        traceSink = invocation.traceSink,
                    ),
                )
            } catch (error: AiTransportException) {
                throw error.toModelException()
            } finally {
                requestBody.fill(0)
            }
            requireSuccessfulResponse(response)
            try {
                val root = JSONObject(response.body.toString(Charsets.UTF_8))
                val usage = root.optJSONObject("usage")
                CanonicalAiPayloadParser.parse(extractOutputText(response.body)).copy(
                    usageTokens = usage?.optLong("total_tokens")?.takeIf { it > 0L }
                        ?: usage?.let {
                            (it.optLong("input_tokens") + it.optLong("output_tokens"))
                                .takeIf { total -> total > 0L }
                        },
                )
            } catch (error: IllegalArgumentException) {
                throw AiModelException(AiModelFailure.RESPONSE_INVALID, error)
            } finally {
                response.body.fill(0)
            }
        }

    private fun buildRequestBody(invocation: AiModelInvocation) = JSONObject().apply {
        put("model", invocation.configuration.model.modelId)
        put("store", false)
        put(
            "input",
            JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put(
                        "content",
                        JSONArray()
                            .put(
                                JSONObject().apply {
                                    put("type", "input_text")
                                    put("text", invocation.configuration.runtime.promptText)
                                },
                            )
                            .put(
                                JSONObject().apply {
                                    put("type", "input_image")
                                    put(
                                        "image_url",
                                        "data:${invocation.image.mimeType};base64," +
                                            Base64.encodeToString(
                                                invocation.image.bytes,
                                                Base64.NO_WRAP,
                                            ),
                                    )
                                    put("detail", "auto")
                                },
                            ),
                    )
                },
            ),
        )
        put(
            "text",
            JSONObject().put(
                "format",
                JSONObject().apply {
                    put("type", "json_schema")
                    put("name", CanonicalAiOutputSchema.NAME)
                    put("strict", true)
                    put("schema", JSONObject(CanonicalAiOutputSchema.jsonSchema))
                },
            ),
        )
        invocation.configuration.model.maxOutputTokens?.let { put("max_output_tokens", it) }
        invocation.configuration.model.temperature?.let { put("temperature", it) }
    }

    private fun resolveEndpoint(baseUrl: String): String {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
        val directoryBase = if (base.encodedPath.endsWith('/')) {
            base
        } else {
            (base.toString() + "/").toHttpUrlOrNull()
                ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
        }
        return directoryBase.resolve("responses")?.toString()
            ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
    }

    private fun extractOutputText(bytes: ByteArray): String {
        val tokener = JSONTokener(bytes.toString(Charsets.UTF_8))
        val root = try {
            tokener.nextValue()
        } catch (error: Exception) {
            throw AiModelException(AiModelFailure.RESPONSE_INVALID, error)
        }
        if (root !is JSONObject || tokener.nextClean().code != 0) {
            throw AiModelException(AiModelFailure.RESPONSE_INVALID)
        }
        val output = root.optJSONArray("output")
            ?: throw AiModelException(AiModelFailure.RESPONSE_INVALID)
        repeat(output.length()) { outputIndex ->
            val item = output.optJSONObject(outputIndex) ?: return@repeat
            if (item.optString("type") != "message") return@repeat
            val content = item.optJSONArray("content") ?: return@repeat
            repeat(content.length()) { contentIndex ->
                val part = content.optJSONObject(contentIndex) ?: return@repeat
                if (part.optString("type") == "output_text") {
                    val text = part.opt("text") as? String
                    if (!text.isNullOrBlank()) return text
                }
            }
        }
        throw AiModelException(AiModelFailure.RESPONSE_INVALID)
    }

    private fun AiTransportException.toModelException() = AiModelException(
        failure = when (failure) {
            AiTransportFailure.CREDENTIAL_MISSING -> AiModelFailure.CREDENTIAL_MISSING
            AiTransportFailure.CREDENTIAL_UNAVAILABLE -> AiModelFailure.CREDENTIAL_UNAVAILABLE
            AiTransportFailure.QUOTA_REJECTED -> AiModelFailure.QUOTA_REJECTED
            AiTransportFailure.NETWORK_IO -> AiModelFailure.NETWORK
            AiTransportFailure.RESPONSE_TOO_LARGE -> AiModelFailure.RESPONSE_INVALID
            AiTransportFailure.INVALID_CONFIGURATION,
            AiTransportFailure.INVALID_URL,
            AiTransportFailure.CLEARTEXT_NOT_APPROVED,
            AiTransportFailure.REDIRECT_NOT_ALLOWED,
            AiTransportFailure.TOO_MANY_REDIRECTS,
            -> AiModelFailure.UNSUPPORTED_PROTOCOL
        },
        cause = this,
    )
}

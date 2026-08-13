package cn.soul2.imageai.ai.protocol

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
import org.json.JSONObject
import org.json.JSONTokener

class CustomJsonAiModelClient(
    private val transport: SecureAiHttpTransport,
) : AiModelClient {
    override suspend fun analyze(invocation: AiModelInvocation): CanonicalAiPayload =
        withContext(Dispatchers.IO) {
            val protocolEntity = invocation.configuration.protocolDefinition
                ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
            val definition = try {
                CustomJsonProtocolDefinition.parse(protocolEntity.definitionJson)
            } catch (error: ProtocolDefinitionException) {
                throw AiModelException(AiModelFailure.RESPONSE_INVALID, error)
            }
            val endpoint = resolveEndpoint(
                invocation.configuration.provider.baseUrl,
                definition.endpointPath,
            )
            val requestBody = definition.renderRequest(
                CustomJsonTemplateContext(
                    modelId = invocation.configuration.model.modelId,
                    prompt = invocation.configuration.runtime.promptText,
                    imageBytes = invocation.image.bytes,
                    imageMimeType = invocation.image.mimeType,
                    outputSchemaJson = CanonicalAiOutputSchema.jsonSchema,
                    maxOutputTokens = invocation.configuration.model.maxOutputTokens,
                    temperature = invocation.configuration.model.temperature,
                ),
            )
            val response = try {
                try {
                    transport.execute(
                        AiHttpRequest(
                            provider = invocation.configuration.provider,
                            quotaPolicy = invocation.quotaPolicy,
                            url = endpoint,
                body = requestBody,
                traceSink = invocation.traceSink,
                        ),
                    )
                } catch (error: AiTransportException) {
                    throw error.toModelException()
                }
            } finally {
                requestBody.fill(0)
            }
            if (response.statusCode !in 200..299) {
                throw AiModelException(AiModelFailure.PROVIDER_HTTP_ERROR)
            }
            val envelope = parseEnvelope(response.body)
            val payload = try {
                definition.responsePayloadPointer.resolve(envelope)
            } catch (error: ProtocolDefinitionException) {
                throw AiModelException(AiModelFailure.RESPONSE_INVALID, error)
            }
            val payloadJson = when (payload) {
                is JSONObject -> payload.toString()
                is String -> payload
                else -> throw AiModelException(AiModelFailure.RESPONSE_INVALID)
            }
            try {
                CanonicalAiPayloadParser.parse(payloadJson)
            } catch (error: IllegalArgumentException) {
                throw AiModelException(AiModelFailure.RESPONSE_INVALID, error)
            }
        }

    private fun resolveEndpoint(baseUrl: String, endpointPath: String): String {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
        val directoryBase = if (base.encodedPath.endsWith('/')) {
            base
        } else {
            (base.toString() + "/").toHttpUrlOrNull()
                ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
        }
        return directoryBase.resolve(endpointPath)?.toString()
            ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
    }

    private fun parseEnvelope(bytes: ByteArray): Any {
        val text = bytes.toString(Charsets.UTF_8)
        val tokener = JSONTokener(text)
        val value = try {
            tokener.nextValue()
        } catch (error: Exception) {
            throw AiModelException(AiModelFailure.RESPONSE_INVALID, error)
        }
        if (value !is JSONObject || tokener.nextClean().code != 0) {
            throw AiModelException(AiModelFailure.RESPONSE_INVALID)
        }
        return value
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

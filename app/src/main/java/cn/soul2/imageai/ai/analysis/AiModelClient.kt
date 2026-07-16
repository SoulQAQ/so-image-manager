package cn.soul2.imageai.ai.analysis

import cn.soul2.imageai.ai.image.PreparedImage
import cn.soul2.imageai.ai.output.CanonicalAiPayload
import cn.soul2.imageai.ai.quota.AiQuotaPolicy
import cn.soul2.imageai.data.db.entity.ModelProtocolType

data class AiModelInvocation(
    val configuration: ResolvedAiAnalysisConfiguration,
    val quotaPolicy: AiQuotaPolicy,
    val image: PreparedImage,
)

enum class AiModelFailure {
    UNSUPPORTED_PROTOCOL,
    CREDENTIAL_MISSING,
    CREDENTIAL_UNAVAILABLE,
    QUOTA_REJECTED,
    NETWORK,
    PROVIDER_HTTP_ERROR,
    RESPONSE_INVALID,
}

class AiModelException(
    val failure: AiModelFailure,
    cause: Throwable? = null,
) : Exception("AI model call failed: $failure", cause)

fun interface AiModelClient {
    suspend fun analyze(invocation: AiModelInvocation): CanonicalAiPayload
}

class AiModelClientRegistry(
    clients: Map<ModelProtocolType, AiModelClient>,
) {
    private val clients = clients.toMap()

    fun require(protocolType: ModelProtocolType): AiModelClient =
        clients[protocolType] ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
}

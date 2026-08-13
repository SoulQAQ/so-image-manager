package cn.soul2.imageai.ai.transport

import cn.soul2.imageai.ai.quota.AiQuotaAcquireResult
import cn.soul2.imageai.ai.quota.AiQuotaPolicy
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity

data class AiHttpRequest(
    val provider: ProviderProfileEntity,
    val quotaPolicy: AiQuotaPolicy,
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = "application/json; charset=utf-8",
    val body: ByteArray? = null,
    val containsSensitiveData: Boolean = true,
    val bodyReplayable: Boolean = true,
    val traceSink: ((AiHttpTrace) -> Unit)? = null,
)

data class AiHttpTrace(
    val method: String,
    val url: String,
    val headerNames: List<String>,
    val redactedRequestBody: String?,
    val statusCode: Int,
    val responseBody: String,
)

data class AiHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val finalUrl: String,
    val redirectCount: Int,
)

enum class AiTransportFailure {
    INVALID_CONFIGURATION,
    INVALID_URL,
    CLEARTEXT_NOT_APPROVED,
    CREDENTIAL_MISSING,
    CREDENTIAL_UNAVAILABLE,
    QUOTA_REJECTED,
    REDIRECT_NOT_ALLOWED,
    TOO_MANY_REDIRECTS,
    RESPONSE_TOO_LARGE,
    NETWORK_IO,
}

class AiTransportException(
    val failure: AiTransportFailure,
    val quotaRejection: AiQuotaAcquireResult.Rejected? = null,
    cause: Throwable? = null,
) : Exception("AI transport failed: $failure", cause)

package cn.soul2.imageai.ai.protocol

import cn.soul2.imageai.ai.analysis.AiModelException
import cn.soul2.imageai.ai.analysis.AiModelFailure
import cn.soul2.imageai.ai.transport.AiTransportException
import cn.soul2.imageai.ai.transport.AiTransportFailure
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import cn.soul2.imageai.ai.transport.AiHttpResponse
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun resolvePresetEndpoint(baseUrl: String, relativePath: String): String {
    val base = baseUrl.toHttpUrlOrNull()
        ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
    val directory = if (base.encodedPath.endsWith('/')) base else {
        (base.toString() + "/").toHttpUrlOrNull()
            ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
    }
    return directory.resolve(relativePath)?.toString()
        ?: throw AiModelException(AiModelFailure.UNSUPPORTED_PROTOCOL)
}

internal fun AiTransportException.toPresetModelException() = AiModelException(
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

internal fun parseCanonicalResponse(raw: String) = try {
    cn.soul2.imageai.ai.output.CanonicalAiPayloadParser.parse(raw)
} catch (error: IllegalArgumentException) {
    throw AiModelException(AiModelFailure.RESPONSE_INVALID, error)
}

internal fun requireSuccessfulResponse(response: AiHttpResponse, nowEpochMillis: Long = System.currentTimeMillis()) {
    if (response.statusCode in 200..299) return
    val retryAt = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
        ?.value?.firstOrNull()?.let { parseRetryAfter(it, nowEpochMillis) }
    val failure = when (response.statusCode) {
        401, 403 -> AiModelFailure.PROVIDER_AUTH_ERROR
        408, 409, 425, 429, in 500..599 -> AiModelFailure.PROVIDER_TEMPORARY
        else -> AiModelFailure.PROVIDER_HTTP_ERROR
    }
    response.body.fill(0)
    throw AiModelException(
        failure,
        retryAtEpochMillis = retryAt,
        statusCode = response.statusCode,
    )
}

private fun parseRetryAfter(value: String, nowEpochMillis: Long): Long? {
    value.trim().toLongOrNull()?.let { seconds ->
        return nowEpochMillis + seconds.coerceIn(0L, 86_400L) * 1_000L
    }
    return runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli().coerceAtLeast(nowEpochMillis)
    }.getOrNull()
}

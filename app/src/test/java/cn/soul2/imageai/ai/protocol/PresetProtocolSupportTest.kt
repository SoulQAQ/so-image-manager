package cn.soul2.imageai.ai.protocol

import cn.soul2.imageai.ai.analysis.AiModelException
import cn.soul2.imageai.ai.analysis.AiModelFailure
import cn.soul2.imageai.ai.transport.AiHttpResponse
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PresetProtocolSupportTest {
    @Test
    fun retryAfterSecondsIsPreservedForTemporaryResponses() {
        val error = assertThrows(AiModelException::class.java) {
            requireSuccessfulResponse(response(429, mapOf("Retry-After" to listOf("15"))), 1_000L)
        }

        assertEquals(AiModelFailure.PROVIDER_TEMPORARY, error.failure)
        assertEquals(16_000L, error.retryAtEpochMillis)
    }

    @Test
    fun retryAfterHttpDateAndExplicitRejectionAreClassifiedSeparately() {
        val retryAt = 120_000L
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(retryAt).atZone(ZoneOffset.UTC),
        )
        val temporary = assertThrows(AiModelException::class.java) {
            requireSuccessfulResponse(response(503, mapOf("retry-after" to listOf(date))), 1_000L)
        }
        val rejected = assertThrows(AiModelException::class.java) {
            requireSuccessfulResponse(response(400), 1_000L)
        }

        assertEquals(AiModelFailure.PROVIDER_TEMPORARY, temporary.failure)
        assertEquals(retryAt, temporary.retryAtEpochMillis)
        assertEquals(AiModelFailure.PROVIDER_HTTP_ERROR, rejected.failure)
        assertEquals(null, rejected.retryAtEpochMillis)
    }

    private fun response(code: Int, headers: Map<String, List<String>> = emptyMap()) = AiHttpResponse(
        statusCode = code,
        headers = headers,
        body = "error".toByteArray(),
        finalUrl = "https://example.test/v1",
        redirectCount = 0,
    )
}

package cn.soul2.imageai.ai.transport

import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.ai.credential.CredentialReadResult
import cn.soul2.imageai.ai.quota.AiQuotaAcquireResult
import cn.soul2.imageai.ai.quota.AiQuotaCoordinator
import cn.soul2.imageai.ai.quota.AiQuotaLimit
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class SecureAiHttpTransport(
    private val credentialStore: AiCredentialStore,
    private val quotaCoordinator: AiQuotaCoordinator,
    private val baseClient: OkHttpClient = defaultClient(),
) {
    fun execute(spec: AiHttpRequest): AiHttpResponse {
        validateQuotaBinding(spec)
        if (!spec.provider.enabled || (spec.body?.size ?: 0) > MAX_REQUEST_BODY_BYTES) {
            throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
        }
        val initialUrl = spec.url.toHttpUrlOrNull()
            ?: throw AiTransportException(AiTransportFailure.INVALID_URL)
        val baseUrl = spec.provider.baseUrl.toHttpUrlOrNull()
            ?: throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
        if (!initialUrl.sameOrigin(baseUrl)) {
            throw AiTransportException(AiTransportFailure.INVALID_URL)
        }
        validateDestination(initialUrl, baseUrl, spec.provider.cleartextApproved)
        val allowedRedirectOrigins = parseAllowedOrigins(
            spec.provider.allowedRedirectOriginsJson,
        )
        val headers = buildHeaders(spec)
        val hasCredential = spec.provider.authMode != ProviderAuthMode.NONE
        val sensitive =
            spec.containsSensitiveData || spec.body != null || hasCredential || headers.size > 0
        val method = spec.method.uppercase(Locale.ROOT)
        if (method !in SUPPORTED_METHODS || (method == "GET" && spec.body != null)) {
            throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
        }
        val client = baseClient.newBuilder()
            .connectTimeout(spec.provider.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(spec.provider.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(spec.provider.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        var currentUrl = initialUrl
        var redirectCount = 0
        while (true) {
            val request = buildRequest(spec, currentUrl, method, headers)
            val response = executeOnce(client, request, spec)
            if (!response.isRedirect) {
                return response.use {
                    AiHttpResponse(
                        statusCode = it.code,
                        headers = it.headers.toMultimap(),
                        body = readBoundedBody(it),
                        finalUrl = currentUrl.toString(),
                        redirectCount = redirectCount,
                    )
                }
            }

            response.use {
                if (redirectCount >= MAX_REDIRECTS) {
                    throw AiTransportException(AiTransportFailure.TOO_MANY_REDIRECTS)
                }
                val location = it.header("Location")
                    ?: throw AiTransportException(AiTransportFailure.REDIRECT_NOT_ALLOWED)
                val destination = currentUrl.resolve(location)
                    ?: throw AiTransportException(AiTransportFailure.INVALID_URL)
                validateRedirect(
                    statusCode = it.code,
                    method = method,
                    source = currentUrl,
                    destination = destination,
                    baseUrl = baseUrl,
                    cleartextApproved = spec.provider.cleartextApproved,
                    allowedRedirectOrigins = allowedRedirectOrigins,
                    sensitive = sensitive,
                    bodyReplayable = spec.bodyReplayable,
                )
                currentUrl = destination
                redirectCount += 1
            }
        }
    }

    private fun executeOnce(
        client: OkHttpClient,
        request: Request,
        spec: AiHttpRequest,
    ): Response {
        val quota = quotaCoordinator.tryAcquire(spec.quotaPolicy)
        if (quota is AiQuotaAcquireResult.Rejected) {
            throw AiTransportException(
                failure = AiTransportFailure.QUOTA_REJECTED,
                quotaRejection = quota,
            )
        }
        val lease = (quota as AiQuotaAcquireResult.Granted).lease
        return lease.use {
            lease.markDispatched()
            try {
                client.newCall(request).execute()
            } catch (error: IOException) {
                throw AiTransportException(AiTransportFailure.NETWORK_IO, cause = error)
            }
        }
    }

    private fun buildRequest(
        spec: AiHttpRequest,
        url: HttpUrl,
        method: String,
        headers: Headers,
    ): Request {
        val body = spec.body?.toRequestBody(spec.contentType.toMediaTypeOrNull())
        return Request.Builder()
            .url(url)
            .headers(headers)
            .method(method, body)
            .build()
    }

    private fun validateQuotaBinding(spec: AiHttpRequest) {
        val expectedProviderLimit = AiQuotaLimit(
            maxConcurrency = spec.provider.maxConcurrency,
            requestsPerMinute = spec.provider.requestsPerMinute,
            requestsPerDay = spec.provider.requestsPerDay,
        )
        if (
            spec.quotaPolicy.providerId != spec.provider.providerId ||
            spec.quotaPolicy.provider != expectedProviderLimit
        ) {
            throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
        }
    }

    private fun buildHeaders(spec: AiHttpRequest): Headers {
        val builder = Headers.Builder()
        val occupied = mutableSetOf<String>()
        fun add(name: String, value: String) {
            val normalized = name.lowercase(Locale.ROOT)
            if (normalized in FORBIDDEN_HEADERS || !occupied.add(normalized)) {
                throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
            }
            try {
                builder.add(name, value)
            } catch (error: IllegalArgumentException) {
                throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION, cause = error)
            }
        }

        parseStaticHeaders(spec.provider.headersJson).forEach { (name, value) -> add(name, value) }
        spec.headers.forEach { (name, value) -> add(name, value) }
        when (spec.provider.authMode) {
            ProviderAuthMode.NONE -> Unit
            ProviderAuthMode.BEARER,
            ProviderAuthMode.API_KEY_HEADER,
            -> {
                val credentialId = spec.provider.credentialId
                    ?: throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
                val secret = when (val result = credentialStore.read(credentialId)) {
                    CredentialReadResult.Missing -> throw AiTransportException(
                        AiTransportFailure.CREDENTIAL_MISSING,
                    )
                    is CredentialReadResult.Unavailable -> throw AiTransportException(
                        AiTransportFailure.CREDENTIAL_UNAVAILABLE,
                    )
                    is CredentialReadResult.Available -> result.secret
                }
                try {
                    val headerName = when (spec.provider.authMode) {
                        ProviderAuthMode.BEARER -> spec.provider.authHeaderName ?: "Authorization"
                        ProviderAuthMode.API_KEY_HEADER -> spec.provider.authHeaderName
                            ?: throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
                        ProviderAuthMode.NONE -> error("handled above")
                    }
                    val prefix = when (spec.provider.authMode) {
                        ProviderAuthMode.BEARER -> spec.provider.authPrefix ?: "Bearer"
                        ProviderAuthMode.API_KEY_HEADER -> spec.provider.authPrefix.orEmpty()
                        ProviderAuthMode.NONE -> error("handled above")
                    }
                    val secretText = secret.concatToString()
                    add(headerName, if (prefix.isBlank()) secretText else "$prefix $secretText")
                } finally {
                    secret.fill('\u0000')
                }
            }
        }
        return builder.build()
    }

    private fun validateRedirect(
        statusCode: Int,
        method: String,
        source: HttpUrl,
        destination: HttpUrl,
        baseUrl: HttpUrl,
        cleartextApproved: Boolean,
        allowedRedirectOrigins: Set<Origin>,
        sensitive: Boolean,
        bodyReplayable: Boolean,
    ) {
        if (source.isHttps && !destination.isHttps) {
            throw AiTransportException(AiTransportFailure.REDIRECT_NOT_ALLOWED)
        }
        val sameOrigin = source.sameOrigin(destination)
        if (!sameOrigin && destination.origin() !in allowedRedirectOrigins) {
            throw AiTransportException(AiTransportFailure.REDIRECT_NOT_ALLOWED)
        }
        if (!sameOrigin && sensitive) {
            throw AiTransportException(AiTransportFailure.REDIRECT_NOT_ALLOWED)
        }
        validateDestination(destination, baseUrl, cleartextApproved)
        if (!bodyReplayable) {
            throw AiTransportException(AiTransportFailure.REDIRECT_NOT_ALLOWED)
        }
        when (statusCode) {
            307, 308 -> Unit
            301, 302, 303 -> if (method != "GET") {
                throw AiTransportException(AiTransportFailure.REDIRECT_NOT_ALLOWED)
            }
            else -> throw AiTransportException(AiTransportFailure.REDIRECT_NOT_ALLOWED)
        }
    }

    private fun validateDestination(
        destination: HttpUrl,
        baseUrl: HttpUrl,
        cleartextApproved: Boolean,
    ) {
        if (destination.scheme == "https") return
        if (
            destination.scheme != "http" ||
            !cleartextApproved ||
            !destination.sameOrigin(baseUrl)
        ) {
            throw AiTransportException(AiTransportFailure.CLEARTEXT_NOT_APPROVED)
        }
    }

    private fun parseStaticHeaders(json: String): Map<String, String> = try {
        val objectValue = JSONObject(json)
        buildMap {
            objectValue.keys().forEach { key ->
                val value = objectValue.opt(key)
                if (value !is String) {
                    throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
                }
                put(key, value)
            }
        }
    } catch (error: JSONException) {
        throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION, cause = error)
    }

    private fun parseAllowedOrigins(json: String): Set<Origin> = try {
        val array = JSONArray(json)
        buildSet {
            repeat(array.length()) { index ->
                val url = array.optString(index, null)?.toHttpUrlOrNull()
                    ?: throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
                if (url.encodedPath != "/" || url.query != null || url.fragment != null) {
                    throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION)
                }
                add(url.origin())
            }
        }
    } catch (error: JSONException) {
        throw AiTransportException(AiTransportFailure.INVALID_CONFIGURATION, cause = error)
    }

    private fun readBoundedBody(response: Response): ByteArray {
        val body = response.body ?: return byteArrayOf()
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw AiTransportException(AiTransportFailure.RESPONSE_TOO_LARGE)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        body.byteStream().use { input ->
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (error: IOException) {
                    throw AiTransportException(AiTransportFailure.NETWORK_IO, cause = error)
                }
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw AiTransportException(AiTransportFailure.RESPONSE_TOO_LARGE)
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private fun HttpUrl.origin() = Origin(scheme, host, port)

    private fun HttpUrl.sameOrigin(other: HttpUrl) = origin() == other.origin()

    private companion object {
        const val MAX_REDIRECTS = 3
        const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
        const val MAX_REQUEST_BODY_BYTES = 12 * 1_024 * 1_024
        const val READ_BUFFER_BYTES = 8 * 1_024
        val SUPPORTED_METHODS = setOf("GET", "POST")
        val FORBIDDEN_HEADERS = setOf(
            "connection",
            "content-length",
            "host",
            "proxy-authorization",
            "transfer-encoding",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

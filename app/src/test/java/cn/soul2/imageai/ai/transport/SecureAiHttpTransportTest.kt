package cn.soul2.imageai.ai.transport

import android.app.Application
import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.ai.credential.CredentialReadResult
import cn.soul2.imageai.ai.quota.AiQuotaCoordinator
import cn.soul2.imageai.ai.quota.AiQuotaLimit
import cn.soul2.imageai.ai.quota.AiQuotaPolicy
import cn.soul2.imageai.ai.quota.AiQuotaRejectionReason
import cn.soul2.imageai.ai.quota.AiQuotaStateStore
import cn.soul2.imageai.ai.quota.PersistentQuotaUsage
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecureAiHttpTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var credentialStore: FakeCredentialStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentialStore = FakeCredentialStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sameOrigin307ReplaysPostBodyAndCountsEachNetworkRequest() {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/second"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val provider = provider(requestsPerMinute = 2)
        val transport = transport(provider)

        val response = transport.execute(request(provider))

        assertEquals(200, response.statusCode)
        assertEquals(1, response.redirectCount)
        assertArrayEquals("ok".toByteArray(), response.body)
        val first = server.takeRequest(1, TimeUnit.SECONDS)
        val second = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("POST", first?.method)
        assertEquals("POST", second?.method)
        assertEquals(first?.body?.readUtf8(), second?.body?.readUtf8())
    }

    @Test
    fun sensitivePost302IsNotConvertedToGet() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/other"))

        val error = assertThrows(AiTransportException::class.java) {
            transport().execute(request())
        }

        assertEquals(AiTransportFailure.REDIRECT_NOT_ALLOWED, error.failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun crossOriginSensitiveRedirectIsRejectedEvenWhenAllowlisted() {
        val destination = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", destination.url("/receive")),
            )
            val allowedOrigin = destination.url("/").toString()
            val provider = provider(
                allowedRedirectOriginsJson = "[\"$allowedOrigin\"]",
            )

            val error = assertThrows(AiTransportException::class.java) {
                transport(provider = provider).execute(request(provider))
            }

            assertEquals(AiTransportFailure.REDIRECT_NOT_ALLOWED, error.failure)
            assertEquals(0, destination.requestCount)
        } finally {
            destination.shutdown()
        }
    }

    @Test
    fun nonReplayableBodyFailsAtRedirectWithoutSecondRequest() {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/other"))

        val error = assertThrows(AiTransportException::class.java) {
            transport().execute(request().copy(bodyReplayable = false))
        }

        assertEquals(AiTransportFailure.REDIRECT_NOT_ALLOWED, error.failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectRequiresAnotherQuotaReservation() {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/second"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("must not be reached"))
        val provider = provider(requestsPerMinute = 1)

        val error = assertThrows(AiTransportException::class.java) {
            transport(provider).execute(request(provider))
        }

        assertEquals(AiTransportFailure.QUOTA_REJECTED, error.failure)
        assertEquals(AiQuotaRejectionReason.REQUESTS_PER_MINUTE, error.quotaRejection?.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun bearerCredentialIsAppliedAndCallerSecretArrayIsClearedAfterRead() {
        credentialStore.secret = "test-secret".toCharArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val provider = provider(
            authMode = ProviderAuthMode.BEARER,
            credentialId = CREDENTIAL_ID,
        )

        transport(provider = provider).execute(request(provider))

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("Bearer test-secret", recorded?.getHeader("Authorization"))
        assertTrue(credentialStore.lastReturned?.all { it == '\u0000' } == true)
    }

    @Test
    fun missingCredentialFailsBeforeQuotaOrNetworkUse() {
        val provider = provider(
            authMode = ProviderAuthMode.BEARER,
            credentialId = CREDENTIAL_ID,
        )

        val error = assertThrows(AiTransportException::class.java) {
            transport(provider = provider).execute(request(provider))
        }

        assertEquals(AiTransportFailure.CREDENTIAL_MISSING, error.failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun responseBodyIsBoundedToTwoMegabytes() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(ByteArray(2 * 1_024 * 1_024 + 1))),
        )

        val error = assertThrows(AiTransportException::class.java) {
            transport().execute(request())
        }

        assertEquals(AiTransportFailure.RESPONSE_TOO_LARGE, error.failure)
    }

    @Test
    fun cleartextMustBeApprovedForTheExactProviderOrigin() {
        val provider = provider(cleartextApproved = false)
        val error = assertThrows(AiTransportException::class.java) {
            transport(provider = provider).execute(request(provider))
        }
        assertEquals(AiTransportFailure.CLEARTEXT_NOT_APPROVED, error.failure)

        val other = MockWebServer().apply { start() }
        try {
            val wrongOrigin = request(provider()).copy(url = other.url("/responses").toString())
            val mismatch = assertThrows(AiTransportException::class.java) {
                transport().execute(wrongOrigin)
            }
            assertEquals(AiTransportFailure.INVALID_URL, mismatch.failure)
        } finally {
            other.shutdown()
        }
    }

    private fun transport(provider: ProviderProfileEntity = provider()) = SecureAiHttpTransport(
        credentialStore = credentialStore,
        quotaCoordinator = AiQuotaCoordinator(InMemoryQuotaStore()) { NOW },
    ).also {
        currentPolicy = policy(provider)
    }

    private lateinit var currentPolicy: AiQuotaPolicy

    private fun request(provider: ProviderProfileEntity = provider()) = AiHttpRequest(
        provider = provider,
        quotaPolicy = if (::currentPolicy.isInitialized && currentPolicy.providerId == provider.providerId) {
            currentPolicy
        } else {
            policy(provider)
        },
        url = server.url("/responses").toString(),
        body = "{\"input\":\"fixture\"}".toByteArray(),
    )

    private fun provider(
        cleartextApproved: Boolean = true,
        allowedRedirectOriginsJson: String = "[]",
        authMode: ProviderAuthMode = ProviderAuthMode.NONE,
        credentialId: String? = null,
        requestsPerMinute: Int = 10,
    ) = ProviderProfileEntity(
        providerId = PROVIDER_ID,
        displayName = "测试供应方",
        baseUrl = server.url("/v1").toString(),
        authMode = authMode,
        authHeaderName = null,
        authPrefix = null,
        credentialId = credentialId,
        headersJson = "{}",
        allowedRedirectOriginsJson = allowedRedirectOriginsJson,
        cleartextApproved = cleartextApproved,
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = 5_000,
        writeTimeoutMillis = 5_000,
        maxConcurrency = 2,
        requestsPerMinute = requestsPerMinute,
        requestsPerDay = 100,
        enabled = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun policy(provider: ProviderProfileEntity) = AiQuotaPolicy(
        providerId = provider.providerId,
        modelProfileId = MODEL_ID,
        global = AiQuotaLimit(4, 100, 1_000),
        provider = AiQuotaLimit(
            provider.maxConcurrency,
            provider.requestsPerMinute,
            provider.requestsPerDay,
        ),
        model = AiQuotaLimit(2, 100, 100),
    )

    private class FakeCredentialStore : AiCredentialStore {
        var secret: CharArray? = null
        var lastReturned: CharArray? = null

        override fun read(credentialId: String): CredentialReadResult {
            val value = secret?.copyOf() ?: return CredentialReadResult.Missing
            lastReturned = value
            return CredentialReadResult.Available(value)
        }

        override fun put(credentialId: String, secret: CharArray) = Unit

        override fun delete(credentialId: String) = false

        override fun resetAll() = Unit
    }

    private class InMemoryQuotaStore : AiQuotaStateStore {
        private val values = mutableMapOf<String, PersistentQuotaUsage>()

        override fun load(scopeKey: String): PersistentQuotaUsage? = values[scopeKey]

        override fun save(usages: Collection<PersistentQuotaUsage>): Boolean {
            usages.forEach { values[it.scopeKey] = it }
            return true
        }
    }

    private companion object {
        const val PROVIDER_ID = "provider"
        const val MODEL_ID = "model"
        const val CREDENTIAL_ID = "credential"
        const val NOW = 120_000L
    }
}

package cn.soul2.imageai.ai.protocol

import android.app.Application
import cn.soul2.imageai.ai.analysis.AiModelInvocation
import cn.soul2.imageai.ai.analysis.ResolvedAiAnalysisConfiguration
import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.ai.credential.CredentialReadResult
import cn.soul2.imageai.ai.image.PreparedImage
import cn.soul2.imageai.ai.quota.AiQuotaCoordinator
import cn.soul2.imageai.ai.quota.AiQuotaPolicy
import cn.soul2.imageai.ai.quota.AiQuotaStateStore
import cn.soul2.imageai.ai.quota.PersistentQuotaUsage
import cn.soul2.imageai.ai.transport.SecureAiHttpTransport
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomJsonAiModelClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun executesRenderedRequestAndExtractsCanonicalPayload() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"result":{
                  "caption":"一张测试图片",
                  "tags":[{"value":"测试","confidence":0.9}],
                  "categories":[],
                  "search_tokens":["fixture"]
                }}
                """.trimIndent(),
            ),
        )
        val invocation = invocation()
        val client = CustomJsonAiModelClient(
            SecureAiHttpTransport(
                credentialStore = NoCredentialStore,
                quotaCoordinator = AiQuotaCoordinator(InMemoryQuotaStore()) { 120_000L },
            ),
        )

        val payload = client.analyze(invocation)

        assertEquals("一张测试图片", payload.caption)
        assertEquals("测试", payload.tags.single().value)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/v1/analyze", request?.path)
        val body = JSONObject(request?.body?.readUtf8().orEmpty())
        assertEquals("vision-model", body.getString("model"))
        assertEquals("data:image/jpeg;base64,AQID", body.getString("image"))
        assertEquals("object", body.getJSONObject("schema").getString("type"))
    }

    private fun invocation(): AiModelInvocation {
        val provider = ProviderProfileEntity(
            providerId = "provider",
            displayName = "自定义供应方",
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
            authMode = ProviderAuthMode.NONE,
            authHeaderName = null,
            authPrefix = null,
            credentialId = null,
            headersJson = "{}",
            allowedRedirectOriginsJson = "[]",
            cleartextApproved = true,
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 5_000,
            writeTimeoutMillis = 5_000,
            maxConcurrency = 2,
            requestsPerMinute = 10,
            requestsPerDay = 100,
            enabled = true,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val model = ModelProfileEntity(
            modelProfileId = "model",
            providerId = provider.providerId,
            displayName = "模型",
            modelId = "vision-model",
            protocolType = ModelProtocolType.CUSTOM_JSON,
            protocolDefinitionId = "protocol",
            supportsVision = true,
            maxOutputTokens = 2_048,
            temperature = 0.2,
            maxImageEdge = 1_600,
            maxImageBytes = 1_500_000,
            maxConcurrency = 1,
            requestsPerMinute = 10,
            requestsPerDay = 100,
            enabled = true,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val runtime = AiRuntimeSettingEntity(
            defaultModelProfileId = model.modelProfileId,
            globalMaxConcurrency = 2,
            globalRequestsPerMinute = 20,
            globalRequestsPerDay = 200,
            promptText = "请分析图片",
            updatedAtEpochMillis = 1L,
        )
        return AiModelInvocation(
            configuration = ResolvedAiAnalysisConfiguration(
                runtime,
                provider,
                model,
                ProtocolDefinitionEntity(
                    protocolDefinitionId = "protocol",
                    displayName = "测试协议",
                    definitionJson = CustomJsonProtocolDefinitionTest.DEFINITION,
                    enabled = true,
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                ),
            ),
            quotaPolicy = AiQuotaPolicy.from(runtime, provider, model),
            image = PreparedImage(byteArrayOf(1, 2, 3), "image/jpeg", 2, 2),
        )
    }

    private data object NoCredentialStore : AiCredentialStore {
        override fun read(credentialId: String) = CredentialReadResult.Missing
        override fun put(credentialId: String, secret: CharArray) = Unit
        override fun delete(credentialId: String) = false
        override fun resetAll() = Unit
    }

    private class InMemoryQuotaStore : AiQuotaStateStore {
        private val values = mutableMapOf<String, PersistentQuotaUsage>()
        override fun load(scopeKey: String) = values[scopeKey]
        override fun save(usages: Collection<PersistentQuotaUsage>): Boolean {
            usages.forEach { values[it.scopeKey] = it }
            return true
        }
    }
}

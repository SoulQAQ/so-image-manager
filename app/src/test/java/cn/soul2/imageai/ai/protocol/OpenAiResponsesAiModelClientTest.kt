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
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenAiResponsesAiModelClientTest {
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
    fun sendsImageWithStrictSchemaAndParsesOutputText() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"output":[{"type":"message","content":[{"type":"output_text","text":"{\"caption\":\"海边日落\",\"tags\":[{\"value\":\"日落\",\"confidence\":0.95}],\"categories\":[],\"search_tokens\":[\"海边\"]}"}]}]}
                """.trimIndent(),
            ),
        )
        val client = OpenAiResponsesAiModelClient(transport())

        val payload = client.analyze(invocation())

        assertEquals("海边日落", payload.caption)
        assertEquals("日落", payload.tags.single().value)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/v1/responses", request?.path)
        val body = JSONObject(request?.body?.readUtf8().orEmpty())
        assertEquals("vision-model", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,AQID", content.getJSONObject(1).getString("image_url"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals(true, format.getBoolean("strict"))
        assertEquals("object", format.getJSONObject("schema").getString("type"))
    }

    private fun transport() = SecureAiHttpTransport(
        credentialStore = NoCredentialStore,
        quotaCoordinator = AiQuotaCoordinator(InMemoryQuotaStore()) { 120_000L },
    )

    private fun invocation(): AiModelInvocation {
        val provider = ProviderProfileEntity(
            providerId = "provider",
            displayName = "OpenAI",
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
            protocolType = ModelProtocolType.OPENAI_RESPONSES,
            protocolDefinitionId = null,
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
            configuration = ResolvedAiAnalysisConfiguration(runtime, provider, model, null),
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

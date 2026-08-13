package cn.soul2.imageai.ai.protocol

import android.app.Application
import cn.soul2.imageai.ai.analysis.AiModelInvocation
import cn.soul2.imageai.ai.analysis.ResolvedAiAnalysisConfiguration
import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.ai.credential.CredentialReadResult
import cn.soul2.imageai.ai.image.PreparedImage
import cn.soul2.imageai.ai.quota.*
import cn.soul2.imageai.ai.transport.SecureAiHttpTransport
import cn.soul2.imageai.data.db.entity.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PresetAiModelClientsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun chatCompletionsUsesVisionMessageAndParsesContent() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":${JSONObject.quote(PAYLOAD)}}}],"usage":{"prompt_tokens":12,"completion_tokens":8}}"""))
        val result = OpenAiChatCompletionsAiModelClient(transport()).analyze(invocation(ModelProtocolType.OPENAI_CHAT_COMPLETIONS))
        assertEquals("海边日落", result.caption)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertEquals("image_url", body.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(1).getString("type"))
    }

    @Test fun anthropicUsesMessagesEndpointAndVersionHeader() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":${JSONObject.quote(PAYLOAD)}}],"usage":{"input_tokens":10,"output_tokens":7}}"""))
        val result = AnthropicMessagesAiModelClient(transport()).analyze(invocation(ModelProtocolType.ANTHROPIC_MESSAGES))
        assertEquals("海边日落", result.caption)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/messages", request.path)
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertEquals("image", JSONObject(request.body.readUtf8()).getJSONArray("messages")
            .getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test fun geminiUsesModelEndpointAndJsonSchema() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":${JSONObject.quote(PAYLOAD)}}]}}],"usageMetadata":{"totalTokenCount":21}}"""))
        val result = GeminiGenerateContentAiModelClient(transport()).analyze(invocation(ModelProtocolType.GEMINI_GENERATE_CONTENT))
        assertEquals("海边日落", result.caption)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/models/vision-model:generateContent", request.path)
        val generation = JSONObject(request.body.readUtf8()).getJSONObject("generationConfig")
        assertEquals("application/json", generation.getString("responseMimeType"))
        assertEquals("object", generation.getJSONObject("responseJsonSchema").getString("type"))
    }

    private fun transport() = SecureAiHttpTransport(NoCredentialStore, AiQuotaCoordinator(InMemoryQuotaStore()) { 120_000L })

    private fun invocation(type: ModelProtocolType): AiModelInvocation {
        val provider = ProviderProfileEntity(
            "provider", "供应方", server.url("/v1").toString().removeSuffix("/"),
            ProviderAuthMode.NONE, null, null, null, "{}", "[]", true,
            5_000, 5_000, 5_000, 2, 20, 200, true, 1L, 1L,
        )
        val model = ModelProfileEntity(
            "model", "provider", "模型", "vision-model", type, null, true,
            2_048, 0.2, 1_600, 1_500_000, 1, 10, 100, true, 1L, 1L,
        )
        val runtime = AiRuntimeSettingEntity(
            defaultModelProfileId = "model", globalMaxConcurrency = 2,
            globalRequestsPerMinute = 20, globalRequestsPerDay = 200,
            promptText = "请分析图片", updatedAtEpochMillis = 1L,
        )
        return AiModelInvocation(
            ResolvedAiAnalysisConfiguration(runtime, provider, model, null),
            AiQuotaPolicy.from(runtime, provider, model), PreparedImage(byteArrayOf(1, 2, 3), "image/jpeg", 2, 2),
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
        override fun save(usages: Collection<PersistentQuotaUsage>) = true.also { usages.forEach { values[it.scopeKey] = it } }
    }
    private companion object {
        const val PAYLOAD = """{"caption":"海边日落","tags":[{"value":"日落","confidence":0.95}],"categories":[],"search_tokens":["海边"]}"""
    }
}

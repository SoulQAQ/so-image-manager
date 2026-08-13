package cn.soul2.imageai.ai.debug

import cn.soul2.imageai.ai.analysis.*
import cn.soul2.imageai.ai.image.ImagePreprocessor
import cn.soul2.imageai.ai.quota.AiQuotaPolicy
import cn.soul2.imageai.ai.transport.AiHttpTrace
import cn.soul2.imageai.data.db.entity.ImagePartition

data class AiProtocolDebugReport(
    val requestSummary: String,
    val responsePreview: String,
    val mappedOutput: String,
)

class AiProtocolDebugger(
    private val resolver: AiAnalysisConfigurationResolver,
    private val preprocessor: ImagePreprocessor,
    private val clients: AiModelClientRegistry,
) {
    suspend fun test(providerId: String, contentUri: String, partition: ImagePartition): AiProtocolDebugReport {
        val configuration = resolver.resolveCandidates(partition)
            .firstOrNull { it.provider.providerId == providerId }
            ?: throw IllegalArgumentException("当前分区找不到已启用的模型配置")
        val image = preprocessor.prepare(
            contentUri, configuration.model.maxImageEdge, configuration.model.maxImageBytes,
        )
        var trace: AiHttpTrace? = null
        try {
            val payload = clients.require(configuration.model.protocolType).analyze(
                AiModelInvocation(
                    configuration, AiQuotaPolicy.from(configuration.runtime, configuration.provider, configuration.model),
                    image, traceSink = { trace = it },
                ),
            )
            val captured = trace
            return AiProtocolDebugReport(
                requestSummary = captured?.let {
                    "${it.method} ${it.url}\nHeaders: ${it.headerNames.joinToString()}\n${it.redactedRequestBody.orEmpty()}"
                }.orEmpty(),
                responsePreview = captured?.let { "HTTP ${it.statusCode}\n${it.responseBody}" }.orEmpty(),
                mappedOutput = buildString {
                    appendLine("描述：${payload.caption}")
                    appendLine("标签：${payload.tags.joinToString { it.value }}")
                    appendLine("分类：${payload.categories.joinToString { it.value }}")
                    append("搜索词：${payload.searchTokens.joinToString()}")
                },
            )
        } catch (error: Exception) {
            val captured = trace
            val detail = captured?.let { "\nHTTP ${it.statusCode}\n${it.responseBody}" }.orEmpty()
            throw IllegalStateException("协议测试失败：${error.message.orEmpty()}$detail", error)
        } finally {
            image.bytes.fill(0)
        }
    }
}

package cn.soul2.imageai.data.api.model

/**
 * AI分析结果
 */
data class AiAnalysisResult(
    val caption: String,
    val categories: List<String>,
    val tags: List<Tag>,
    val searchTokens: List<String>,
    val ocr: List<String>,
    val nsfw: Boolean,
    val quality: Double
) {
    data class Tag(
        val name: String,
        val score: Float
    )
}

/**
 * AI请求配置
 */
data class AiRequestConfig(
    val model: String = "gpt-4o",
    val promptVersion: String = "v1",
    val schemaVersion: String = "v1.2"
)

/**
 * 完整的AI分析响应（包含元数据）
 */
data class FullAiResult(
    val imageId: Long,
    val result: AiAnalysisResult,
    val aiModel: String,
    val promptVersion: String,
    val schemaVersion: String,
    val analyzedAt: Long
)
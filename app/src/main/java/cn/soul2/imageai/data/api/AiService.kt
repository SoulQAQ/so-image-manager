package cn.soul2.imageai.data.api

import android.net.Uri
import android.util.Base64
import cn.soul2.imageai.data.api.model.AiAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * AI服务接口
 * 封装AI API调用逻辑
 */
class AiService(private val httpClient: OkHttpClient = AiHttpClient.client) {

    companion object {
        private const val MEDIA_TYPE_JSON = "application/json; charset=utf-8"
        private val VISION_MODEL get() = AiHttpClient.model

        // Prompt模板
        private const val ANALYSIS_PROMPT = """
分析这张图片，返回以下JSON结构（不要输出其他内容）：
{
  "caption": "简短描述（20字内）",
  "categories": ["分类路径1", "分类路径2"],
  "tags": [{"name": "标签名", "score": 0.95}],
  "search_tokens": ["关键词1", "关键词2"],
  "ocr": [],
  "safety": {"nsfw": false},
  "score": {"quality": 0.9}
}

分类路径从以下选择：
- 真实照片/风景
- 真实照片/人像
- 真实照片/美食
- 真实照片/建筑
- 截图/聊天记录
- 截图/网页
- 漫画/插画
- 图表/信息图
- 表情包/梗图
- 其他

标签尽量全面，包括主体、场景、颜色、情感等。
search_tokens用于搜索，提取最重要5-10个关键词。
"""
    }

    /**
     * 分析图片
     * @param imageUri 图片URI
     * @param imageData 压缩后的图片数据
     * @return AI分析结果
     */
    suspend fun analyzeImage(
        imageUri: Uri,
        imageData: ByteArray,
        mimeType: String = "image/jpeg"
    ): Result<AiAnalysisResult> = withContext(Dispatchers.IO) {
        try {
            val base64Image = Base64.encodeToString(imageData, Base64.NO_WRAP)
            val imageUrl = "data:$mimeType;base64,$base64Image"

            val requestBody = createVisionRequestBody(imageUrl)

            val request = Request.Builder()
                .url("${AiHttpClient.getBaseUrl()}/chat/completions")
                .post(requestBody.toRequestBody(MEDIA_TYPE_JSON.toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("API request failed: ${response.code} - ${response.message}")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            val analysisResult = parseAnalysisResponse(responseBody)
            Result.success(analysisResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 分析图片（从URI读取）
     */
    suspend fun analyzeImageFromUri(
        imageUri: Uri,
        imageData: ByteArray,
        mimeType: String = "image/jpeg",
        model: String = VISION_MODEL
    ): Result<AiAnalysisResult> {
        return analyzeImage(imageUri, imageData, mimeType)
    }

    private fun createVisionRequestBody(imageUrl: String): String {
        val request = JSONObject().apply {
            put("model", VISION_MODEL)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", ANALYSIS_PROMPT.trim())
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageUrl)
                                put("detail", "low")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 1000)
            put("temperature", 0.3)
        }
        return request.toString()
    }

    private fun parseAnalysisResponse(responseBody: String): AiAnalysisResult {
        val json = JSONObject(responseBody)
        val choices = json.getJSONArray("choices")
        val firstChoice = choices.getJSONObject(0)
        val message = firstChoice.getJSONObject("message")
        val content = message.getString("content")

        // 尝试从content中提取JSON
        val jsonContent = extractJsonFromContent(content)
        return parseAnalysisResult(jsonContent)
    }

    private fun extractJsonFromContent(content: String): String {
        // 尝试找到JSON对象
        val startIndex = content.indexOf('{')
        val endIndex = content.lastIndexOf('}')
        return if (startIndex >= 0 && endIndex > startIndex) {
            content.substring(startIndex, endIndex + 1)
        } else {
            content
        }
    }

    private fun parseAnalysisResult(jsonString: String): AiAnalysisResult {
        val json = JSONObject(jsonString)

        return AiAnalysisResult(
            caption = json.optString("caption", ""),
            categories = parseStringArray(json.optJSONArray("categories")),
            tags = parseTags(json.optJSONArray("tags")),
            searchTokens = parseStringArray(json.optJSONArray("search_tokens")),
            ocr = parseStringArray(json.optJSONArray("ocr")),
            nsfw = json.optJSONObject("safety")?.optBoolean("nsfw", false) ?: false,
            quality = json.optJSONObject("score")?.optDouble("quality", 0.0) ?: 0.0
        )
    }

    private fun parseStringArray(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            result.add(array.getString(i))
        }
        return result
    }

    private fun parseTags(array: org.json.JSONArray?): List<AiAnalysisResult.Tag> {
        if (array == null) return emptyList()
        val result = mutableListOf<AiAnalysisResult.Tag>()
        for (i in 0 until array.length()) {
            val tagJson = array.getJSONObject(i)
            result.add(
                AiAnalysisResult.Tag(
                    name = tagJson.getString("name"),
                    score = tagJson.getDouble("score").toFloat()
                )
            )
        }
        return result
    }
}

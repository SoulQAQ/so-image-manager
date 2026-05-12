package cn.soul2.imageai.domain.service

import android.content.Context
import android.net.Uri
import cn.soul2.imageai.data.api.AiService
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.dao.ImageTagWithDetails
import cn.soul2.imageai.data.db.dao.SearchResult
import cn.soul2.imageai.data.db.entity.ImageQueryCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 搜索服务
 * 封装文本检索和看图查标签功能
 */
class SearchService(private val context: Context) {

    private val database: AppDatabase = AppDatabase.getInstance(context)
    private val imageDao = database.imageDao()
    private val tagDao = database.tagDao()
    private val imageTagDao = database.imageTagDao()
    private val searchDao = database.searchDao()
    private val preprocessor = ImagePreprocessor(context)
    private val aiService = AiService()
    private val aiResultProcessor = AiResultProcessor(context)

    /**
     * 文本检索
     * @param query 搜索关键词（多个关键词用空格分隔）
     * @param limit 返回结果数量限制
     */
    suspend fun searchByText(query: String, limit: Int = 100): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 将关键词进行 canonical 化
        val keywords = query.split("\\s+".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { tagDao.canonicalizeTag(it) }

        if (keywords.isEmpty()) return@withContext emptyList()

        // 构建 FTS5 查询（AND 查询）
        val ftsQuery = keywords.joinToString(" ")
        searchDao.searchByText(ftsQuery, limit)
    }

    /**
     * 看图查标签（库内）
     */
    suspend fun getImageTags(imageId: Long): ImageTagsResult = withContext(Dispatchers.IO) {
        val allTags = imageTagDao.getTagsForImage(imageId)
        val aiTags = allTags.filter { !it.isUserTag }
        val userTags = allTags.filter { it.isUserTag }

        val image = imageDao.getById(imageId)

        ImageTagsResult(
            imageId = imageId,
            uri = image?.uri,
            sha256 = image?.sha256,
            aiTags = aiTags.map { TagInfo(it.tagName, it.confidence) },
            userTags = userTags.map { TagInfo(it.tagName, it.confidence) }
        )
    }

    /**
     * 看图查标签（外部临时图片）
     * 先查缓存，缓存未命中则调用AI分析
     */
    suspend fun queryExternalImage(uri: Uri): ExternalImageResult = withContext(Dispatchers.IO) {
        // 计算 SHA256
        val sha256 = try {
            preprocessor.calculateSha256(uri)
        } catch (e: Exception) {
            return@withContext ExternalImageResult.Error("Failed to calculate image hash")
        }

        // 查缓存
        val now = System.currentTimeMillis()
        val cache = searchDao.getValidCache(sha256, now)

        if (cache != null) {
            return@withContext ExternalImageResult.Cached(
                sha256 = sha256,
                resultJson = cache.resultJson,
                cached = true
            )
        }

        // 调用AI分析
        val imageData = preprocessor.compressImage(uri)
        if (imageData == null) {
            return@withContext ExternalImageResult.Error("Failed to process image")
        }

        val result = aiService.analyzeImage(uri, imageData)
        if (result.isFailure) {
            return@withContext ExternalImageResult.Error(result.exceptionOrNull()?.message ?: "AI analysis failed")
        }

        val aiResult = result.getOrThrow()

        // 存入缓存（24小时有效）
        val cacheEntity = ImageQueryCacheEntity(
            sha256 = sha256,
            aiModel = "gpt-4o",
            schemaVersion = "v1.2",
            resultJson = serializeAiResult(aiResult),
            createdAt = now,
            expireAt = now + 24 * 60 * 60 * 1000
        )
        searchDao.insertCache(cacheEntity)

        ExternalImageResult.Analyzed(
            sha256 = sha256,
            caption = aiResult.caption,
            tags = aiResult.tags.map { TagInfo(it.name, it.score) },
            categories = aiResult.categories,
            cached = false
        )
    }

    /**
     * 保存外部图片入库
     */
    suspend fun saveExternalImage(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            // 扫描图片信息
            val scanner = ImageScanner(context)
            val scannedImage = scanner.scanSingleImage(uri)
            if (scannedImage == null) {
                return@withContext Result.failure(Exception("Failed to scan image"))
            }

            // 入队
            val queue = ImageProcessQueue(context, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
            val imageId = queue.enqueueSingleImage(scannedImage)

            if (imageId == null) {
                // 可能已存在
                val sha256 = preprocessor.calculateSha256(uri)
                val existing = imageDao.getBySha256(sha256)
                if (existing != null) {
                    return@withContext Result.success(existing.id)
                }
                return@withContext Result.failure(Exception("Failed to enqueue image"))
            }

            Result.success(imageId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 用户修正标签
     */
    suspend fun saveUserTags(imageId: Long, tags: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            aiResultProcessor.saveUserTags(imageId, tags)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun serializeAiResult(result: cn.soul2.imageai.data.api.model.AiAnalysisResult): String {
        return org.json.JSONObject().apply {
            put("caption", result.caption)
            put("categories", org.json.JSONArray(result.categories))
            put("tags", org.json.JSONArray().apply {
                result.tags.forEach { tag ->
                    put(org.json.JSONObject().apply {
                        put("name", tag.name)
                        put("score", tag.score)
                    })
                }
            })
            put("search_tokens", org.json.JSONArray(result.searchTokens))
        }.toString()
    }
}

/**
 * 图片标签结果
 */
data class ImageTagsResult(
    val imageId: Long,
    val uri: String?,
    val sha256: String?,
    val aiTags: List<TagInfo>,
    val userTags: List<TagInfo>
)

/**
 * 标签信息
 */
data class TagInfo(
    val name: String,
    val confidence: Float
)

/**
 * 外部图片查询结果
 */
sealed class ExternalImageResult {
    data class Cached(
        val sha256: String,
        val resultJson: String,
        val cached: Boolean
    ) : ExternalImageResult()

    data class Analyzed(
        val sha256: String,
        val caption: String,
        val tags: List<TagInfo>,
        val categories: List<String>,
        val cached: Boolean
    ) : ExternalImageResult()

    data class Error(val message: String) : ExternalImageResult()
}

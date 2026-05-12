package cn.soul2.imageai.domain.service

import android.content.Context
import cn.soul2.imageai.data.api.AiService
import cn.soul2.imageai.data.api.model.AiAnalysisResult
import cn.soul2.imageai.data.api.model.AiRequestConfig
import cn.soul2.imageai.data.api.model.FullAiResult
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.ImageAiEntity
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImageTagEntity
import cn.soul2.imageai.data.db.entity.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI结果处理器
 * 负责将AI分析结果解析并写入数据库
 */
class AiResultProcessor(private val context: Context) {

    private val database: AppDatabase = AppDatabase.getInstance(context)
    private val imageDao = database.imageDao()
    private val imageAiDao = database.imageAiDao()
    private val tagDao = database.tagDao()
    private val imageTagDao = database.imageTagDao()
    private val searchDao = database.searchDao()

    private val preprocessor = ImagePreprocessor(context)
    private val aiService = AiService()

    /**
     * 处理单张图片的AI分析
     */
    suspend fun processImage(
        image: ImageEntity,
        config: AiRequestConfig = AiRequestConfig()
    ): Result<FullAiResult> = withContext(Dispatchers.IO) {
        try {
            // 获取压缩后的图片数据
            val compressedData = preprocessor.compressImage(android.net.Uri.parse(image.uri))
            if (compressedData == null) {
                return@withContext Result.failure(Exception("Failed to compress image"))
            }

            // 调用AI分析
            val aiResult = aiService.analyzeImage(
                imageUri = android.net.Uri.parse(image.uri),
                imageData = compressedData,
                mimeType = image.mimeType ?: "image/jpeg"
            ).getOrThrow()

            // 写入数据库
            val fullResult = FullAiResult(
                imageId = image.id,
                result = aiResult,
                aiModel = config.model,
                promptVersion = config.promptVersion,
                schemaVersion = config.schemaVersion,
                analyzedAt = System.currentTimeMillis()
            )

            saveResultToDatabase(image.id, fullResult)

            Result.success(fullResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 将AI结果保存到数据库
     */
    suspend fun saveResultToDatabase(imageId: Long, result: FullAiResult) = withContext(Dispatchers.IO) {
        // 保存AI结果到 image_ai 表
        val imageAiEntity = ImageAiEntity(
            imageId = imageId,
            aiCaption = result.result.caption,
            aiTagsJson = tagsToJsonString(result.result.tags),
            aiCategoriesJson = listToJsonString(result.result.categories),
            aiSearchTokens = result.result.searchTokens.joinToString(" "),
            userCaption = null,
            userTagsJson = null,
            userCategoriesJson = null,
            ocrText = result.result.ocr.joinToString("\n"),
            rawJson = null
        )
        imageAiDao.upsert(imageAiEntity)

        // 处理标签
        val tagIds = mutableListOf<Pair<Long, Float>>()
        result.result.tags.forEach { tag ->
            // canonical化标签名
            val canonicalName = tagDao.canonicalizeTag(tag.name)
            val tagId = tagDao.getOrInsertTag(canonicalName)
            tagIds.add(tagId to tag.score)
        }

        // 保存图片-标签关联
        imageTagDao.setImageTags(imageId, tagIds, isUserTag = false)

        // 更新FTS索引
        searchDao.syncFtsForImage(imageId, result.result.searchTokens.joinToString(" "))

        // 更新图片状态
        imageDao.updateAiResult(
            imageId = imageId,
            status = ImageEntity.AI_STATUS_DONE,
            model = result.aiModel,
            promptVersion = result.promptVersion,
            schemaVersion = result.schemaVersion,
            analyzedAt = result.analyzedAt
        )
    }

    /**
     * 保存用户修正的标签
     */
    suspend fun saveUserTags(imageId: Long, tags: List<String>) = withContext(Dispatchers.IO) {
        val tagIds = tags.map { tagName ->
            val canonicalName = tagDao.canonicalizeTag(tagName)
            tagDao.getOrInsertTag(canonicalName) to 1.0f
        }

        imageTagDao.setImageTags(imageId, tagIds, isUserTag = true)

        // 更新用户修改标记
        val image = imageDao.getById(imageId)
        if (image != null) {
            imageDao.update(image.copy(userModified = 1))
        }
    }

    private fun tagsToJsonString(tags: List<AiAnalysisResult.Tag>): String {
        val array = JSONArray()
        tags.forEach { tag ->
            array.put(JSONObject().apply {
                put("name", tag.name)
                put("score", tag.score)
            })
        }
        return array.toString()
    }

    private fun listToJsonString(list: List<String>): String {
        val array = JSONArray()
        list.forEach { array.put(it) }
        return array.toString()
    }
}

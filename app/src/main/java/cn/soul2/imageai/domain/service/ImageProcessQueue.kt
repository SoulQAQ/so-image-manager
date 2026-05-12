package cn.soul2.imageai.domain.service

import android.content.Context
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 图片处理队列
 * 负责管理图片入库、去重、后台处理
 */
class ImageProcessQueue(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val database: AppDatabase = AppDatabase.getInstance(context)
    private val imageDao = database.imageDao()
    private val preprocessor = ImagePreprocessor(context)

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val _processingCount = MutableStateFlow(0)
    val processingCount: StateFlow<Int> = _processingCount.asStateFlow()

    private var processingJob: Job? = null

    /**
     * 将扫描到的图片批量入队
     */
    suspend fun enqueueImages(scannedImages: List<ScannedImage>): EnqueueResult = withContext(Dispatchers.IO) {
        val importTime = System.currentTimeMillis()
        var added = 0
        var skipped = 0
        val pendingIds = mutableListOf<Long>()

        scannedImages.forEach { scanned ->
            // 计算SHA256
            val sha256 = try {
                preprocessor.calculateSha256(scanned.uri)
            } catch (e: Exception) {
                null
            }

            if (sha256.isNullOrBlank()) {
                skipped++
                return@forEach
            }

            // 检查是否已存在（去重）
            val existing = imageDao.getBySha256(sha256)
            if (existing != null) {
                skipped++
                return@forEach
            }

            // 创建实体
            val entity = ImageEntity(
                uri = scanned.uri.toString(),
                path = scanned.path,
                sha256 = sha256,
                width = scanned.width,
                height = scanned.height,
                mimeType = scanned.mimeType,
                fileSize = scanned.size,
                createTime = scanned.createTime,
                modifyTime = scanned.modifyTime,
                importTime = importTime,
                aiStatus = ImageEntity.AI_STATUS_PENDING
            )

            // 插入数据库
            val id = imageDao.insert(entity)
            pendingIds.add(id)
            added++
        }

        _queueSize.value += pendingIds.size

        EnqueueResult(
            addedCount = added,
            skippedCount = skipped,
            pendingImageIds = pendingIds
        )
    }

    /**
     * 单张图片入队
     */
    suspend fun enqueueSingleImage(scannedImage: ScannedImage): Long? = withContext(Dispatchers.IO) {
        val sha256 = try {
            preprocessor.calculateSha256(scannedImage.uri)
        } catch (e: Exception) {
            return@withContext null
        }

        // 检查去重
        val existing = imageDao.getBySha256(sha256)
        if (existing != null) {
            return@withContext existing.id
        }

        val importTime = System.currentTimeMillis()
        val entity = ImageEntity(
            uri = scannedImage.uri.toString(),
            path = scannedImage.path,
            sha256 = sha256,
            width = scannedImage.width,
            height = scannedImage.height,
            mimeType = scannedImage.mimeType,
            fileSize = scannedImage.size,
            createTime = scannedImage.createTime,
            modifyTime = scannedImage.modifyTime,
            importTime = importTime,
            aiStatus = ImageEntity.AI_STATUS_PENDING
        )

        val id = imageDao.insert(entity)
        _queueSize.value += 1
        id
    }

    /**
     * 获取待处理的图片数量
     */
    suspend fun getPendingCount(): Int = withContext(Dispatchers.IO) {
        imageDao.getCountByAiStatus(ImageEntity.AI_STATUS_PENDING)
    }

    /**
     * 获取待处理的图片列表
     */
    suspend fun getPendingImages(limit: Int = 50): List<ImageEntity> = withContext(Dispatchers.IO) {
        imageDao.getPendingForAi(limit)
    }

    /**
     * 标记图片为处理中
     */
    suspend fun markAsProcessing(imageId: Long) = withContext(Dispatchers.IO) {
        imageDao.updateAiStatus(imageId, ImageEntity.AI_STATUS_PROCESSING)
        _processingCount.value += 1
    }

    /**
     * 标记图片处理完成
     */
    suspend fun markAsDone(imageId: Long) = withContext(Dispatchers.IO) {
        imageDao.updateAiStatus(imageId, ImageEntity.AI_STATUS_DONE)
        _queueSize.value = maxOf(0, _queueSize.value - 1)
        _processingCount.value = maxOf(0, _processingCount.value - 1)
    }

    /**
     * 标记图片处理失败
     */
    suspend fun markAsFailed(imageId: Long) = withContext(Dispatchers.IO) {
        imageDao.updateAiStatus(imageId, ImageEntity.AI_STATUS_FAILED)
        _processingCount.value = maxOf(0, _processingCount.value - 1)
    }

    /**
     * 停止处理
     */
    fun stopProcessing() {
        processingJob?.cancel()
        processingJob = null
    }
}

/**
 * 入队结果
 */
data class EnqueueResult(
    val addedCount: Int,
    val skippedCount: Int,
    val pendingImageIds: List<Long>
)

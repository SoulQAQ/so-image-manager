package cn.soul2.imageai.domain.service

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media
import cn.soul2.imageai.data.db.entity.ImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImageScanner(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * 全量扫描设备上的所有图片
     */
    suspend fun scanAllImages(): List<ScannedImage> = withContext(Dispatchers.IO) {
        val images = mutableListOf<ScannedImage>()
        val projection = getProjection()

        contentResolver.query(
            Media.EXTERNAL_CONTENT_URI,
            projection,
            buildSelection(),
            buildSelectionArgs(),
            "${Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val image = parseCursor(cursor)
                if (image != null) {
                    images.add(image)
                }
            }
        }

        images
    }

    /**
     * 增量扫描：获取指定时间戳之后新增的图片
     */
    suspend fun scanNewImages(sinceTimestamp: Long): List<ScannedImage> = withContext(Dispatchers.IO) {
        val images = mutableListOf<ScannedImage>()
        val projection = getProjection()

        contentResolver.query(
            Media.EXTERNAL_CONTENT_URI,
            projection,
            "${Media.DATE_ADDED} > ?",
            arrayOf(sinceTimestamp.toString()),
            "${Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val image = parseCursor(cursor)
                if (image != null) {
                    images.add(image)
                }
            }
        }

        images
    }

    /**
     * 获取单个图片信息
     */
    suspend fun scanSingleImage(uri: Uri): ScannedImage? = withContext(Dispatchers.IO) {
        val projection = getProjection()

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                parseCursor(cursor)
            } else {
                null
            }
        }
    }

    private fun getProjection(): Array<String> {
        return arrayOf(
            Media._ID,
            Media.DATA,
            Media.DISPLAY_NAME,
            Media.SIZE,
            Media.MIME_TYPE,
            Media.WIDTH,
            Media.HEIGHT,
            Media.DATE_ADDED,
            Media.DATE_MODIFIED,
            Media.DATE_TAKEN
        )
    }

    private fun buildSelection(): String? {
        // 只扫描图片类型
        return "${Media.MIME_TYPE} LIKE 'image/%'"
    }

    private fun buildSelectionArgs(): Array<String>? = null

    private fun parseCursor(cursor: Cursor): ScannedImage? {
        val idColumn = cursor.getColumnIndexOrThrow(Media._ID)
        val pathColumn = cursor.getColumnIndexOrThrow(Media.DATA)
        val nameColumn = cursor.getColumnIndexOrThrow(Media.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndexOrThrow(Media.SIZE)
        val mimeColumn = cursor.getColumnIndexOrThrow(Media.MIME_TYPE)
        val widthColumn = cursor.getColumnIndexOrThrow(Media.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(Media.HEIGHT)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(Media.DATE_ADDED)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(Media.DATE_MODIFIED)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(Media.DATE_TAKEN)

        val id = cursor.getLong(idColumn)
        val path = cursor.getString(pathColumn)
        val name = cursor.getString(nameColumn)
        val size = cursor.getLong(sizeColumn)
        val mimeType = cursor.getString(mimeColumn)

        // 跳过不存在的文件
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null

        val width = cursor.getInt(widthColumn).takeIf { it > 0 }
        val height = cursor.getInt(heightColumn).takeIf { it > 0 }

        val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // 转为毫秒
        val dateModified = cursor.getLong(dateModifiedColumn) * 1000
        val dateTaken = cursor.getLong(dateTakenColumn).takeIf { it > 0 } ?: dateAdded

        val uri = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id)

        return ScannedImage(
            uri = uri,
            path = path,
            name = name,
            size = size,
            mimeType = mimeType,
            width = width,
            height = height,
            createTime = dateTaken,
            modifyTime = dateModified,
            addedTime = dateAdded
        )
    }

    /**
     * 获取图片总数
     */
    suspend fun getImageCount(): Int = withContext(Dispatchers.IO) {
        var count = 0
        contentResolver.query(
            Media.EXTERNAL_CONTENT_URI,
            arrayOf(Media._ID),
            buildSelection(),
            null,
            null
        )?.use { cursor ->
            count = cursor.count
        }
        count
    }
}

/**
 * 扫描到的图片信息
 */
data class ScannedImage(
    val uri: Uri,
    val path: String?,
    val name: String,
    val size: Long,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val createTime: Long?,
    val modifyTime: Long?,
    val addedTime: Long
)

/**
 * 将 ScannedImage 转换为 ImageEntity
 */
fun ScannedImage.toEntity(importTime: Long, sha256: String): ImageEntity {
    return ImageEntity(
        uri = uri.toString(),
        path = path,
        sha256 = sha256,
        width = width,
        height = height,
        mimeType = mimeType,
        fileSize = size,
        createTime = createTime,
        modifyTime = modifyTime,
        importTime = importTime,
        aiStatus = ImageEntity.AI_STATUS_PENDING
    )
}

package cn.soul2.imageai.domain.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

class ImagePreprocessor(private val context: Context) {

    companion object {
        const val MAX_COMPRESSED_SIZE = 512 * 1024 // 512KB
        const val MAX_DIMENSION = 1024 // 最大边长
    }

    /**
     * 计算文件的 SHA256 哈希值
     */
    suspend fun calculateSha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算文件的 SHA256 哈希值（本地文件路径）
     */
    suspend fun calculateSha256FromFile(path: String): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 获取图片的 EXIF 旋转角度
     */
    suspend fun getExifRotation(uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.media.ExifInterface(inputStream ?: return@withContext 0)
            } else {
                val path = getRealPathFromUri(uri) ?: return@withContext 0
                android.media.ExifInterface(path)
            }
            inputStream?.close()

            when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 压缩图片并返回字节数组
     */
    suspend fun compressImage(uri: Uri, maxSizeBytes: Int = MAX_COMPRESSED_SIZE): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val rotation = getExifRotation(uri)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null

            // 先读取图片尺寸
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // 计算采样率
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight)

            // 重新读取并采样
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val newInputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            var bitmap = BitmapFactory.decodeStream(newInputStream, null, decodeOptions)
            newInputStream.close()

            if (bitmap == null) return@withContext null

            // 应用旋转
            if (rotation != 0) {
                bitmap = rotateBitmap(bitmap, rotation)
            }

            // 压缩到目标大小
            compressToSize(bitmap, maxSizeBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取预处理后的图片（压缩+旋转纠正）
     */
    suspend fun preprocessImage(uri: Uri): PreprocessedImage? = withContext(Dispatchers.IO) {
        try {
            val sha256 = calculateSha256(uri)
            val rotation = getExifRotation(uri)
            val compressedBytes = compressImage(uri)

            PreprocessedImage(
                sha256 = sha256,
                rotation = rotation,
                compressedBytes = compressedBytes
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val maxDimension = maxOf(width, height)

        while (maxDimension / sampleSize > MAX_DIMENSION) {
            sampleSize *= 2
        }

        return sampleSize
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun compressToSize(bitmap: Bitmap, maxSizeBytes: Int): ByteArray {
        var quality = 90
        var compressedBytes: ByteArray

        do {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            compressedBytes = outputStream.toByteArray()
            quality -= 10
        } while (compressedBytes.size > maxSizeBytes && quality > 10)

        return compressedBytes
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }
}

/**
 * 预处理后的图片信息
 */
data class PreprocessedImage(
    val sha256: String,
    val rotation: Int,
    val compressedBytes: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PreprocessedImage
        return sha256 == other.sha256
    }

    override fun hashCode(): Int = sha256.hashCode()
}

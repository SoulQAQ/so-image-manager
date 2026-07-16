package cn.soul2.imageai.ai.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import cn.soul2.imageai.ai.config.AiConfigurationLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

class ContentImagePreprocessor internal constructor(
    private val contentResolver: ContentResolver,
    private val encoder: JpegImageEncoder = JpegImageEncoder(),
) {
    constructor(contentResolver: ContentResolver) : this(contentResolver, JpegImageEncoder())

    suspend fun prepare(
        contentUri: String,
        maxEdge: Int,
        maxBytes: Int,
    ): PreparedImage = withContext(Dispatchers.IO) {
        validateLimits(maxEdge, maxBytes)
        coroutineContext.ensureActive()
        val uri = try {
            Uri.parse(contentUri)
        } catch (error: RuntimeException) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE, error)
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE)
        }
        val source = try {
            ImageDecoder.createSource(contentResolver, uri)
        } catch (error: RuntimeException) {
            throw ImagePreparationException(ImagePreparationFailure.SOURCE_UNAVAILABLE, error)
        }
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                val sourceEdge = max(info.size.width, info.size.height)
                if (sourceEdge > maxEdge) {
                    val scale = maxEdge.toDouble() / sourceEdge
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (error: Exception) {
            throw ImagePreparationException(ImagePreparationFailure.DECODE_FAILED, error)
        } catch (error: OutOfMemoryError) {
            throw ImagePreparationException(ImagePreparationFailure.DECODE_FAILED, error)
        }
        try {
            coroutineContext.ensureActive()
            encoder.encode(bitmap, maxEdge, maxBytes)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun validateLimits(maxEdge: Int, maxBytes: Int) {
        if (maxEdge !in AiConfigurationLimits.MIN_IMAGE_EDGE..AiConfigurationLimits.MAX_IMAGE_EDGE) {
            throw IllegalArgumentException("maxEdge is outside the supported range")
        }
        if (maxBytes !in AiConfigurationLimits.MIN_IMAGE_BYTES..AiConfigurationLimits.MAX_IMAGE_BYTES) {
            throw IllegalArgumentException("maxBytes is outside the supported range")
        }
    }
}

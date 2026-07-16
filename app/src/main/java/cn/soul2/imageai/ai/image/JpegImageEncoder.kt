package cn.soul2.imageai.ai.image

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal class JpegImageEncoder {
    fun encode(
        source: Bitmap,
        maxEdge: Int,
        maxBytes: Int,
    ): PreparedImage {
        require(maxEdge > 0)
        require(maxBytes > 0)
        var working = scaleToEdge(source, maxEdge)
        var ownsWorking = working !== source
        try {
            if (working.hasAlpha()) {
                val flattened = flattenOnWhite(working)
                if (ownsWorking) working.recycle()
                working = flattened
                ownsWorking = true
            }

            repeat(MAX_SCALE_ATTEMPTS) {
                QUALITY_STEPS.forEach { quality ->
                    val bytes = compress(working, quality)
                    if (bytes.size <= maxBytes) {
                        return PreparedImage(
                            bytes = bytes,
                            mimeType = JPEG_MIME_TYPE,
                            width = working.width,
                            height = working.height,
                        )
                    }
                }
                if (max(working.width, working.height) <= MIN_OUTPUT_EDGE) {
                    throw ImagePreparationException(
                        ImagePreparationFailure.OUTPUT_LIMIT_UNREACHABLE,
                    )
                }
                val nextEdge = max(
                    MIN_OUTPUT_EDGE,
                    (max(working.width, working.height) * SCALE_FACTOR).roundToInt(),
                )
                val scaled = scaleToEdge(working, nextEdge)
                if (scaled === working) {
                    throw ImagePreparationException(
                        ImagePreparationFailure.OUTPUT_LIMIT_UNREACHABLE,
                    )
                }
                if (ownsWorking) working.recycle()
                working = scaled
                ownsWorking = true
            }
            throw ImagePreparationException(ImagePreparationFailure.OUTPUT_LIMIT_UNREACHABLE)
        } finally {
            if (ownsWorking && !working.isRecycled) working.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        val compressed = try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        } catch (error: RuntimeException) {
            throw ImagePreparationException(ImagePreparationFailure.ENCODE_FAILED, error)
        }
        if (!compressed) {
            throw ImagePreparationException(ImagePreparationFailure.ENCODE_FAILED)
        }
        return output.toByteArray()
    }

    private fun scaleToEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val currentEdge = max(source.width, source.height)
        if (currentEdge <= maxEdge) return source
        val scale = maxEdge.toDouble() / currentEdge
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        return try {
            Bitmap.createScaledBitmap(source, width, height, true)
        } catch (error: RuntimeException) {
            throw ImagePreparationException(ImagePreparationFailure.ENCODE_FAILED, error)
        }
    }

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val flattened = try {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        } catch (error: RuntimeException) {
            throw ImagePreparationException(ImagePreparationFailure.ENCODE_FAILED, error)
        } catch (error: OutOfMemoryError) {
            throw ImagePreparationException(ImagePreparationFailure.ENCODE_FAILED, error)
        }
        val row = IntArray(source.width)
        repeat(source.height) { y ->
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
            row.indices.forEach { x -> row[x] = compositeOnWhite(row[x]) }
            flattened.setPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        flattened.setHasAlpha(false)
        return flattened
    }

    private fun compositeOnWhite(pixel: Int): Int {
        val alpha = pixel ushr 24
        if (alpha == 255) return pixel or OPAQUE_ALPHA
        if (alpha == 0) return OPAQUE_WHITE
        val inverseAlpha = 255 - alpha
        fun blend(channel: Int) = (channel * alpha + 255 * inverseAlpha + 127) / 255
        val red = blend(pixel ushr 16 and 0xff)
        val green = blend(pixel ushr 8 and 0xff)
        val blue = blend(pixel and 0xff)
        return OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
    }

    private companion object {
        const val JPEG_MIME_TYPE = "image/jpeg"
        const val INITIAL_BUFFER_BYTES = 64 * 1_024
        const val MIN_OUTPUT_EDGE = 256
        const val MAX_SCALE_ATTEMPTS = 12
        const val SCALE_FACTOR = 0.8
        const val OPAQUE_ALPHA = -0x1000000
        const val OPAQUE_WHITE = -0x1
        val QUALITY_STEPS = intArrayOf(90, 82, 74, 66, 58, 50)
    }
}

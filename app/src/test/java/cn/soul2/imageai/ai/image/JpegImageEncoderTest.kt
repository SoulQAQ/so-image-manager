package cn.soul2.imageai.ai.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JpegImageEncoderTest {
    private val encoder = JpegImageEncoder()

    @Test
    fun scalesToMaximumEdgePreservesAspectRatioAndBoundsBytes() {
        val source = Bitmap.createBitmap(1_200, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(32, 120, 220))
        }
        try {
            val result = encoder.encode(source, maxEdge = 600, maxBytes = 128 * 1_024)

            assertEquals("image/jpeg", result.mimeType)
            assertEquals(600, result.width)
            assertEquals(300, result.height)
            assertTrue(result.bytes.size <= 128 * 1_024)
            assertFalse(source.isRecycled)
            val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
            assertEquals(600, decoded.width)
            assertEquals(300, decoded.height)
            decoded.recycle()
        } finally {
            source.recycle()
        }
    }

    @Test
    fun transparentSourceIsFlattenedOnWhiteWithoutRecyclingCallerBitmap() {
        val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            eraseColor(Color.TRANSPARENT)
        }
        try {
            assertTrue(source.hasAlpha())
            val result = encoder.encode(source, maxEdge = 400, maxBytes = 128 * 1_024)
            val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)

            assertFalse(source.isRecycled)
            val pixel = decoded.getPixel(decoded.width / 2, decoded.height / 2)
            val diagnostic = "pixel=${Color.red(pixel)},${Color.green(pixel)},${Color.blue(pixel)}"
            assertTrue(diagnostic, Color.red(pixel) >= 245)
            assertTrue(diagnostic, Color.green(pixel) >= 245)
            assertTrue(diagnostic, Color.blue(pixel) >= 245)
            decoded.recycle()
        } finally {
            source.recycle()
        }
    }

    @Test
    fun impossibleByteLimitFailsInsteadOfReturningOversizedData() {
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        try {
            val error = assertThrows(ImagePreparationException::class.java) {
                encoder.encode(source, maxEdge = 512, maxBytes = 1)
            }
            assertEquals(ImagePreparationFailure.OUTPUT_LIMIT_UNREACHABLE, error.failure)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }
}

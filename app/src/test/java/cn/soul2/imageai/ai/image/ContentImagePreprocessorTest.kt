package cn.soul2.imageai.ai.image

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContentImagePreprocessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preprocessor = ContentImagePreprocessor(context.contentResolver)

    @Test
    fun rejectsLimitsOutsideTheConfiguredHardBoundsBeforeOpeningSource() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                preprocessor.prepare("content://fixture/image", maxEdge = 255, maxBytes = 1_500_000)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                preprocessor.prepare("content://fixture/image", maxEdge = 1_600, maxBytes = 8_388_609)
            }
        }
    }

    @Test
    fun rejectsNonContentUrisWithoutReadingThem() = runTest {
        assertThrows(ImagePreparationException::class.java) {
            kotlinx.coroutines.runBlocking {
                preprocessor.prepare("file:///sdcard/private.jpg", 1_600, 1_500_000)
            }
        }
    }
}

package cn.soul2.imageai.update

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateDownloadGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val updateDirectory: File
        get() = File(
            requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
            "updates",
        )

    @Before
    fun prepareDirectory() {
        updateDirectory.deleteRecursively()
        updateDirectory.mkdirs()
    }

    @After
    fun cleanDirectory() {
        updateDirectory.deleteRecursively()
    }

    @Test
    fun cleanupKeepsOnlyTheActiveArtifactThenRemovesEverything() {
        val keep = File(updateDirectory, "soim-v0.17.3-debug.apk").apply {
            writeBytes(byteArrayOf(1))
        }
        val legacy = File(updateDirectory, "soim-v0.17.2-debug.apk").apply {
            writeBytes(byteArrayOf(2))
        }
        val gateway = AndroidUpdateDownloadGateway(context)

        gateway.cleanupArtifacts(exceptAssetName = keep.name)

        assertTrue(keep.isFile)
        assertFalse(legacy.exists())

        gateway.cleanupArtifacts()
        assertFalse(keep.exists())
    }
}

package cn.soul2.imageai.media.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncManifestContractTest {
    @Test
    fun workManagerForegroundAndNotificationPermissionsAreExplicitlyRemoved() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(
            "WorkManager's foreground-service permission must be removed",
            Regex(
                "<uses-permission\\s+android:name=\"android.permission.FOREGROUND_SERVICE\"" +
                    "\\s+tools:node=\"remove\"\\s*/>",
            ).containsMatchIn(manifest),
        )
        assertFalse(
            "Notification permission must not be declared",
            manifest.contains("android.permission.POST_NOTIFICATIONS"),
        )
    }

    private fun projectFile(path: String): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile }
        return File(root, path)
    }
}

package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateContractTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun manifestLimitsInstallerSharingToThePrivateUpdateDirectory() {
        val manifest = file("app/src/main/AndroidManifest.xml").readText()
        val paths = file("app/src/main/res/xml/update_file_paths.xml").readText()

        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("android:authorities=\"\${applicationId}.updates\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(paths.contains("path=\"Download/updates/\""))
        assertFalse(paths.contains("<root-path"))
        assertFalse(paths.contains("path=\".\""))
    }

    @Test
    fun updaterUsesPublicReleaseApiWithoutEmbeddingCredentials() {
        val source = file(
            "app/src/main/java/cn/soul2/imageai/update/GitHubReleaseUpdateSource.kt",
        ).readText()
        assertTrue(
            source.contains(
                "https://api.github.com/repos/SoulQAQ/so-image-manager/releases/latest",
            ),
        )
        listOf("Authorization", "Bearer ", "github_pat_", "ghp_").forEach { forbidden ->
            assertFalse("Updater embeds GitHub credentials: $forbidden", source.contains(forbidden))
        }
    }

    private fun file(path: String) = File(root, path)
}

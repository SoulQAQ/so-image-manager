package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacySurfaceTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun secretAndDemoProductPathsAreAbsent() {
        listOf(
            "env/apikey.txt",
            "app/src/main/assets/h5",
            "app/src/main/java/cn/soul2/imageai/webview",
            "app/src/main/java/cn/soul2/imageai/data/api",
            "app/src/main/java/cn/soul2/imageai/domain/service",
        ).forEach { path -> assertFalse("Legacy path remains: $path", File(root, path).exists()) }

        val mainText = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "kts", "xml") }
            .joinToString("\n") { it.readText() }
        listOf("AI_API_KEY", "apikey.txt", "android.webkit.WebView", "WebViewAssetLoader", "fts5(")
            .forEach { token -> assertFalse("Forbidden product token remains: $token", mainText.contains(token)) }
    }
}

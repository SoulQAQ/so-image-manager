package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubReleasePublishContractTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
    ) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val script = File(root, "scripts/publish-github-release.ps1")

    @Test
    fun publisherRequiresReleaseIdentityAndRunsTheFullGate() {
        assertTrue("Missing GitHub Release publisher: ${script.path}", script.isFile)
        val text = script.readText()

        listOf(
            "SOIM_SIGNING_STORE_FILE",
            "SOIM_SIGNING_STORE_PASSWORD",
            "SOIM_SIGNING_KEY_ALIAS",
            "SOIM_SIGNING_KEY_PASSWORD",
            "SOIM_SIGNING_CERT_SHA256",
            ":app:testDebugUnitTest",
            ":app:testReleaseUnitTest",
            ":app:lintDebug",
            ":app:compileDebugAndroidTestKotlin",
            ":app:assembleRelease",
            "apksigner.bat",
            "Signer #1 certificate SHA-256 digest:",
            "Refusing to publish a Debug-signed APK",
        ).forEach { token -> assertTrue("Missing release safety token: $token", text.contains(token)) }
    }

    @Test
    fun publisherOnlyTouchesGitHubWhenPublishIsExplicit() {
        val text = script.readText()
        val publishGuard = text.indexOf("if (-not \$Publish)")
        val shouldProcess = text.indexOf("ShouldProcess(\"github.com/SoulQAQ/so-image-manager\"")
        val githubCreate = text.indexOf("\"release\", \"create\"")

        assertTrue("Missing explicit publish guard", publishGuard >= 0)
        assertTrue("Missing ShouldProcess guard", shouldProcess > publishGuard)
        assertTrue("GitHub creation must follow both guards", githubCreate > shouldProcess)
        assertFalse(text.substring(0, publishGuard).contains("release\", \"create"))
    }
}

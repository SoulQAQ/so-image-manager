package cn.soul2.imageai.update

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GitHubReleaseParserTest {
    @Test
    fun parsesOneStableVersionedApkAndGitHubDigest() {
        val release = GitHubReleaseParser.parse(releaseJson())

        assertEquals(SemanticVersion(0, 17, 0), release.version)
        assertEquals("v0.17.0", release.tagName)
        assertEquals("版本说明", release.notes)
        assertEquals("soim-v0.17.0-debug.apk", release.asset.name)
        assertEquals(64_811_928L, release.asset.sizeBytes)
        assertEquals("a".repeat(64), release.asset.sha256)
    }

    @Test
    fun rejectsMissingDigestMultipleApksAndUntrustedUrls() {
        assertThrows(AppUpdateException::class.java) {
            GitHubReleaseParser.parse(releaseJson(digest = ""))
        }
        assertThrows(AppUpdateException::class.java) {
            GitHubReleaseParser.parse(releaseJson(extraApk = true))
        }
        assertThrows(AppUpdateException::class.java) {
            GitHubReleaseParser.parse(releaseJson(downloadUrl = "https://example.com/update.apk"))
        }
    }

    @Test
    fun exactTagFlowAcceptsPrereleaseButStableFlowRejectsIt() {
        val json = releaseJson(prerelease = true)

        assertThrows(AppUpdateException::class.java) { GitHubReleaseParser.parse(json) }
        assertEquals("v0.17.0", GitHubReleaseParser.parse(json, allowPrerelease = true).tagName)
    }

    private fun releaseJson(
        digest: String = "sha256:${"a".repeat(64)}",
        downloadUrl: String =
            "https://github.com/SoulQAQ/so-image-manager/releases/download/v0.17.0/soim-v0.17.0-debug.apk",
        extraApk: Boolean = false,
        prerelease: Boolean = false,
    ): String {
        val secondAsset = if (extraApk) {
            """,{
                "name":"soim-v0.17.0-release.apk",
                "browser_download_url":"$downloadUrl",
                "size":64811928,
                "digest":"sha256:${"b".repeat(64)}"
            }"""
        } else {
            ""
        }
        return """
            {
              "tag_name":"v0.17.0",
              "name":"SoIM v0.17.0",
              "body":"版本说明",
              "published_at":"2026-07-29T00:00:00Z",
              "html_url":"https://github.com/SoulQAQ/so-image-manager/releases/tag/v0.17.0",
              "draft":false,
              "prerelease":$prerelease,
              "assets":[{
                "name":"soim-v0.17.0-debug.apk",
                "browser_download_url":"$downloadUrl",
                "size":64811928,
                "digest":"$digest"
              }$secondAsset]
            }
        """.trimIndent()
    }
}

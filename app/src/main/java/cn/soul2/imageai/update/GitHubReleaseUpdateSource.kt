package cn.soul2.imageai.update

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

fun interface ReleaseUpdateSource {
    suspend fun latestRelease(): UpdateRelease
}

class GitHubReleaseUpdateSource(
    private val client: OkHttpClient = OkHttpClient(),
    private val endpoint: String = LATEST_RELEASE_ENDPOINT,
) : ReleaseUpdateSource {
    override suspend fun latestRelease(): UpdateRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "SoIM-Android-Updater")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AppUpdateException("检查更新失败（HTTP ${response.code}）")
                }
                val body = response.body?.string()
                    ?: throw AppUpdateException("GitHub 未返回版本信息")
                GitHubReleaseParser.parse(body)
            }
        }.getOrElse { error ->
            if (error is AppUpdateException) throw error
            throw AppUpdateException("无法连接 GitHub Release", error)
        }
    }

    companion object {
        const val LATEST_RELEASE_ENDPOINT =
            "https://api.github.com/repos/SoulQAQ/so-image-manager/releases/latest"
    }
}

internal object GitHubReleaseParser {
    private val APK_NAME = Regex("^soim-v[0-9]+\\.[0-9]+\\.[0-9]+-(?:debug|release)\\.apk$")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val ALLOWED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com")

    fun parse(json: String): UpdateRelease {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw AppUpdateException("GitHub 版本信息格式错误", it) }
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
            throw AppUpdateException("最新版本不是公开稳定版本")
        }
        val tagName = root.requiredString("tag_name")
        val version = SemanticVersion.parse(tagName)
            ?: throw AppUpdateException("Release 标签不是有效版本号")
        val assets = root.optJSONArray("assets")
            ?: throw AppUpdateException("Release 没有安装包")
        val apkAssets = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (APK_NAME.matches(name)) add(asset)
            }
        }
        if (apkAssets.size != 1) {
            throw AppUpdateException("Release 必须且只能包含一个 SoIM APK")
        }
        val assetJson = apkAssets.single()
        val digestValue = assetJson.requiredString("digest")
        val digestParts = digestValue.split(':', limit = 2)
        if (digestParts.size != 2 || digestParts[0] != "sha256" || !SHA256.matches(digestParts[1])) {
            throw AppUpdateException("Release APK 缺少有效的 SHA-256")
        }
        val downloadUrl = assetJson.requiredString("browser_download_url")
        requireSecureGitHubUrl(downloadUrl)
        val sizeBytes = assetJson.optLong("size", -1L)
        if (sizeBytes <= 0L) throw AppUpdateException("Release APK 大小无效")
        return UpdateRelease(
            version = version,
            tagName = tagName,
            releaseName = root.optString("name").ifBlank { tagName },
            notes = root.optString("body"),
            publishedAt = root.optString("published_at"),
            pageUrl = root.requiredString("html_url").also(::requireSecureGitHubUrl),
            asset = UpdateAsset(
                name = assetJson.requiredString("name"),
                downloadUrl = downloadUrl,
                sizeBytes = sizeBytes,
                sha256 = digestParts[1].lowercase(),
            ),
        )
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).takeIf(String::isNotBlank)
            ?: throw AppUpdateException("GitHub 版本信息缺少 $name")

    private fun requireSecureGitHubUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw AppUpdateException("Release 地址无效")
        if (uri.scheme != "https" || uri.host?.lowercase() !in ALLOWED_DOWNLOAD_HOSTS) {
            throw AppUpdateException("Release 地址不是受信任的 GitHub HTTPS 地址")
        }
    }
}

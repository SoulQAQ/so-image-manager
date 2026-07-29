package cn.soul2.imageai.update

import java.io.File

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val STRICT_VERSION = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

        fun parse(value: String): SemanticVersion? {
            val match = STRICT_VERSION.matchEntire(value.trim()) ?: return null
            return runCatching {
                SemanticVersion(
                    major = match.groupValues[1].toInt(),
                    minor = match.groupValues[2].toInt(),
                    patch = match.groupValues[3].toInt(),
                )
            }.getOrNull()
        }
    }
}

data class UpdateRelease(
    val version: SemanticVersion,
    val tagName: String,
    val releaseName: String,
    val notes: String,
    val publishedAt: String,
    val pageUrl: String,
    val asset: UpdateAsset,
)

data class UpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class InstalledAppVersion(
    val version: SemanticVersion,
    val versionName: String,
    val versionCode: Long,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val currentVersion: String) : AppUpdateState
    data class Available(val release: UpdateRelease) : AppUpdateState
    data class Downloading(
        val release: UpdateRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long? = null,
    ) : AppUpdateState
    data class Restoring(val release: UpdateRelease) : AppUpdateState
    data class Ready(val release: UpdateRelease, val apk: File) : AppUpdateState
    data class InstallationFailed(
        val release: UpdateRelease,
        val apk: File,
        val message: String,
    ) : AppUpdateState
    data class Failed(val message: String, val release: UpdateRelease? = null) : AppUpdateState
}

class AppUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

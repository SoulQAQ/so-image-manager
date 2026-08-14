package cn.soul2.imageai.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

fun interface MigrationArtifactVerifier {
    fun verify(file: File, release: UpdateRelease): File
}

class OfficialMigrationApkVerifier(
    context: Context,
    private val identity: OfficialReleaseIdentity = OfficialReleaseIdentity.Current,
) : MigrationArtifactVerifier {
    private val packageManager = context.applicationContext.packageManager

    override fun verify(file: File, release: UpdateRelease): File {
        if (!identity.isConfigured) throw AppUpdateException("长期正式版证书尚未配置，当前不能开始迁移")
        if (release.tagName != identity.firstTag || release.version != identity.firstVersion) {
            throw AppUpdateException("迁移安装包不是指定的 ${identity.firstTag}")
        }
        if (!file.isFile || file.length() != release.asset.sizeBytes) {
            throw AppUpdateException("正式版安装包不完整")
        }
        if (sha256(file) != release.asset.sha256) {
            throw AppUpdateException("正式版安装包 SHA-256 校验失败")
        }
        val archive = archivePackageInfo(file) ?: throw AppUpdateException("无法读取正式版安装包")
        val signingInfo = archive.signingInfo ?: throw AppUpdateException("正式版安装包没有签名信息")
        val signers = signingInfo.apkContentsSigners.orEmpty()
            .map { sha256(it.toByteArray()) }
            .toSet()
        OfficialMigrationPackagePolicy.validate(
            identity = identity,
            release = release,
            packageName = archive.packageName,
            versionName = archive.versionName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(archive),
            signerSha256 = signers,
        )
        return file
    }

    private fun archivePackageInfo(file: File): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

internal object OfficialMigrationPackagePolicy {
    fun validate(
        identity: OfficialReleaseIdentity,
        release: UpdateRelease,
        packageName: String,
        versionName: String,
        versionCode: Long,
        signerSha256: Set<String>,
    ) {
        val expectedCertificate = identity.certificateSha256
            ?: throw AppUpdateException("长期正式版证书尚未配置，当前不能开始迁移")
        if (release.tagName != identity.firstTag || release.version != identity.firstVersion) {
            throw AppUpdateException("迁移安装包不是指定的 ${identity.firstTag}")
        }
        if (packageName != identity.packageId) throw AppUpdateException("正式版应用 ID 不匹配")
        if (SemanticVersion.parse(versionName) != identity.firstVersion || versionCode <= 0L) {
            throw AppUpdateException("正式版安装包版本不匹配")
        }
        if (signerSha256.size != 1 || expectedCertificate !in signerSha256) {
            throw AppUpdateException("正式版安装包证书与固定身份不匹配")
        }
    }
}

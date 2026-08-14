package cn.soul2.imageai.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

interface UpdateArtifactVerifier {
    fun installedVersion(): InstalledAppVersion
    fun verify(file: File, release: UpdateRelease): File
}

class ApkUpdateVerifier(
    context: Context,
    private val officialIdentity: OfficialReleaseIdentity = OfficialReleaseIdentity.Current,
) : UpdateArtifactVerifier {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    override fun installedVersion(): InstalledAppVersion {
        val info = installedPackageInfo()
        val versionName = info.versionName.orEmpty()
        return InstalledAppVersion(
            version = SemanticVersion.parse(versionName)
                ?: throw AppUpdateException("当前应用版本号无效"),
            versionName = versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
        )
    }

    override fun verify(file: File, release: UpdateRelease): File {
        if (!file.isFile) throw AppUpdateException("更新安装包不存在")
        if (file.length() != release.asset.sizeBytes) {
            throw AppUpdateException("更新安装包大小与 Release 不一致")
        }
        if (sha256(file) != release.asset.sha256) {
            throw AppUpdateException("更新安装包 SHA-256 校验失败")
        }
        val archive = archivePackageInfo(file)
            ?: throw AppUpdateException("无法读取更新安装包")
        if (archive.packageName != applicationContext.packageName) {
            throw AppUpdateException("更新安装包的应用 ID 不匹配")
        }
        val archiveVersionName = archive.versionName.orEmpty()
        if (SemanticVersion.parse(archiveVersionName) != release.version) {
            throw AppUpdateException("安装包版本与 GitHub Release 不匹配")
        }
        val installed = installedPackageInfo()
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw AppUpdateException("更新安装包版本不高于当前版本")
        }
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty()
            .map { signature -> sha256(signature.toByteArray()) }
            .toSet()
        val archiveSigningInfo = archive.signingInfo
            ?: throw AppUpdateException("更新安装包没有签名信息")
        val archiveHistory = if (archiveSigningInfo.hasPastSigningCertificates()) {
            archiveSigningInfo.signingCertificateHistory.orEmpty()
        } else {
            archiveSigningInfo.apkContentsSigners.orEmpty()
        }.map { signature -> sha256(signature.toByteArray()) }.toSet()
        if (installedSigners.isEmpty() || installedSigners.intersect(archiveHistory).isEmpty()) {
            if (officialIdentity.certificateSha256 in archiveHistory) {
                throw AppUpdateException("此版本使用长期正式证书，需要通过设置中的“迁移到正式版”完成升级")
            }
            throw AppUpdateException("更新安装包签名与当前应用不匹配")
        }
        return file
    }

    private fun installedPackageInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(
            applicationContext.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(
            applicationContext.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
    }

    private fun archivePackageInfo(file: File): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
    }

    internal fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

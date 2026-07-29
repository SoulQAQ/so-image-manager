package cn.soul2.imageai.update

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import java.io.File

sealed interface UpdateDownloadStatus {
    data class Active(val downloadedBytes: Long, val totalBytes: Long) : UpdateDownloadStatus
    data object Successful : UpdateDownloadStatus
    data class Failed(val reason: Int) : UpdateDownloadStatus
    data object Missing : UpdateDownloadStatus
}

interface UpdateDownloadGateway {
    fun enqueue(release: UpdateRelease): Long
    fun status(downloadId: Long): UpdateDownloadStatus
    fun cancel(downloadId: Long)
    fun fileFor(asset: UpdateAsset): File
    fun cleanupArtifacts(exceptAssetName: String? = null)
}

class AndroidUpdateDownloadGateway(context: Context) : UpdateDownloadGateway {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(DownloadManager::class.java)

    override fun enqueue(release: UpdateRelease): Long {
        val destination = fileFor(release.asset)
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            throw AppUpdateException("无法清理旧的更新安装包")
        }
        val request = DownloadManager.Request(Uri.parse(release.asset.downloadUrl))
            .setTitle("SoIM ${release.tagName}")
            .setDescription("正在下载应用更新")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                applicationContext,
                Environment.DIRECTORY_DOWNLOADS,
                "updates/${release.asset.name}",
            )
        return manager.enqueue(request)
    }

    override fun status(downloadId: Long): UpdateDownloadStatus {
        val query = DownloadManager.Query().setFilterById(downloadId)
        return manager.query(query)?.use { cursor -> cursor.toStatus() }
            ?: UpdateDownloadStatus.Missing
    }

    override fun cancel(downloadId: Long) {
        manager.remove(downloadId)
    }

    override fun fileFor(asset: UpdateAsset): File {
        val root = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw AppUpdateException("设备没有可用的更新下载目录")
        val updateDirectory = File(root, "updates").canonicalFile
        val destination = File(updateDirectory, asset.name).canonicalFile
        if (destination.parentFile != updateDirectory) {
            throw AppUpdateException("Release APK 文件名不安全")
        }
        return destination
    }

    override fun cleanupArtifacts(exceptAssetName: String?) {
        val root = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val updateDirectory = File(root, "updates").canonicalFile
        updateDirectory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.name != exceptAssetName }
            .forEach { file -> runCatching { file.delete() } }
    }

    private fun Cursor.toStatus(): UpdateDownloadStatus {
        if (!moveToFirst()) return UpdateDownloadStatus.Missing
        return when (getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_RUNNING,
            -> UpdateDownloadStatus.Active(
                downloadedBytes = getLong(
                    getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                ).coerceAtLeast(0L),
                totalBytes = getLong(
                    getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                ),
            )
            DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadStatus.Successful
            DownloadManager.STATUS_FAILED -> UpdateDownloadStatus.Failed(
                getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
            else -> UpdateDownloadStatus.Missing
        }
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

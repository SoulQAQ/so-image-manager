package cn.soul2.imageai.update

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class AppUpdateManager(
    context: Context,
    private val scope: CoroutineScope,
    private val source: ReleaseUpdateSource = GitHubReleaseUpdateSource(),
    private val downloads: UpdateDownloadGateway = AndroidUpdateDownloadGateway(context),
    private val verifier: UpdateArtifactVerifier = ApkUpdateVerifier(context),
    private val pendingStore: PendingUpdateStore = SharedPreferencesPendingUpdateStore(context),
) {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    private val lock = Any()
    private var operation: Job? = null

    init {
        pendingStore.load()?.let(::monitor)
    }

    fun checkForUpdate() {
        if (mutableState.value is AppUpdateState.Downloading || mutableState.value is AppUpdateState.Ready) {
            return
        }
        launchOperation {
            mutableState.value = AppUpdateState.Checking
            try {
                val installed = verifier.installedVersion()
                val latest = source.latestRelease()
                mutableState.value = if (latest.version > installed.version) {
                    AppUpdateState.Available(latest)
                } else {
                    AppUpdateState.UpToDate(installed.versionName)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppUpdateException) {
                mutableState.value = AppUpdateState.Failed(error.message.orEmpty())
            } catch (_: Throwable) {
                mutableState.value = AppUpdateState.Failed("检查更新失败")
            }
        }
    }

    fun downloadUpdate() {
        val release = when (val current = mutableState.value) {
            is AppUpdateState.Available -> current.release
            is AppUpdateState.Failed -> current.release
            else -> null
        } ?: return
        mutableState.value = AppUpdateState.Downloading(
            release = release,
            downloadedBytes = 0L,
            totalBytes = release.asset.sizeBytes,
        )
        launchOperation {
            try {
                val downloadId = downloads.enqueue(release)
                val pending = PendingUpdate(downloadId, release)
                pendingStore.save(pending)
                monitorLoop(pending)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppUpdateException) {
                mutableState.value = AppUpdateState.Failed(error.message.orEmpty(), release)
            } catch (_: Throwable) {
                mutableState.value = AppUpdateState.Failed("无法开始下载更新", release)
            }
        }
    }

    fun cancelDownload() {
        val release = when (val current = mutableState.value) {
            is AppUpdateState.Downloading -> current.release
            else -> pendingStore.load()?.release
        } ?: return
        synchronized(lock) {
            operation?.cancel()
            operation = null
        }
        pendingStore.load()?.let { pending ->
            downloads.cancel(pending.downloadId)
            runCatching { downloads.fileFor(pending.release.asset).delete() }
        }
        pendingStore.clear()
        mutableState.value = AppUpdateState.Available(release)
    }

    fun dismissFailure() {
        if (mutableState.value is AppUpdateState.Failed) mutableState.value = AppUpdateState.Idle
    }

    private fun monitor(pending: PendingUpdate) {
        mutableState.value = AppUpdateState.Downloading(
            release = pending.release,
            downloadedBytes = 0L,
            totalBytes = pending.release.asset.sizeBytes,
        )
        launchOperation { monitorLoop(pending) }
    }

    private suspend fun monitorLoop(pending: PendingUpdate) {
        val release = pending.release
        while (kotlin.coroutines.coroutineContext.isActive) {
            when (val status = downloads.status(pending.downloadId)) {
                is UpdateDownloadStatus.Active -> {
                    mutableState.value = AppUpdateState.Downloading(
                        release = release,
                        downloadedBytes = status.downloadedBytes,
                        totalBytes = status.totalBytes.takeIf { it > 0L } ?: release.asset.sizeBytes,
                    )
                    delay(DOWNLOAD_POLL_INTERVAL_MILLIS)
                }
                UpdateDownloadStatus.Successful -> {
                    val file = downloads.fileFor(release.asset)
                    try {
                        verifier.verify(file, release)
                        pendingStore.clear()
                        mutableState.value = AppUpdateState.Ready(release, file)
                    } catch (error: AppUpdateException) {
                        downloads.cancel(pending.downloadId)
                        file.delete()
                        pendingStore.clear()
                        mutableState.value = AppUpdateState.Failed(error.message.orEmpty(), release)
                    }
                    return
                }
                is UpdateDownloadStatus.Failed -> {
                    cleanupFailedDownload(pending)
                    mutableState.value = AppUpdateState.Failed(
                        "更新下载失败（代码 ${status.reason}）",
                        release,
                    )
                    return
                }
                UpdateDownloadStatus.Missing -> {
                    cleanupFailedDownload(pending)
                    mutableState.value = AppUpdateState.Failed("更新下载任务已丢失", release)
                    return
                }
            }
        }
    }

    private fun cleanupFailedDownload(pending: PendingUpdate) {
        downloads.cancel(pending.downloadId)
        runCatching { downloads.fileFor(pending.release.asset).delete() }
        pendingStore.clear()
    }

    private fun launchOperation(block: suspend () -> Unit) {
        synchronized(lock) {
            operation?.cancel()
            operation = scope.launch { block() }
        }
    }

    companion object {
        private const val DOWNLOAD_POLL_INTERVAL_MILLIS = 750L
    }
}

data class PendingUpdate(val downloadId: Long, val release: UpdateRelease)

interface PendingUpdateStore {
    fun load(): PendingUpdate?
    fun save(pending: PendingUpdate)
    fun clear()
}

class SharedPreferencesPendingUpdateStore(context: Context) : PendingUpdateStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): PendingUpdate? {
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        val releaseJson = preferences.getString(KEY_RELEASE, null)
        if (downloadId <= 0L || releaseJson.isNullOrBlank()) {
            if (downloadId != -1L || releaseJson != null) clear()
            return null
        }
        return runCatching {
            PendingUpdate(downloadId, releaseFromJson(JSONObject(releaseJson)))
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(pending: PendingUpdate) {
        preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, pending.downloadId)
            .putString(KEY_RELEASE, releaseToJson(pending.release).toString())
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_RELEASE).apply()
    }

    private fun releaseToJson(release: UpdateRelease): JSONObject = JSONObject()
        .put("version", release.version.toString())
        .put("tagName", release.tagName)
        .put("releaseName", release.releaseName)
        .put("notes", release.notes)
        .put("publishedAt", release.publishedAt)
        .put("pageUrl", release.pageUrl)
        .put("assetName", release.asset.name)
        .put("downloadUrl", release.asset.downloadUrl)
        .put("sizeBytes", release.asset.sizeBytes)
        .put("sha256", release.asset.sha256)

    private fun releaseFromJson(json: JSONObject): UpdateRelease {
        val version = SemanticVersion.parse(json.getString("version"))
            ?: throw AppUpdateException("缓存的更新版本无效")
        return UpdateRelease(
            version = version,
            tagName = json.getString("tagName"),
            releaseName = json.getString("releaseName"),
            notes = json.getString("notes"),
            publishedAt = json.getString("publishedAt"),
            pageUrl = json.getString("pageUrl"),
            asset = UpdateAsset(
                name = json.getString("assetName"),
                downloadUrl = json.getString("downloadUrl"),
                sizeBytes = json.getLong("sizeBytes"),
                sha256 = json.getString("sha256"),
            ),
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "app_update_download"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_RELEASE = "release"
    }
}

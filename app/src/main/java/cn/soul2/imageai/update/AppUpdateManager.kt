package cn.soul2.imageai.update

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.io.File
import java.time.Instant
import java.time.ZoneId
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
    private val lifecycleStore: UpdateLifecycleStore = SharedPreferencesUpdateLifecycleStore(context),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val nowElapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val epochDayAt: (Long) -> Long = { epochMillis ->
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    },
) {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private val mutableInstalledUpdateNotice = MutableStateFlow<InstalledUpdateNotice?>(null)
    val installedUpdateNotice: StateFlow<InstalledUpdateNotice?> =
        mutableInstalledUpdateNotice.asStateFlow()

    private val lock = Any()
    private var operation: Job? = null

    init {
        when (val persisted = pendingStore.load()) {
            null -> scope.launch { downloads.cleanupArtifacts() }
            else -> when (persisted.stage) {
                PersistedUpdateStage.DOWNLOADING -> monitor(persisted)
                PersistedUpdateStage.VERIFIED_READY -> restoreReady(persisted)
            }
        }
    }

    fun onAppStarted() {
        val installed = runCatching { verifier.installedVersion() }.getOrNull()
        mutableInstalledUpdateNotice.value = installed?.let { version ->
            lifecycleStore.loadUnseenInstalledNotice(version.version)
        }
        val now = nowEpochMillis()
        if (!lifecycleStore.markFirstStartOfDay(epochDayAt(now))) return
        if (
            AutomaticUpdateCheckPolicy.isDue(
                lifecycleStore.lastCheckAttemptAtMillis(),
                lifecycleStore.lastSuccessfulCheckAtMillis(),
                now,
            )
        ) {
            checkForUpdate(manual = false)
        }
    }

    fun dismissInstalledUpdateNotice() {
        mutableInstalledUpdateNotice.value?.let { lifecycleStore.markNoticeShown(it.version) }
        mutableInstalledUpdateNotice.value = null
    }

    fun checkForUpdate(manual: Boolean = true) {
        if (
            mutableState.value is AppUpdateState.Downloading ||
            mutableState.value is AppUpdateState.Restoring ||
            mutableState.value is AppUpdateState.Ready ||
            mutableState.value is AppUpdateState.InstallationFailed
        ) {
            return
        }
        lifecycleStore.recordCheckStarted(nowEpochMillis())
        launchOperation {
            mutableState.value = AppUpdateState.Checking
            try {
                val installed = verifier.installedVersion()
                val latest = source.latestRelease()
                lifecycleStore.recordCheckSucceeded(nowEpochMillis())
                mutableState.value = if (
                    latest.version > installed.version &&
                    (manual || latest.version != lifecycleStore.skippedVersion())
                ) {
                    AppUpdateState.Available(latest)
                } else if (!manual && latest.version == lifecycleStore.skippedVersion()) {
                    AppUpdateState.Idle
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
            var enqueuedDownloadId: Long? = null
            var persisted = false
            try {
                val downloadId = downloads.enqueue(release)
                enqueuedDownloadId = downloadId
                val pending = PendingUpdate(downloadId, release, PersistedUpdateStage.DOWNLOADING)
                pendingStore.save(pending)
                persisted = true
                monitorLoop(pending)
            } catch (error: CancellationException) {
                if (!persisted) cleanupUntrackedDownload(enqueuedDownloadId, release)
                throw error
            } catch (error: AppUpdateException) {
                if (!persisted) cleanupUntrackedDownload(enqueuedDownloadId, release)
                mutableState.value = AppUpdateState.Failed(error.message.orEmpty(), release)
            } catch (_: Throwable) {
                if (!persisted) cleanupUntrackedDownload(enqueuedDownloadId, release)
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
            runCatching { downloads.cancel(pending.downloadId) }
            runCatching { downloads.fileFor(pending.release.asset).delete() }
        }
        runCatching { downloads.cleanupArtifacts() }
        pendingStore.clear()
        mutableState.value = AppUpdateState.Available(release)
    }

    fun discardReadyUpdate() {
        val persisted = pendingStore.load()
            ?.takeIf { it.stage == PersistedUpdateStage.VERIFIED_READY }
        val release = persisted?.release ?: when (val current = mutableState.value) {
            is AppUpdateState.Ready -> current.release
            is AppUpdateState.InstallationFailed -> current.release
            else -> null
        } ?: return
        launchOperation {
            try {
                lifecycleStore.markReleaseSkipped(release.version)
                persisted?.let(::cleanupPersistedUpdate) ?: run {
                    runCatching { downloads.fileFor(release.asset).delete() }
                    runCatching { downloads.cleanupArtifacts() }
                    pendingStore.clear()
                }
                mutableState.value = AppUpdateState.Idle
            } catch (error: AppUpdateException) {
                mutableState.value = AppUpdateState.Failed(error.message.orEmpty(), release)
            }
        }
    }

    fun dismissFailure() {
        mutableState.value = when (val current = mutableState.value) {
            is AppUpdateState.Failed -> AppUpdateState.Idle
            is AppUpdateState.InstallationFailed ->
                AppUpdateState.Ready(current.release, current.apk)
            else -> current
        }
    }

    fun reportInstallationFailure(apk: File, message: String) {
        val release = when (val current = mutableState.value) {
            is AppUpdateState.Ready -> current.release
            is AppUpdateState.InstallationFailed -> current.release
            else -> pendingStore.load()
                ?.takeIf { it.stage == PersistedUpdateStage.VERIFIED_READY }
                ?.release
        }
        mutableState.value = if (release == null) {
            AppUpdateState.Failed(message)
        } else {
            AppUpdateState.InstallationFailed(release, apk, message)
        }
    }

    fun invalidateReadyUpdate(message: String) {
        val persisted = pendingStore.load()
            ?.takeIf { it.stage == PersistedUpdateStage.VERIFIED_READY }
        val release = persisted?.release ?: when (val current = mutableState.value) {
            is AppUpdateState.Ready -> current.release
            is AppUpdateState.InstallationFailed -> current.release
            else -> null
        }
        launchOperation {
            persisted?.let(::cleanupPersistedUpdate)
            mutableState.value = AppUpdateState.Failed(message, release)
        }
    }

    private fun monitor(pending: PendingUpdate) {
        mutableState.value = AppUpdateState.Downloading(
            release = pending.release,
            downloadedBytes = 0L,
            totalBytes = pending.release.asset.sizeBytes,
        )
        launchOperation {
            downloads.cleanupArtifacts(exceptAssetName = pending.release.asset.name)
            monitorLoop(pending)
        }
    }

    private fun restoreReady(persisted: PendingUpdate) {
        mutableState.value = AppUpdateState.Restoring(persisted.release)
        launchOperation {
            val release = persisted.release
            val file = downloads.fileFor(release.asset)
            try {
                if (verifier.installedVersion().version >= release.version) {
                    cleanupPersistedUpdate(persisted)
                    mutableState.value = AppUpdateState.Idle
                } else {
                    verifier.verify(file, release)
                    downloads.cleanupArtifacts(exceptAssetName = release.asset.name)
                    mutableState.value = AppUpdateState.Ready(release, file)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppUpdateException) {
                cleanupPersistedUpdate(persisted)
                mutableState.value = AppUpdateState.Failed(error.message.orEmpty(), release)
            } catch (_: Throwable) {
                cleanupPersistedUpdate(persisted)
                mutableState.value = AppUpdateState.Failed("无法恢复已下载的更新", release)
            }
        }
    }

    private suspend fun monitorLoop(pending: PendingUpdate) {
        val release = pending.release
        val speedEstimator = DownloadSpeedEstimator()
        while (kotlin.coroutines.coroutineContext.isActive) {
            when (val status = downloads.status(pending.downloadId)) {
                is UpdateDownloadStatus.Active -> {
                    mutableState.value = AppUpdateState.Downloading(
                        release = release,
                        downloadedBytes = status.downloadedBytes,
                        totalBytes = status.totalBytes.takeIf { it > 0L } ?: release.asset.sizeBytes,
                        bytesPerSecond = speedEstimator.observe(
                            downloadedBytes = status.downloadedBytes,
                            observedAtMillis = nowElapsedRealtimeMillis(),
                        ),
                    )
                    delay(DOWNLOAD_POLL_INTERVAL_MILLIS)
                }
                UpdateDownloadStatus.Successful -> {
                    val file = downloads.fileFor(release.asset)
                    try {
                        verifier.verify(file, release)
                        lifecycleStore.savePreparedRelease(release)
                        pendingStore.save(
                            pending.copy(stage = PersistedUpdateStage.VERIFIED_READY),
                        )
                        mutableState.value = AppUpdateState.Ready(release, file)
                    } catch (error: AppUpdateException) {
                        cleanupFailedDownload(pending)
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
        runCatching { downloads.cancel(pending.downloadId) }
        runCatching { downloads.fileFor(pending.release.asset).delete() }
        runCatching { downloads.cleanupArtifacts() }
        pendingStore.clear()
    }

    private fun cleanupPersistedUpdate(persisted: PendingUpdate) {
        runCatching { downloads.cancel(persisted.downloadId) }
        runCatching { downloads.fileFor(persisted.release.asset).delete() }
        runCatching { downloads.cleanupArtifacts() }
        pendingStore.clear()
    }

    private fun cleanupUntrackedDownload(downloadId: Long?, release: UpdateRelease) {
        downloadId?.let { id -> runCatching { downloads.cancel(id) } }
        runCatching { downloads.fileFor(release.asset).delete() }
        runCatching { downloads.cleanupArtifacts() }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        synchronized(lock) {
            operation?.cancel()
            operation = scope.launch { block() }
        }
    }

    companion object {
        private const val DOWNLOAD_POLL_INTERVAL_MILLIS = 500L
    }
}

enum class PersistedUpdateStage {
    DOWNLOADING,
    VERIFIED_READY,
}

data class PendingUpdate(
    val downloadId: Long,
    val release: UpdateRelease,
    val stage: PersistedUpdateStage = PersistedUpdateStage.DOWNLOADING,
)

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
            pendingFromJson(downloadId, JSONObject(releaseJson))
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(pending: PendingUpdate) {
        val saved = preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, pending.downloadId)
            .putString(KEY_RELEASE, releaseToJson(pending).toString())
            .commit()
        if (!saved) throw AppUpdateException("无法保存更新下载状态")
    }

    override fun clear() {
        preferences.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_RELEASE).commit()
    }

    private fun releaseToJson(pending: PendingUpdate): JSONObject = JSONObject().also { json ->
        val release = pending.release
        json
            .put("stage", pending.stage.name)
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
    }

    private fun pendingFromJson(downloadId: Long, json: JSONObject): PendingUpdate {
        val version = SemanticVersion.parse(json.getString("version"))
            ?: throw AppUpdateException("缓存的更新版本无效")
        val release = UpdateRelease(
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
        return PendingUpdate(
            downloadId = downloadId,
            release = release,
            stage = json.optString("stage")
                .takeIf(String::isNotBlank)
                ?.let(PersistedUpdateStage::valueOf)
                ?: PersistedUpdateStage.DOWNLOADING,
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "app_update_download"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_RELEASE = "release"
    }
}

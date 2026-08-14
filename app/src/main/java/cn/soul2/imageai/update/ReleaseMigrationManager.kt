package cn.soul2.imageai.update

import android.content.Context
import cn.soul2.imageai.backup.BackupPreflightResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface ReleaseMigrationState {
    data class Unavailable(val reason: String) : ReleaseMigrationState
    data object AwaitingBackup : ReleaseMigrationState
    data class BackupSaved(val summary: BackupPreflightResult) : ReleaseMigrationState
    data class Downloading(
        val summary: BackupPreflightResult,
        val release: UpdateRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : ReleaseMigrationState
    data class ApkReady(
        val summary: BackupPreflightResult,
        val release: UpdateRelease,
        val apk: File,
    ) : ReleaseMigrationState
    data class Complete(
        val summary: BackupPreflightResult,
        val release: UpdateRelease,
    ) : ReleaseMigrationState
    data class Failed(val message: String, val summary: BackupPreflightResult?) : ReleaseMigrationState
}

class ReleaseMigrationManager(
    context: Context,
    private val scope: CoroutineScope,
    private val identity: OfficialReleaseIdentity = OfficialReleaseIdentity.Current,
    private val source: TaggedReleaseSource = GitHubTaggedReleaseSource(),
    private val downloads: UpdateDownloadGateway = AndroidUpdateDownloadGateway(context),
    private val verifier: MigrationArtifactVerifier = OfficialMigrationApkVerifier(context, identity),
    private val store: ReleaseMigrationStore = SharedPreferencesReleaseMigrationStore(context),
) {
    private val mutableState = MutableStateFlow<ReleaseMigrationState>(initialState())
    val state: StateFlow<ReleaseMigrationState> = mutableState.asStateFlow()
    private val mutablePromptRequested = MutableStateFlow(false)
    val promptRequested: StateFlow<Boolean> = mutablePromptRequested.asStateFlow()
    private var operation: Job? = null

    init {
        store.load()?.takeIf { it.stage == MigrationStage.DOWNLOADING }?.let(::monitor)
    }

    fun onAppStarted() {
        if (!identity.isConfigured || store.promptWasShown()) return
        scope.launch {
            runCatching { source.release(identity.firstTag) }
                .onSuccess { mutablePromptRequested.value = true }
        }
    }

    fun consumePrompt() {
        store.markPromptShown()
        mutablePromptRequested.value = false
    }

    fun recordBackupSaved(summary: BackupPreflightResult) {
        if (!identity.isConfigured) {
            mutableState.value = ReleaseMigrationState.Unavailable("长期正式版证书尚未配置")
            return
        }
        store.save(MigrationRecord(MigrationStage.BACKUP_SAVED, summary, null, null))
        mutableState.value = ReleaseMigrationState.BackupSaved(summary)
    }

    fun downloadOfficialRelease() {
        if (!identity.isConfigured) {
            mutableState.value = ReleaseMigrationState.Unavailable("长期正式版证书尚未配置")
            return
        }
        val summary = store.load()?.summary ?: return
        operation?.cancel()
        operation = scope.launch {
            try {
                val release = source.release(identity.firstTag)
                val downloadId = downloads.enqueue(release)
                val record = MigrationRecord(MigrationStage.DOWNLOADING, summary, release, downloadId)
                store.save(record)
                monitorLoop(record)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.value = ReleaseMigrationState.Failed(
                    error.message ?: "无法下载正式版", summary,
                )
            }
        }
    }

    fun markApkSaved() {
        val record = store.load() ?: return
        val release = record.release ?: return
        store.save(record.copy(stage = MigrationStage.COMPLETE, downloadId = null))
        mutableState.value = ReleaseMigrationState.Complete(record.summary, release)
    }

    fun retry() {
        val summary = store.load()?.summary
        if (summary == null) reset() else downloadOfficialRelease()
    }

    fun reset() {
        operation?.cancel()
        store.load()?.downloadId?.let { runCatching { downloads.cancel(it) } }
        store.clear()
        mutableState.value = if (identity.isConfigured) {
            ReleaseMigrationState.AwaitingBackup
        } else {
            ReleaseMigrationState.Unavailable("长期正式版证书尚未配置")
        }
    }

    private fun initialState(): ReleaseMigrationState {
        if (!identity.isConfigured) return ReleaseMigrationState.Unavailable("长期正式版证书尚未配置")
        val record = store.load() ?: return ReleaseMigrationState.AwaitingBackup
        return when (record.stage) {
            MigrationStage.BACKUP_SAVED -> ReleaseMigrationState.BackupSaved(record.summary)
            MigrationStage.DOWNLOADING -> record.release?.let {
                ReleaseMigrationState.Downloading(record.summary, it, 0L, it.asset.sizeBytes)
            } ?: ReleaseMigrationState.BackupSaved(record.summary)
            MigrationStage.APK_READY -> {
                val release = record.release
                val apk = release?.let { runCatching { downloads.fileFor(it.asset) }.getOrNull() }
                if (release != null && apk?.isFile == true) {
                    ReleaseMigrationState.ApkReady(record.summary, release, apk)
                } else ReleaseMigrationState.BackupSaved(record.summary)
            }
            MigrationStage.COMPLETE -> record.release?.let {
                ReleaseMigrationState.Complete(record.summary, it)
            } ?: ReleaseMigrationState.BackupSaved(record.summary)
        }
    }

    private fun monitor(record: MigrationRecord) {
        operation?.cancel()
        operation = scope.launch {
            try {
                monitorLoop(record)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.value = ReleaseMigrationState.Failed(
                    error.message ?: "无法恢复正式版下载", record.summary,
                )
            }
        }
    }

    private suspend fun monitorLoop(record: MigrationRecord) {
        val release = requireNotNull(record.release)
        val downloadId = requireNotNull(record.downloadId)
        while (true) {
            when (val status = downloads.status(downloadId)) {
                is UpdateDownloadStatus.Active -> mutableState.value = ReleaseMigrationState.Downloading(
                    record.summary, release, status.downloadedBytes,
                    status.totalBytes.takeIf { it > 0L } ?: release.asset.sizeBytes,
                )
                UpdateDownloadStatus.Successful -> {
                    val apk = verifier.verify(downloads.fileFor(release.asset), release)
                    store.save(record.copy(stage = MigrationStage.APK_READY, downloadId = null))
                    mutableState.value = ReleaseMigrationState.ApkReady(record.summary, release, apk)
                    return
                }
                is UpdateDownloadStatus.Failed -> throw AppUpdateException("正式版下载失败（${status.reason}）")
                UpdateDownloadStatus.Missing -> throw AppUpdateException("正式版下载任务已丢失")
            }
            delay(500L)
        }
    }
}

enum class MigrationStage { BACKUP_SAVED, DOWNLOADING, APK_READY, COMPLETE }

data class MigrationRecord(
    val stage: MigrationStage,
    val summary: BackupPreflightResult,
    val release: UpdateRelease?,
    val downloadId: Long?,
)

interface ReleaseMigrationStore {
    fun load(): MigrationRecord?
    fun save(record: MigrationRecord)
    fun clear()
    fun promptWasShown(): Boolean
    fun markPromptShown()
}

class SharedPreferencesReleaseMigrationStore(context: Context) : ReleaseMigrationStore {
    private val preferences = context.applicationContext.getSharedPreferences("release_migration", Context.MODE_PRIVATE)

    override fun load(): MigrationRecord? = preferences.getString(KEY_RECORD, null)?.let { encoded ->
        runCatching { decode(JSONObject(encoded)) }.getOrNull()
    }

    override fun save(record: MigrationRecord) {
        if (!preferences.edit().putString(KEY_RECORD, encode(record).toString()).commit()) {
            throw AppUpdateException("无法保存迁移进度")
        }
    }

    override fun clear() { preferences.edit().remove(KEY_RECORD).apply() }
    override fun promptWasShown(): Boolean = preferences.getBoolean(KEY_PROMPT_SHOWN, false)
    override fun markPromptShown() { preferences.edit().putBoolean(KEY_PROMPT_SHOWN, true).apply() }

    private fun encode(record: MigrationRecord): JSONObject = JSONObject()
        .put("stage", record.stage.name)
        .put("summary", summaryJson(record.summary))
        .put("release", record.release?.let(::releaseJson))
        .put("downloadId", record.downloadId)

    private fun decode(root: JSONObject): MigrationRecord = MigrationRecord(
        stage = MigrationStage.valueOf(root.getString("stage")),
        summary = parseSummary(root.getJSONObject("summary")),
        release = root.optJSONObject("release")?.let(::parseRelease),
        downloadId = if (root.isNull("downloadId")) null else root.getLong("downloadId"),
    )

    private fun summaryJson(value: BackupPreflightResult) = JSONObject()
        .put("format", value.format).put("version", value.version).put("images", value.imageCount)
        .put("analyses", value.analysisCount).put("corrections", value.correctionCount)
        .put("providers", value.providerCount).put("models", value.modelCount)
        .put("protocols", value.protocolCount).put("credentials", value.credentialReentryCount)

    private fun parseSummary(value: JSONObject) = BackupPreflightResult(
        value.getString("format"), value.getInt("version"), value.getInt("images"),
        value.getInt("analyses"), value.getInt("corrections"), value.getInt("providers"),
        value.getInt("models"), value.getInt("protocols"), value.getInt("credentials"), false,
    )

    private fun releaseJson(value: UpdateRelease) = JSONObject()
        .put("version", value.version.toString()).put("tag", value.tagName).put("name", value.releaseName)
        .put("notes", value.notes).put("published", value.publishedAt).put("page", value.pageUrl)
        .put("assetName", value.asset.name).put("url", value.asset.downloadUrl)
        .put("size", value.asset.sizeBytes).put("sha256", value.asset.sha256)

    private fun parseRelease(value: JSONObject) = UpdateRelease(
        version = requireNotNull(SemanticVersion.parse(value.getString("version"))),
        tagName = value.getString("tag"), releaseName = value.getString("name"),
        notes = value.getString("notes"), publishedAt = value.getString("published"),
        pageUrl = value.getString("page"), asset = UpdateAsset(
            value.getString("assetName"), value.getString("url"), value.getLong("size"),
            value.getString("sha256"),
        ),
    )

    private companion object {
        const val KEY_RECORD = "record"
        const val KEY_PROMPT_SHOWN = "prompt_shown"
    }
}

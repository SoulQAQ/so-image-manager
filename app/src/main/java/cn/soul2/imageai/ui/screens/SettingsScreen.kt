package cn.soul2.imageai.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.soul2.imageai.R
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.update.AppUpdateState
import cn.soul2.imageai.update.ReleaseNotesBlock
import cn.soul2.imageai.update.ReleaseNotesMarkdown
import cn.soul2.imageai.update.UpdateRelease
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import cn.soul2.imageai.storage.AppStorageService
import cn.soul2.imageai.storage.AppStorageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    galleryAccessStates: Flow<GalleryAccessState>,
    repository: GalleryRepository,
    unavailableCounts: Flow<Int>,
    onReselectPhotos: () -> Unit,
    onSelectDocumentImages: () -> Unit = {},
    documentImportNotice: String? = null,
    onDocumentImportNoticeConsumed: () -> Unit = {},
    backupNotice: String? = null,
    onBackupNoticeConsumed: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onRestoreBackup: () -> Unit = {},
    onOpenReleaseMigration: () -> Unit = {},
    onRescan: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenGeneralSettings: () -> Unit,
    onOpenHomeModules: () -> Unit = {},
    onOpenAiSettings: () -> Unit,
    onOpenPrivateGallery: () -> Unit,
    onOpenUnprocessedGallery: () -> Unit,
    storageService: AppStorageService? = null,
    appUpdateState: Flow<AppUpdateState> = flowOf(AppUpdateState.Idle),
    onCheckForUpdate: () -> Unit = {},
    onOpenUpdateDetails: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(galleryAccessStates, repository, unavailableCounts),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by appUpdateState.collectAsStateWithLifecycle(initialValue = AppUpdateState.Idle)
    val currentReselect by rememberUpdatedState(onReselectPhotos)
    val currentRescan by rememberUpdatedState(onRescan)
    val currentSystemSettings by rememberUpdatedState(onOpenSystemSettings)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var storage by remember { mutableStateOf<AppStorageSnapshot?>(null) }
    var confirmClearStorage by remember { mutableStateOf(false) }
    LaunchedEffect(storageService) {
        storage = storageService?.let { withContext(Dispatchers.IO) { it.measure() } }
    }
    val rescanMessage = stringResource(R.string.settings_rescan_requested)
    LaunchedEffect(viewModel) {
        viewModel.commands.collect { command ->
            when (command) {
                SettingsCommand.ReselectPhotos -> currentReselect()
                SettingsCommand.SelectDocumentImages -> onSelectDocumentImages()
                SettingsCommand.Rescan -> {
                    currentRescan()
                    launch { snackbar.showSnackbar(rescanMessage) }
                }
                SettingsCommand.OpenSystemSettings -> currentSystemSettings()
            }
        }
    }
    LaunchedEffect(documentImportNotice) {
        documentImportNotice?.let {
            snackbar.showSnackbar(it)
            onDocumentImportNoticeConsumed()
        }
    }
    LaunchedEffect(backupNotice) {
        backupNotice?.let {
            snackbar.showSnackbar(it)
            onBackupNoticeConsumed()
        }
    }
    Box(Modifier.fillMaxSize()) {
        SettingsContent(
            state = state,
            onGeneral = onOpenGeneralSettings,
            onHomeModules = onOpenHomeModules,
            onProviders = onOpenAiSettings,
            onImport = viewModel::selectDocumentImages,
            onRescan = viewModel::rescan,
            onSystemSettings = viewModel::openSystemSettings,
            onPrivateGallery = onOpenPrivateGallery,
            onUnprocessedGallery = onOpenUnprocessedGallery,
            onExportBackup = onExportBackup,
            onRestoreBackup = onRestoreBackup,
            onReleaseMigration = onOpenReleaseMigration,
            storage = storage,
            onClearStorage = { confirmClearStorage = true },
            updateState = updateState,
            onUpdate = {
                when (updateState) {
                    AppUpdateState.Idle,
                    is AppUpdateState.UpToDate,
                    -> onCheckForUpdate()
                    AppUpdateState.Checking,
                    is AppUpdateState.Restoring,
                    -> Unit
                    is AppUpdateState.Available,
                    is AppUpdateState.Downloading,
                    is AppUpdateState.Ready,
                    is AppUpdateState.InstallationFailed,
                    is AppUpdateState.Failed,
                    -> onOpenUpdateDetails()
                }
            },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (confirmClearStorage) {
        AlertDialog(
            onDismissRequest = { confirmClearStorage = false },
            title = { Text("清理缓存？") },
            text = { Text("只删除可重新生成的缩略图缓存和临时文件，不会删除原图、AI 标注或设置。") },
            confirmButton = { Button(onClick = {
                confirmClearStorage = false
                scope.launch {
                    storage = withContext(Dispatchers.IO) { storageService?.clearRebuildableFiles() }
                    snackbar.showSnackbar("缓存已清理")
                }
            }) { Text("清理") } },
            dismissButton = { TextButton(onClick = { confirmClearStorage = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onGeneral: () -> Unit,
    onHomeModules: () -> Unit,
    onProviders: () -> Unit,
    onImport: () -> Unit,
    onRescan: () -> Unit,
    onSystemSettings: () -> Unit,
    onPrivateGallery: () -> Unit,
    onUnprocessedGallery: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onReleaseMigration: () -> Unit,
    storage: AppStorageSnapshot?,
    onClearStorage: () -> Unit,
    updateState: AppUpdateState,
    onUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember(context) {
        @Suppress("DEPRECATION")
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    Column(Modifier.fillMaxSize().testTag("screen_settings")) {
        TopAppBar(title = { Text("设置") }, windowInsets = WindowInsets(0, 0, 0, 0))
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionHeader("图库状态") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    StatusValue("图片总数", state.indexedCount, Modifier.weight(1f))
                    StatusValue("已分析", state.analyzedCount, Modifier.weight(1f))
                    StatusValue("不可访问", state.unavailableCount, Modifier.weight(1f))
                }
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }

            item { SectionHeader("常规设置") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    title = "分析与显示",
                    subtitle = "每日上限、并发、图库显示与分析规则",
                    onClick = onGeneral,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    title = "首页模块",
                    subtitle = "配置来源、布局和显示顺序",
                    onClick = onHomeModules,
                )
            }

            item { SectionHeader("模型提供方") }
            item {
                SettingsRow(
                    icon = Icons.Outlined.SmartToy,
                    title = "模型提供方",
                    subtitle = "分别管理主分区和隐私分区的调用顺序",
                    onClick = onProviders,
                )
            }

            item { SectionHeader("图库设置") }
            item {
                SettingsRow(Icons.Outlined.AddPhotoAlternate, "添加图片", "从文件管理器添加 SoIM 可持续访问的图片", onImport)
            }
            item {
                SettingsRow(Icons.Outlined.Sync, "重新扫描", "重新同步系统图库与索引状态", onRescan)
            }
            item {
                SettingsRow(Icons.Outlined.FolderOpen, "隐私分区", "查看仅在本机保存的隐私图片", onPrivateGallery)
            }
            item {
                SettingsRow(Icons.Outlined.FolderOpen, "未处理图片", "查看尚未经过 AI 分析的图片", onUnprocessedGallery)
            }
            item { SectionHeader("备份与恢复") }
            item {
                SettingsRow(
                    Icons.Outlined.Upload,
                    "导出备份",
                    "导出索引、AI 结果、用户修正和配置，不包含原图与 API Key",
                    onExportBackup,
                )
            }
            item {
                SettingsRow(
                    Icons.Outlined.Download,
                    "恢复备份",
                    "验证后合并数据，并按图库身份与文件指纹重新关联图片",
                    onRestoreBackup,
                )
            }
            item {
                SettingsRow(
                    Icons.Outlined.SystemUpdate,
                    "迁移到正式版",
                    "备份数据并准备长期签名的正式安装包",
                    onReleaseMigration,
                )
            }

            item { SectionHeader("APP 权限") }
            item {
                SettingsRow(Icons.Outlined.Settings, "前往系统设置", "管理照片和后台运行权限", onSystemSettings)
            }

            item { SectionHeader("存储占用") }
            item {
                ListItem(
                    headlineContent = { Text("SoIM 数据") },
                    supportingContent = { Text(storage?.let {
                        "数据库 ${formatBytes(it.databaseBytes)} · 缓存 ${formatBytes(it.cacheBytes)} · 临时文件 ${formatBytes(it.temporaryBytes)}"
                    } ?: "正在统计…") },
                    trailingContent = { Text(storage?.let { formatBytes(it.totalBytes) } ?: "-") },
                )
            }
            item {
                SettingsRow(Icons.Outlined.DeleteOutline, "清理缓存", "删除可重新生成的缓存和临时文件", onClearStorage)
            }

            item { SectionHeader("关于") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    headlineContent = { Text("SoIM") },
                    supportingContent = { Text("本地 AI 图片管理") },
                    trailingContent = { Text("v$versionName") },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "检查更新",
                    subtitle = updateState.subtitle(versionName),
                    onClick = onUpdate,
                )
            }
        }
    }
}

@Composable
internal fun UpdateDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDiscardReadyUpdate: () -> Unit,
    onInstall: (File) -> Unit,
) {
    when (state) {
        AppUpdateState.Idle,
        AppUpdateState.Checking,
        is AppUpdateState.Restoring,
        is AppUpdateState.UpToDate,
        -> Unit
        is AppUpdateState.Available -> ReleaseDialog(
            release = state.release,
            title = "发现新版本 ${state.release.tagName}",
            confirmLabel = "下载更新",
            onConfirm = onDownload,
            onDismiss = onDismiss,
        )
        is AppUpdateState.Downloading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("正在下载 ${state.release.tagName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val targetProgress = if (state.totalBytes > 0L) {
                        (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val progress by animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = tween(durationMillis = 450),
                        label = "update_download_progress",
                    )
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}")
                    Text(
                        state.bytesPerSecond?.let { "下载速度：${formatTransferRate(it)}" }
                            ?: if (state.downloadedBytes > 0L) "下载速度：计算中…" else "正在连接…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCancelDownload()
                        onDismiss()
                    },
                ) { Text("取消下载") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("后台下载") }
            },
        )
        is AppUpdateState.Ready -> ReleaseDialog(
            release = state.release,
            title = "更新已准备完成",
            confirmLabel = "安装",
            onConfirm = { onInstall(state.apk) },
            onDismiss = onDismiss,
            secondaryLabel = "删除并跳过",
            onSecondary = onDiscardReadyUpdate,
        )
        is AppUpdateState.InstallationFailed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("无法安装更新") },
            text = { Text(state.message) },
            confirmButton = {
                Button(onClick = { onInstall(state.apk) }) { Text("重试安装") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onDiscardReadyUpdate) { Text("删除并跳过") }
                    TextButton(onClick = onDismiss) { Text("稍后") }
                }
            },
        )
        is AppUpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新失败") },
            text = { Text(state.message) },
            confirmButton = {
                Button(
                    onClick = if (state.release == null) onCheck else onDownload,
                ) {
                    Text(if (state.release == null) "重新检查" else "重新下载")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
    }
}

@Composable
private fun ReleaseDialog(
    release: UpdateRelease,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    supportingText: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("安装包大小：${formatBytes(release.asset.sizeBytes)}")
                supportingText?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (release.notes.isNotBlank()) {
                    ReleaseNotesMarkdownContent(release.notes, release.tagName)
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            Row {
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                }
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

@Composable
internal fun ReleaseNotesMarkdownContent(
    markdown: String,
    leadingHeadingToOmit: String? = null,
) {
    val blocks = remember(markdown, leadingHeadingToOmit) {
        ReleaseNotesMarkdown.parse(markdown).let { parsed ->
            if (
                parsed.firstOrNull() is ReleaseNotesBlock.Heading &&
                (parsed.first() as ReleaseNotesBlock.Heading).text.equals(
                    leadingHeadingToOmit,
                    ignoreCase = true,
                )
            ) {
                parsed.drop(1)
            } else {
                parsed
            }
        }
    }
    blocks.forEach { block ->
        when (block) {
            is ReleaseNotesBlock.Heading -> Text(
                text = block.text,
                style = when (block.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
            )
            is ReleaseNotesBlock.Bullet -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(block.ordinal?.let { "$it." } ?: "•")
                Text(block.text, modifier = Modifier.weight(1f))
            }
            is ReleaseNotesBlock.Paragraph -> Text(block.text)
        }
    }
}

private fun AppUpdateState.subtitle(currentVersion: String): String = when (this) {
    AppUpdateState.Idle -> "当前 v$currentVersion，点击检查 GitHub Release"
    AppUpdateState.Checking -> "正在检查 GitHub Release…"
    is AppUpdateState.Restoring -> "正在恢复 ${release.tagName} 的安装状态"
    is AppUpdateState.UpToDate -> "已是最新版本 v$currentVersion"
    is AppUpdateState.Available -> "发现 ${release.tagName}，点击查看"
    is AppUpdateState.Downloading -> "正在下载 ${release.tagName}"
    is AppUpdateState.Ready -> "${release.tagName} 已下载，点击安装"
    is AppUpdateState.InstallationFailed -> "${release.tagName} 安装未完成，点击重试"
    is AppUpdateState.Failed -> "更新失败，点击查看"
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024L * 1024L))
    else -> "%.1f KB".format(bytes.toDouble() / 1024L)
}

private fun formatTransferRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L ->
        "%.1f MB/s".format(bytesPerSecond.toDouble() / (1024L * 1024L))
    bytesPerSecond >= 1024L -> "%.1f KB/s".format(bytesPerSecond.toDouble() / 1024L)
    else -> "$bytesPerSecond B/s"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StatusValue(label: String, count: Int?, modifier: Modifier) {
    Column(modifier) {
        Text(count?.toString() ?: "-", style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null, Modifier.size(22.dp)) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 2) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)) },
    )
}

package cn.soul2.imageai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    galleryAccessStates: Flow<GalleryAccessState>,
    repository: GalleryRepository,
    unavailableCounts: Flow<Int>,
    onReselectPhotos: () -> Unit,
    onSelectDocumentImages: () -> Unit = {},
    documentImportNotice: String? = null,
    onDocumentImportNoticeConsumed: () -> Unit = {},
    onRescan: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenGeneralSettings: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenPrivateGallery: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(galleryAccessStates, repository, unavailableCounts),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentReselect by rememberUpdatedState(onReselectPhotos)
    val currentRescan by rememberUpdatedState(onRescan)
    val currentSystemSettings by rememberUpdatedState(onOpenSystemSettings)
    val snackbar = remember { SnackbarHostState() }
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
    Box(Modifier.fillMaxSize()) {
        SettingsContent(
            state = state,
            onGeneral = onOpenGeneralSettings,
            onProviders = onOpenAiSettings,
            onImport = viewModel::selectDocumentImages,
            onRescan = viewModel::rescan,
            onReselect = viewModel::reselectPhotos,
            onSystemSettings = viewModel::openSystemSettings,
            onPrivateGallery = onOpenPrivateGallery,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onGeneral: () -> Unit,
    onProviders: () -> Unit,
    onImport: () -> Unit,
    onRescan: () -> Unit,
    onReselect: () -> Unit,
    onSystemSettings: () -> Unit,
    onPrivateGallery: () -> Unit,
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
                SettingsRow(Icons.Outlined.FolderOpen, "从文件管理器选择图片", "导入应用可持续访问的图片", onImport)
            }
            item {
                SettingsRow(Icons.Outlined.Sync, "重新扫描", "重新同步系统图库与索引状态", onRescan)
            }
            item {
                SettingsRow(Icons.Outlined.FolderOpen, "隐私分区", "查看仅在本机保存的隐私图片", onPrivateGallery)
            }

            item { SectionHeader("APP 权限") }
            item {
                SettingsRow(
                    Icons.Outlined.AddPhotoAlternate,
                    "重新选择照片",
                    state.permissionLabel?.displayName() ?: "读取中",
                    onReselect,
                )
            }
            item {
                SettingsRow(Icons.Outlined.Settings, "前往系统设置", "管理照片和后台运行权限", onSystemSettings)
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
        }
    }
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

private fun SettingsPermissionLabel.displayName(): String = when (this) {
    SettingsPermissionLabel.Full -> "完整访问"
    SettingsPermissionLabel.Partial -> "部分访问"
    SettingsPermissionLabel.Denied -> "未授权"
}

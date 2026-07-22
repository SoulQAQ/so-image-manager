package cn.soul2.imageai.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.soul2.imageai.R
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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
    onOpenAiSettings: () -> Unit,
) {
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            galleryAccessStates = galleryAccessStates,
            repository = repository,
            unavailableCounts = unavailableCounts,
        ),
    )
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val currentReselectPhotos by rememberUpdatedState(onReselectPhotos)
    val currentRescan by rememberUpdatedState(onRescan)
    val currentOpenSystemSettings by rememberUpdatedState(onOpenSystemSettings)
    val rescanRequestedMessage = stringResource(R.string.settings_rescan_requested)
    val currentRescanRequestedMessage by rememberUpdatedState(rescanRequestedMessage)
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.commands.collect { command ->
            when (command) {
                SettingsCommand.ReselectPhotos -> currentReselectPhotos()
                SettingsCommand.SelectDocumentImages -> onSelectDocumentImages()
                SettingsCommand.Rescan -> {
                    currentRescan()
                    launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(currentRescanRequestedMessage)
                    }
                }
                SettingsCommand.OpenSystemSettings -> currentOpenSystemSettings()
            }
        }
    }
    LaunchedEffect(documentImportNotice) {
        documentImportNotice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            onDocumentImportNoticeConsumed()
        }
    }
    Box(Modifier.fillMaxSize()) {
        SettingsContent(
            uiState = uiState,
            onReselectPhotos = settingsViewModel::reselectPhotos,
            onSelectDocumentImages = settingsViewModel::selectDocumentImages,
            onRescan = settingsViewModel::rescan,
            onOpenSystemSettings = settingsViewModel::openSystemSettings,
            onOpenAiSettings = onOpenAiSettings,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    onReselectPhotos: () -> Unit,
    onSelectDocumentImages: () -> Unit,
    onRescan: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("screen_settings")) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_settings)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
        ) {
            item {
                Text(
                    text = "图库状态",
                    modifier = Modifier.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsCountText(
                        label = "图片总数",
                        count = uiState.indexedCount,
                        modifier = Modifier.weight(1f),
                    )
                    SettingsCountText(
                        label = "已分析",
                        count = uiState.analyzedCount,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider()
            }
            item {
                Text(
                    text = "常规设置",
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                SettingsAction(
                    labelRes = R.string.settings_rescan,
                    icon = Icons.Outlined.Sync,
                    onClick = onRescan,
                )
            }
            item {
                Text("模型提供方", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SettingsAction(
                    labelRes = R.string.settings_ai_models,
                    icon = Icons.Outlined.SmartToy,
                    onClick = onOpenAiSettings,
                )
            }
            item {
                Text("图库设置", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SettingsAction(labelRes = R.string.settings_import_documents, icon = Icons.Outlined.FolderOpen, onClick = onSelectDocumentImages)
            }
            item {
                Text("APP权限", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SettingsAction(
                    labelRes = R.string.settings_reselect_photos,
                    icon = Icons.Outlined.AddPhotoAlternate,
                    onClick = onReselectPhotos,
                )
            }
            item {
                SettingsAction(
                    labelRes = R.string.gallery_permission_open_settings,
                    icon = Icons.Outlined.Settings,
                    onClick = onOpenSystemSettings,
                )
            }
            item {
                Text("关于", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("SoIM 本地 AI 图片管理", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsCount(@StringRes labelRes: Int, count: Int?, modifier: Modifier) {
    Column(modifier) {
        Text(
            text = count?.toString() ?: stringResource(R.string.settings_loading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCountText(label: String, count: Int?, modifier: Modifier) {
    Column(modifier) {
        Text(text = count?.toString() ?: stringResource(R.string.settings_loading), style = MaterialTheme.typography.titleMedium)
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsAction(
    @StringRes labelRes: Int,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val label = stringResource(labelRes)
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = label, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
}

@StringRes
private fun permissionLabelRes(label: SettingsPermissionLabel): Int = when (label) {
    SettingsPermissionLabel.Full -> R.string.settings_permission_full
    SettingsPermissionLabel.Partial -> R.string.settings_permission_partial
    SettingsPermissionLabel.Denied -> R.string.settings_permission_denied
}

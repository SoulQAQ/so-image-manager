package cn.soul2.imageai

import android.content.Intent
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.app.RecoverableSecurityException
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.provider.Settings
import android.provider.MediaStore
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.permission.GalleryPermissionPolicy
import cn.soul2.imageai.media.permission.GalleryPermissionRequestHistoryPolicy
import cn.soul2.imageai.media.permission.GalleryReselectionDestination
import cn.soul2.imageai.media.permission.GalleryReselectionPolicy
import cn.soul2.imageai.ui.app.SoImageManagerApp
import cn.soul2.imageai.ui.onboarding.GalleryAccessViewModel
import cn.soul2.imageai.ui.onboarding.GalleryPermissionRequestCoordinator
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.data.db.entity.ImageSource
import cn.soul2.imageai.update.AndroidUpdateDownloadGateway
import cn.soul2.imageai.backup.SoimBackupService
import cn.soul2.imageai.backup.readForSizeValidation
import cn.soul2.imageai.ai.debug.AiProtocolDebugReport
import java.nio.charset.StandardCharsets
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as SoImApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.appUpdateManager.onAppStarted()
        enableEdgeToEdge()
        setContent {
            SoImageManagerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GalleryPermissionHost()
                }
            }
        }
    }

    @Composable
    private fun GalleryPermissionHost() {
        val accessViewModel: GalleryAccessViewModel = viewModel(
            factory = GalleryAccessViewModel.factory(
                permissionMonitor = container.galleryPermissionMonitor,
                onboardingRepository = container.galleryOnboardingRepository,
            ),
        )
        val uiState by accessViewModel.uiState.collectAsStateWithLifecycle()
        val coroutineScope = rememberCoroutineScope()
        var documentImportNotice by remember { mutableStateOf<String?>(null) }
        var backupNotice by remember { mutableStateOf<String?>(null) }
        var protocolDebugRequest by remember {
            mutableStateOf<Triple<String, cn.soul2.imageai.data.db.entity.ImagePartition, (Result<AiProtocolDebugReport>) -> Unit>?>(null)
        }
        var pendingUpdatePath by rememberSaveable { mutableStateOf<String?>(null) }
        val permissionRequestCoordinator = remember { GalleryPermissionRequestCoordinator() }
        val permissionRequestInFlight by
            permissionRequestCoordinator.inFlight.collectAsStateWithLifecycle()
        val observePassiveAccess: (GalleryAccessState) -> Unit = { access ->
            coroutineScope.launch {
                container.gallerySyncAccessCoordinator.onAccessAvailable(access)
            }
        }
        val reconcileExplicitSelection: (GalleryAccessState) -> Unit = { access ->
            coroutineScope.launch {
                container.gallerySyncAccessCoordinator.onExplicitSelectionChanged(access)
            }
        }
        LaunchedEffect(uiState.isLoading, uiState.galleryAccessState) {
            if (!uiState.isLoading) {
                when (val access = uiState.galleryAccessState) {
                    GalleryAccessState.Full,
                    GalleryAccessState.Partial,
                    -> observePassiveAccess(access)
                    is GalleryAccessState.Denied -> Unit
                }
            }
        }
        LifecycleResumeEffect(uiState.permissionRequested) {
            if (!permissionRequestInFlight) {
                uiState.permissionRequested?.let { permissionRequested ->
                    accessViewModel.refresh(
                        canRequestAgain = canRequestGalleryPermissionAgain(permissionRequested),
                    )
                }
            }
            onPauseOrDispose { }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            permissionRequestCoordinator.complete()
            accessViewModel.onPermissionResult(
                canRequestAgain = canRequestGalleryPermissionAgain(permissionRequested = true),
                onExplicitSelectionChanged = reconcileExplicitSelection,
            )
        }
        val documentPickerLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val uris = result.data?.let(::documentUris).orEmpty()
            if (uris.isNotEmpty()) {
                coroutineScope.launch {
                    val imported = container.documentImageImporter.import(uris)
                    documentImportNotice = getString(
                        R.string.settings_document_import_result,
                        imported.importedCount,
                        imported.rejectedCount,
                    )
                }
            }
        }
        val backupExportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(SoimBackupService.MIME_TYPE),
        ) { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    val result = runCatching {
                        val (json, summary) = container.backupService.exportJson()
                        contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            output.write(json.toByteArray(StandardCharsets.UTF_8))
                        } ?: error("无法写入备份文件")
                        summary
                    }
                    backupNotice = result.fold(
                        onSuccess = { "备份完成：${it.imageCount} 张图片，${it.analysisCount} 条分析记录" },
                        onFailure = { "备份失败：${it.message ?: "无法写入文件"}" },
                    )
                }
            }
        }
        val backupRestoreLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    val result = runCatching {
                        val bytes = contentResolver.openInputStream(uri)?.use { input ->
                            input.readForSizeValidation(MAX_BACKUP_BYTES)
                        } ?: error("无法读取备份文件")
                        require(bytes.size <= MAX_BACKUP_BYTES) { "备份文件超过 32 MB" }
                        container.backupService.restoreJson(bytes.toString(StandardCharsets.UTF_8))
                    }
                    backupNotice = result.fold(
                        onSuccess = {
                            "恢复完成：关联 ${it.matchedImages} 张，未匹配 ${it.unmatchedImages} 张，恢复 ${it.restoredAnalyses} 条分析记录，冲突 ${it.conflicts} 条"
                        },
                        onFailure = { "恢复失败：${it.message ?: "备份内容无效"}" },
                    )
                }
            }
        }
        val protocolDebugPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val request = protocolDebugRequest
            protocolDebugRequest = null
            if (uri != null && request != null) {
                coroutineScope.launch {
                    request.third(
                        runCatching {
                            container.aiProtocolDebugger.test(request.first, uri.toString(), request.second)
                        },
                    )
                }
            }
        }
        val installPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            val pending = pendingUpdatePath?.let(::File)
            if (pending != null) {
                if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    packageManager.canRequestPackageInstalls()
                ) {
                    openUpdateInstaller(pending)
                } else {
                    container.appUpdateManager.reportInstallationFailure(
                        pending,
                        "需要允许 SoIM 安装应用，才能继续更新。",
                    )
                }
            }
            pendingUpdatePath = null
        }
        var pendingDeleteImages by remember { mutableStateOf<List<GalleryImage>>(emptyList()) }
        var deleteNeedsRetry by remember { mutableStateOf(false) }
        val deleteLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK && pendingDeleteImages.isNotEmpty()) {
                if (deleteNeedsRetry) {
                    pendingDeleteImages.forEach { image ->
                        contentResolver.delete(Uri.parse(image.contentUri), null, null)
                    }
                }
                coroutineScope.launch {
                    container.gallerySelectionActions.removeFromSoim(
                        pendingDeleteImages.map(GalleryImage::localId),
                    )
                }
            }
            pendingDeleteImages = emptyList()
            deleteNeedsRetry = false
        }
        val launchPermissionRequest: () -> Unit = {
            coroutineScope.launch {
                permissionRequestCoordinator.persistThenLaunch(
                    persistRequestHistory = accessViewModel::markPermissionRequested,
                    launchRequest = {
                        permissionLauncher.launch(
                            GalleryPermissionPolicy
                                .requiredPermissions(android.os.Build.VERSION.SDK_INT)
                                .toTypedArray(),
                        )
                    },
                )
            }
        }
        val launchGalleryReselection: () -> Unit = {
            when (
                GalleryReselectionPolicy.destination(
                    android.os.Build.VERSION.SDK_INT,
                    uiState.galleryAccessState,
                )
            ) {
                GalleryReselectionDestination.PermissionRequest -> launchPermissionRequest()
                GalleryReselectionDestination.AppSettings -> openAppSettings()
            }
        }
        val launchDocumentPicker: () -> Unit = {
            documentPickerLauncher.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                },
            )
        }
        val installUpdate: (File) -> Unit = { apk ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                pendingUpdatePath = apk.absolutePath
                try {
                    installPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                } catch (_: ActivityNotFoundException) {
                    pendingUpdatePath = null
                    container.appUpdateManager.reportInstallationFailure(
                        apk,
                        "无法打开安装应用权限设置，请在系统设置中允许 SoIM 安装应用。",
                    )
                } catch (_: SecurityException) {
                    pendingUpdatePath = null
                    container.appUpdateManager.reportInstallationFailure(
                        apk,
                        "系统拒绝打开安装应用权限设置。",
                    )
                }
            } else {
                openUpdateInstaller(apk)
            }
        }

        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SoImageManagerApp(
                galleryRepository = container.galleryRepository,
                imageSearchRepository = container.imageSearchRepository,
                aiConfigurationRepository = container.aiConfigurationRepository,
                aiCredentialStore = container.aiCredentialStore,
                singleImageAnalyzer = container.singleImageAnalysisService,
                canonicalMetadataRepository = container.canonicalMetadataRepository,
                homeConfigurationRepository = container.homeConfigurationRepository,
                appStorageService = container.appStorageService,
                syncRuns = container.gallerySyncRuns,
                lastSyncCompletedAt = container.galleryLastSyncCompletedAt,
                galleryUnavailableCounts = container.galleryUnavailableCounts,
                galleryAccessState = uiState.galleryAccessState,
                galleryAccessStates = container.galleryPermissionMonitor.state,
                showGalleryOnboarding = uiState.showOnboarding,
                isGalleryPermissionRecovery = uiState.isPermissionRecovery,
                isGalleryPermissionRequestInFlight = permissionRequestInFlight,
                onRequestGalleryPermission = launchPermissionRequest,
                onOpenAppSettings = ::openAppSettings,
                onDismissGalleryOnboarding = accessViewModel::dismissOnboarding,
                onRequestGalleryReselection = launchGalleryReselection,
                onSelectDocumentImages = launchDocumentPicker,
                documentImportNotice = documentImportNotice,
                onDocumentImportNoticeConsumed = { documentImportNotice = null },
                backupNotice = backupNotice,
                onBackupNoticeConsumed = { backupNotice = null },
                onExportBackup = {
                    backupExportLauncher.launch(SoimBackupService.DEFAULT_FILE_NAME)
                },
                onRestoreBackup = {
                    backupRestoreLauncher.launch(arrayOf(SoimBackupService.MIME_TYPE, "text/json"))
                },
                onDebugProtocol = { providerId, partition, callback ->
                    protocolDebugRequest = Triple(providerId, partition, callback)
                    protocolDebugPicker.launch(arrayOf("image/*"))
                },
                onRetryGallerySync = container.mediaSyncScheduler::retry,
                onRequestGalleryReconciliation =
                    container.mediaSyncScheduler::requestReconciliation,
                gallerySelectionActions = container.gallerySelectionActions,
                batchAnalysisRepository = container.batchAnalysisRepository,
                appUpdateState = container.appUpdateManager.state,
                installedUpdateNotice = container.appUpdateManager.installedUpdateNotice,
                onCheckForUpdate = container.appUpdateManager::checkForUpdate,
                onDownloadUpdate = container.appUpdateManager::downloadUpdate,
                onCancelUpdateDownload = container.appUpdateManager::cancelDownload,
                onDiscardReadyUpdate = container.appUpdateManager::discardReadyUpdate,
                onDismissUpdateFailure = container.appUpdateManager::dismissFailure,
                onDismissInstalledUpdateNotice =
                    container.appUpdateManager::dismissInstalledUpdateNotice,
                onInstallUpdate = installUpdate,
                onShareImages = ::shareImages,
                onDeleteImages = { images ->
                    val deletable = images.filter { it.source == ImageSource.MEDIA_STORE }
                    if (deletable.isEmpty()) return@SoImageManagerApp
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pendingDeleteImages = deletable
                        val request = MediaStore.createDeleteRequest(
                            contentResolver,
                            deletable.map { Uri.parse(it.contentUri) },
                        )
                        deleteLauncher.launch(
                            androidx.activity.result.IntentSenderRequest.Builder(
                                request.intentSender,
                            ).build(),
                        )
                    } else {
                        try {
                            deletable.forEach { image ->
                                contentResolver.delete(Uri.parse(image.contentUri), null, null)
                            }
                            coroutineScope.launch {
                                container.gallerySelectionActions.removeFromSoim(
                                    deletable.map(GalleryImage::localId),
                                )
                            }
                        } catch (error: RecoverableSecurityException) {
                            pendingDeleteImages = deletable
                            deleteNeedsRetry = true
                            deleteLauncher.launch(
                                androidx.activity.result.IntentSenderRequest.Builder(
                                    error.userAction.actionIntent.intentSender,
                                ).build(),
                            )
                        }
                    }
                },
            )
        }
    }

    private fun canRequestGalleryPermissionAgain(permissionRequested: Boolean): Boolean =
        GalleryPermissionRequestHistoryPolicy.canRequestAgain(
            permissionRequested = permissionRequested,
            rationaleResults = GalleryPermissionPolicy
                .requiredPermissions(android.os.Build.VERSION.SDK_INT)
                .map { permission ->
                    ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
                },
        )

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun documentUris(intent: Intent): List<Uri> = buildList {
        intent.data?.let(::add)
        intent.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index -> add(clipData.getItemAt(index).uri) }
        }
    }.distinct()

    private fun shareImages(images: List<GalleryImage>) {
        if (images.isEmpty()) return
        val uris = ArrayList(images.map { Uri.parse(it.contentUri) })
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    clipData = ClipData.newRawUri("images", uris.first()).also { clip ->
                        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(cn.soul2.imageai.R.string.gallery_selection_share),
            ),
        )
    }

    private fun openUpdateInstaller(apk: File) {
        val downloadsRoot = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsRoot == null) {
            container.appUpdateManager.invalidateReadyUpdate(
                "更新安装目录不可用，请重新下载。",
            )
            return
        }
        val updateDirectory = runCatching { File(downloadsRoot, "updates").canonicalFile }
            .getOrElse {
                container.appUpdateManager.invalidateReadyUpdate(
                    "无法访问更新安装目录，请重新下载。",
                )
                return
            }
        val verifiedApk = runCatching { apk.canonicalFile }.getOrElse {
            container.appUpdateManager.invalidateReadyUpdate(
                "无法访问已下载的更新安装包，请重新下载。",
            )
            return
        }
        if (verifiedApk.parentFile != updateDirectory || !verifiedApk.isFile) {
            container.appUpdateManager.invalidateReadyUpdate(
                "已下载的更新安装包已丢失，请重新下载。",
            )
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", verifiedApk)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, AndroidUpdateDownloadGateway.APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        } catch (_: ActivityNotFoundException) {
            container.appUpdateManager.reportInstallationFailure(
                verifiedApk,
                "系统中没有可用的 APK 安装器。",
            )
        } catch (_: SecurityException) {
            container.appUpdateManager.reportInstallationFailure(
                verifiedApk,
                "系统拒绝打开更新安装包，请检查安装应用权限。",
            )
        } catch (_: IllegalArgumentException) {
            container.appUpdateManager.invalidateReadyUpdate(
                "更新安装包无法共享给系统安装器，请重新下载。",
            )
        }
    }

    companion object {
        private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    }
}

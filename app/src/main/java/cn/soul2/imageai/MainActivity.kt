package cn.soul2.imageai

import android.content.Intent
import android.content.ClipData
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
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as SoImApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val installPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            val pending = pendingUpdatePath?.let(::File)
            pendingUpdatePath = null
            if (
                pending != null &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())
            ) {
                openUpdateInstaller(pending)
            }
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
                installPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ),
                )
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
                onRetryGallerySync = container.mediaSyncScheduler::retry,
                onRequestGalleryReconciliation =
                    container.mediaSyncScheduler::requestReconciliation,
                gallerySelectionActions = container.gallerySelectionActions,
                batchAnalysisRepository = container.batchAnalysisRepository,
                appUpdateState = container.appUpdateManager.state,
                onCheckForUpdate = container.appUpdateManager::checkForUpdate,
                onDownloadUpdate = container.appUpdateManager::downloadUpdate,
                onCancelUpdateDownload = container.appUpdateManager::cancelDownload,
                onDismissUpdateFailure = container.appUpdateManager::dismissFailure,
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
        val downloadsRoot = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val updateDirectory = File(downloadsRoot, "updates").canonicalFile
        val verifiedApk = apk.canonicalFile
        if (verifiedApk.parentFile != updateDirectory || !verifiedApk.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", verifiedApk)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, AndroidUpdateDownloadGateway.APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }
}

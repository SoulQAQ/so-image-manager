package cn.soul2.imageai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.permission.GalleryPermissionPolicy
import cn.soul2.imageai.media.permission.GalleryPermissionRequestHistoryPolicy
import cn.soul2.imageai.ui.app.SoImageManagerApp
import cn.soul2.imageai.ui.onboarding.GalleryAccessViewModel
import cn.soul2.imageai.ui.onboarding.GalleryPermissionRequestCoordinator
import cn.soul2.imageai.ui.theme.SoImageManagerTheme
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
                    GalleryPermissionHost {
                        container.mediaSyncScheduler.requestInitial()
                    }
                }
            }
        }
    }

    @Composable
    private fun GalleryPermissionHost(
        onAccessAvailable: (GalleryAccessState) -> Unit = {},
    ) {
        val accessViewModel: GalleryAccessViewModel = viewModel(
            factory = GalleryAccessViewModel.factory(
                permissionMonitor = container.galleryPermissionMonitor,
                onboardingRepository = container.galleryOnboardingRepository,
            ),
        )
        val uiState by accessViewModel.uiState.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        val permissionRequestCoordinator = remember { GalleryPermissionRequestCoordinator() }
        val permissionRequestInFlight by permissionRequestCoordinator.inFlight.collectAsState()
        LaunchedEffect(uiState.isLoading, uiState.galleryAccessState) {
            if (!uiState.isLoading) {
                when (val access = uiState.galleryAccessState) {
                    GalleryAccessState.Full,
                    GalleryAccessState.Partial,
                    -> onAccessAvailable(access)
                    is GalleryAccessState.Denied -> Unit
                }
            }
        }
        LifecycleResumeEffect(uiState.permissionRequested) {
            uiState.permissionRequested?.let { permissionRequested ->
                accessViewModel.refresh(
                    canRequestAgain = canRequestGalleryPermissionAgain(permissionRequested),
                )
            }
            onPauseOrDispose { }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            permissionRequestCoordinator.complete()
            accessViewModel.onPermissionResult(
                canRequestAgain = canRequestGalleryPermissionAgain(permissionRequested = true),
                onAccessAvailable = onAccessAvailable,
            )
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

        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SoImageManagerApp(
                galleryRepository = container.galleryRepository,
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
                onRequestGalleryReselection = launchPermissionRequest,
                onRetryGallerySync = container.mediaSyncScheduler::retry,
                onRequestGalleryReconciliation =
                    container.mediaSyncScheduler::requestReconciliation,
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
}

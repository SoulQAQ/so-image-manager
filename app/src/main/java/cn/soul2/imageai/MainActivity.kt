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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.media.permission.GalleryPermissionPolicy
import cn.soul2.imageai.ui.app.SoImageManagerApp
import cn.soul2.imageai.ui.onboarding.GalleryAccessViewModel
import cn.soul2.imageai.ui.theme.SoImageManagerTheme

class MainActivity : ComponentActivity() {
    private var requestedGalleryPermission = false

    private val container: AppContainer
        get() = (application as SoImApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedGalleryPermission = savedInstanceState?.getBoolean(REQUESTED_PERMISSION_KEY) ?: false
        enableEdgeToEdge()
        setContent {
            SoImageManagerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GalleryPermissionHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.galleryPermissionMonitor.refresh(canRequestGalleryPermissionAgain())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(REQUESTED_PERMISSION_KEY, requestedGalleryPermission)
        super.onSaveInstanceState(outState)
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
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            accessViewModel.onPermissionResult(
                canRequestAgain = canRequestGalleryPermissionAgain(),
                onAccessAvailable = onAccessAvailable,
            )
        }
        val launchPermissionRequest = {
            requestedGalleryPermission = true
            permissionLauncher.launch(
                GalleryPermissionPolicy.requiredPermissions(android.os.Build.VERSION.SDK_INT)
                    .toTypedArray(),
            )
        }

        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SoImageManagerApp(
                galleryAccessState = uiState.galleryAccessState,
                showGalleryOnboarding = uiState.showOnboarding,
                isGalleryPermissionRecovery = uiState.isPermissionRecovery,
                onRequestGalleryPermission = launchPermissionRequest,
                onOpenAppSettings = ::openAppSettings,
                onDismissGalleryOnboarding = accessViewModel::dismissOnboarding,
                onRequestGalleryReselection = launchPermissionRequest,
            )
        }
    }

    private fun canRequestGalleryPermissionAgain(): Boolean {
        if (!requestedGalleryPermission) return true
        return GalleryPermissionPolicy.requiredPermissions(android.os.Build.VERSION.SDK_INT)
            .any { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
            }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private companion object {
        const val REQUESTED_PERMISSION_KEY = "gallery_permission_requested"
    }
}

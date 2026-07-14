package cn.soul2.imageai.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.gallery.ImageDetailDestination
import cn.soul2.imageai.ui.gallery.ImageDetailScreen
import cn.soul2.imageai.ui.onboarding.GalleryOnboardingScreen
import cn.soul2.imageai.ui.onboarding.GalleryPartialAccessBanner
import cn.soul2.imageai.ui.screens.HomeScreen
import cn.soul2.imageai.ui.screens.LibraryScreen
import cn.soul2.imageai.ui.screens.SettingsScreen
import cn.soul2.imageai.ui.screens.TasksScreen
import kotlinx.coroutines.flow.Flow

@Composable
fun SoImageManagerApp(
    galleryRepository: GalleryRepository,
    syncRuns: Flow<MediaSyncRunEntity?>,
    navController: NavHostController = rememberNavController(),
    galleryAccessState: GalleryAccessState = GalleryAccessState.Full,
    showGalleryOnboarding: Boolean = false,
    isGalleryPermissionRecovery: Boolean = false,
    isGalleryPermissionRequestInFlight: Boolean = false,
    onRequestGalleryPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onDismissGalleryOnboarding: () -> Unit = {},
    onRequestGalleryReselection: () -> Unit = {},
) {
    val deniedState = galleryAccessState as? GalleryAccessState.Denied
    if (showGalleryOnboarding && deniedState != null) {
        GalleryOnboardingScreen(
            deniedState = deniedState,
            isPermissionRecovery = isGalleryPermissionRecovery,
            isPermissionRequestInFlight = isGalleryPermissionRequestInFlight,
            onRequestPermission = onRequestGalleryPermission,
            onOpenAppSettings = onOpenAppSettings,
            onDismiss = onDismissGalleryOnboarding,
        )
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.start.route
    val isImageDetail = currentRoute == ImageDetailDestination.route

    Scaffold(
        contentWindowInsets = if (isImageDetail) {
            WindowInsets(0, 0, 0, 0)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        bottomBar = {
            if (!isImageDetail) {
                NavigationBar(Modifier.testTag("bottom_navigation")) {
                    AppDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(AppDestination.start.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                            modifier = Modifier.testTag("destination_${destination.route}"),
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        val contentModifier = if (isImageDetail) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().padding(contentPadding)
        }
        Column(contentModifier) {
            if (!isImageDetail && galleryAccessState is GalleryAccessState.Partial) {
                GalleryPartialAccessBanner(
                    isPermissionRequestInFlight = isGalleryPermissionRequestInFlight,
                    onRequestReselection = onRequestGalleryReselection,
                )
            }
            NavHost(
                navController = navController,
                startDestination = AppDestination.start.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(AppDestination.HOME.route) {
                    HomeScreen(
                        repository = galleryRepository,
                        syncRuns = syncRuns,
                        galleryAccessState = galleryAccessState,
                        onImageClick = { localId ->
                            navController.navigate(ImageDetailDestination.createRoute(localId))
                        },
                        isPermissionRequestInFlight = isGalleryPermissionRequestInFlight,
                        onRequestGalleryPermission = onRequestGalleryPermission,
                        onOpenAppSettings = onOpenAppSettings,
                    )
                }
                composable(AppDestination.LIBRARY.route) {
                    LibraryScreen(
                        repository = galleryRepository,
                        syncRuns = syncRuns,
                        galleryAccessState = galleryAccessState,
                        onImageClick = { localId ->
                            navController.navigate(ImageDetailDestination.createRoute(localId))
                        },
                        isPermissionRequestInFlight = isGalleryPermissionRequestInFlight,
                        onRequestGalleryPermission = onRequestGalleryPermission,
                        onOpenAppSettings = onOpenAppSettings,
                    )
                }
                composable(AppDestination.TASKS.route) { TasksScreen() }
                composable(AppDestination.SETTINGS.route) { SettingsScreen() }
                composable(
                    route = ImageDetailDestination.route,
                    arguments = listOf(
                        navArgument(ImageDetailDestination.localIdArgument) {
                            type = NavType.LongType
                        },
                    ),
                ) { entry ->
                    val localId = requireNotNull(
                        entry.arguments?.getLong(ImageDetailDestination.localIdArgument),
                    )
                    ImageDetailScreen(
                        repository = galleryRepository,
                        localId = localId,
                        onBack = navController::navigateUp,
                    )
                }
            }
        }
    }
}

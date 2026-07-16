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
import cn.soul2.imageai.search.ImageSearchRepository
import cn.soul2.imageai.ui.gallery.ImageDetailDestination
import cn.soul2.imageai.ui.gallery.ImageDetailScreen
import cn.soul2.imageai.ui.onboarding.GalleryOnboardingScreen
import cn.soul2.imageai.ui.onboarding.GalleryPartialAccessBanner
import cn.soul2.imageai.ui.screens.HomeScreen
import cn.soul2.imageai.ui.screens.LibraryScreen
import cn.soul2.imageai.ui.screens.SettingsScreen
import cn.soul2.imageai.ui.screens.TasksScreen
import cn.soul2.imageai.ui.search.SearchDestination
import cn.soul2.imageai.ui.search.SearchScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun SoImageManagerApp(
    galleryRepository: GalleryRepository,
    syncRuns: Flow<MediaSyncRunEntity?>,
    imageSearchRepository: ImageSearchRepository = ImageSearchRepository.Empty,
    lastSyncCompletedAt: Flow<Long?> = flowOf(null),
    galleryUnavailableCounts: Flow<Int> = flowOf(0),
    navController: NavHostController = rememberNavController(),
    galleryAccessState: GalleryAccessState = GalleryAccessState.Full,
    galleryAccessStates: Flow<GalleryAccessState> = flowOf(galleryAccessState),
    showGalleryOnboarding: Boolean = false,
    isGalleryPermissionRecovery: Boolean = false,
    isGalleryPermissionRequestInFlight: Boolean = false,
    onRequestGalleryPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onDismissGalleryOnboarding: () -> Unit = {},
    onRequestGalleryReselection: () -> Unit = {},
    onRetryGallerySync: () -> Unit = {},
    onRequestGalleryReconciliation: () -> Unit = {},
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
    val isSearch = currentRoute == SearchDestination.route
    val showBottomNavigation = !isImageDetail && !isSearch

    Scaffold(
        contentWindowInsets = if (isImageDetail) {
            WindowInsets(0, 0, 0, 0)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        bottomBar = {
            if (showBottomNavigation) {
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
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = null,
                                )
                            },
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
                        onSearchClick = { navController.navigate(SearchDestination.route) },
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
                composable(AppDestination.TASKS.route) {
                    TasksScreen(
                        syncRuns = syncRuns,
                        lastCompletedAt = lastSyncCompletedAt,
                        onRetry = onRetryGallerySync,
                    )
                }
                composable(AppDestination.SETTINGS.route) {
                    SettingsScreen(
                        galleryAccessStates = galleryAccessStates,
                        repository = galleryRepository,
                        unavailableCounts = galleryUnavailableCounts,
                        onReselectPhotos = onRequestGalleryReselection,
                        onRescan = onRequestGalleryReconciliation,
                        onOpenSystemSettings = onOpenAppSettings,
                    )
                }
                composable(SearchDestination.route) {
                    SearchScreen(
                        searchRepository = imageSearchRepository,
                        galleryRepository = galleryRepository,
                        onBack = navController::navigateUp,
                        onImageClick = { localId ->
                            navController.navigate(ImageDetailDestination.createRoute(localId))
                        },
                        onRebuildIndex = onRequestGalleryReconciliation,
                    )
                }
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

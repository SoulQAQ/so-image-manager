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
import androidx.compose.runtime.rememberCoroutineScope
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
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.ai.analysis.SingleImageAnalyzer
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GallerySelectionActions
import cn.soul2.imageai.ai.batch.BatchAnalysisRepository
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.search.ImageSearchRepository
import cn.soul2.imageai.ui.gallery.ImageDetailDestination
import cn.soul2.imageai.ui.gallery.ImageDetailScreen
import cn.soul2.imageai.ui.ai.AiSettingsDestination
import cn.soul2.imageai.ui.ai.AiSettingsScreen
import cn.soul2.imageai.ui.onboarding.GalleryOnboardingScreen
import cn.soul2.imageai.ui.screens.HomeScreen
import cn.soul2.imageai.ui.screens.LibraryScreen
import cn.soul2.imageai.ui.screens.SettingsScreen
import cn.soul2.imageai.ui.screens.TasksScreen
import cn.soul2.imageai.ui.search.SearchDestination
import cn.soul2.imageai.ui.search.SearchScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SoImageManagerApp(
    galleryRepository: GalleryRepository,
    syncRuns: Flow<MediaSyncRunEntity?>,
    imageSearchRepository: ImageSearchRepository = ImageSearchRepository.Empty,
    aiConfigurationRepository: AiConfigurationRepository? = null,
    aiCredentialStore: AiCredentialStore? = null,
    singleImageAnalyzer: SingleImageAnalyzer? = null,
    canonicalMetadataRepository: CanonicalMetadataRepository? = null,
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
    onSelectDocumentImages: () -> Unit = {},
    documentImportNotice: String? = null,
    onDocumentImportNoticeConsumed: () -> Unit = {},
    onRetryGallerySync: () -> Unit = {},
    onRequestGalleryReconciliation: () -> Unit = {},
    gallerySelectionActions: GallerySelectionActions? = null,
    batchAnalysisRepository: BatchAnalysisRepository? = null,
    onShareImages: (List<GalleryImage>) -> Unit = {},
    onDeleteImages: (List<GalleryImage>) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
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
    val isAiSettings = currentRoute == AiSettingsDestination.route
    val showBottomNavigation = !isImageDetail && !isSearch && !isAiSettings

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
                        onShareImages = onShareImages,
                        onDeleteImages = onDeleteImages,
                        onRemoveImages = { images ->
                            coroutineScope.launch(Dispatchers.IO) {
                                gallerySelectionActions?.removeFromSoim(images.map(GalleryImage::localId))
                            }
                        },
                        onAnalyzeImages = { images ->
                            coroutineScope.launch(Dispatchers.IO) {
                                gallerySelectionActions?.analyze(images.map(GalleryImage::localId))
                            }
                        },
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
                        onShareImages = onShareImages,
                        onDeleteImages = onDeleteImages,
                        onRemoveImages = { images ->
                            coroutineScope.launch(Dispatchers.IO) {
                                gallerySelectionActions?.removeFromSoim(images.map(GalleryImage::localId))
                            }
                        },
                        onAnalyzeImages = { images ->
                            coroutineScope.launch(Dispatchers.IO) {
                                gallerySelectionActions?.analyze(images.map(GalleryImage::localId))
                            }
                        },
                    )
                }
                composable(AppDestination.TASKS.route) {
                    TasksScreen(
                        syncRuns = syncRuns,
                        lastCompletedAt = lastSyncCompletedAt,
                        onRetry = onRetryGallerySync,
                        batchAnalysisRuns = batchAnalysisRepository?.observeLatest() ?: flowOf(null),
                        onAnalyzeAll = {
                            coroutineScope.launch(Dispatchers.IO) {
                                gallerySelectionActions?.analyzeAll()
                            }
                        },
                    )
                }
                composable(AppDestination.SETTINGS.route) {
                    SettingsScreen(
                        galleryAccessStates = galleryAccessStates,
                        repository = galleryRepository,
                        unavailableCounts = galleryUnavailableCounts,
                        onReselectPhotos = onRequestGalleryReselection,
                        onSelectDocumentImages = onSelectDocumentImages,
                        documentImportNotice = documentImportNotice,
                        onDocumentImportNoticeConsumed = onDocumentImportNoticeConsumed,
                        onRescan = onRequestGalleryReconciliation,
                        onOpenSystemSettings = onOpenAppSettings,
                        onOpenAiSettings = { navController.navigate(AiSettingsDestination.route) },
                    )
                }
                composable(AiSettingsDestination.route) {
                    val repository = aiConfigurationRepository
                    val credentials = aiCredentialStore
                    if (repository != null && credentials != null) {
                        AiSettingsScreen(
                            repository = repository,
                            credentialStore = credentials,
                            onBack = navController::navigateUp,
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(stringResource(cn.soul2.imageai.R.string.ai_settings_error_save))
                        }
                    }
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
                        singleImageAnalyzer = singleImageAnalyzer,
                        canonicalMetadataRepository = canonicalMetadataRepository,
                        localId = localId,
                        onBack = navController::navigateUp,
                    )
                }
            }
        }
    }
}

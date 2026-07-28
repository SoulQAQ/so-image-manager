package cn.soul2.imageai.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.data.db.entity.ImagePartition
import cn.soul2.imageai.data.db.entity.BatchAnalysisEnqueueResult
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.ai.analysis.SingleImageAnalyzer
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryCollectionSummary
import cn.soul2.imageai.gallery.GalleryCollectionType
import cn.soul2.imageai.gallery.GallerySelectionActions
import cn.soul2.imageai.ai.batch.BatchAnalysisRepository
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GallerySource
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.search.ImageSearchRepository
import cn.soul2.imageai.ui.gallery.ImageDetailDestination
import cn.soul2.imageai.ui.gallery.ImageDetailScreen
import cn.soul2.imageai.ui.ai.AiSettingsDestination
import cn.soul2.imageai.ui.ai.AiSettingsScreen
import cn.soul2.imageai.ui.ai.ModelProvidersDestination
import cn.soul2.imageai.ui.ai.ModelProvidersScreen
import cn.soul2.imageai.ui.ai.AnalysisSettingsDestination
import cn.soul2.imageai.ui.ai.AnalysisSettingsScreen
import cn.soul2.imageai.ui.onboarding.GalleryOnboardingScreen
import cn.soul2.imageai.ui.screens.HomeScreen
import cn.soul2.imageai.ui.screens.LibraryScreen
import cn.soul2.imageai.ui.screens.LibraryBrowserScreen
import cn.soul2.imageai.ui.screens.SettingsScreen
import cn.soul2.imageai.ui.screens.TasksScreen
import cn.soul2.imageai.ui.screens.UpdateDialog
import cn.soul2.imageai.ui.search.SearchDestination
import cn.soul2.imageai.ui.search.SearchScreen
import cn.soul2.imageai.update.AppUpdateState
import cn.soul2.imageai.update.InstalledUpdateNotice
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object PrivateGalleryDestination {
    const val route = "private_gallery"
}

private object UnprocessedGalleryDestination {
    const val route = "unprocessed_gallery"
}

private object LibraryCollectionDestination {
    const val typeArgument = "type"
    const val keyArgument = "key"
    const val titleArgument = "title"
    const val route = "library_collection/{$typeArgument}/{$keyArgument}/{$titleArgument}"

    fun createRoute(type: GalleryCollectionType, collection: GalleryCollectionSummary): String =
        "library_collection/${type.name}/${android.net.Uri.encode(collection.key)}/" +
            android.net.Uri.encode(collection.displayName)
}

private object LibraryCollectionImageDestination {
    const val localIdArgument = "localId"
    const val typeArgument = "type"
    const val keyArgument = "key"
    const val titleArgument = "title"
    const val route =
        "library_collection_image/{$localIdArgument}/{$typeArgument}/{$keyArgument}/{$titleArgument}"

    fun createRoute(
        localId: Long,
        type: GalleryCollectionType,
        collectionKey: String,
        title: String,
    ): String = "library_collection_image/$localId/${type.name}/" +
        "${android.net.Uri.encode(collectionKey)}/${android.net.Uri.encode(title)}"
}

private object PrivateImageDetailDestination {
    const val localIdArgument = "localId"
    const val sourceArgument = "source"
    const val route = "private_image/{$localIdArgument}/{$sourceArgument}"

    fun createRoute(localId: Long, source: GallerySource): String =
        "private_image/$localId/${source.routeName()}"
}

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
    appUpdateState: Flow<AppUpdateState> = flowOf(AppUpdateState.Idle),
    installedUpdateNotice: Flow<InstalledUpdateNotice?> = flowOf(null),
    onCheckForUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onCancelUpdateDownload: () -> Unit = {},
    onDismissUpdateFailure: () -> Unit = {},
    onDismissInstalledUpdateNotice: () -> Unit = {},
    onInstallUpdate: (File) -> Unit = {},
    onShareImages: (List<GalleryImage>) -> Unit = {},
    onDeleteImages: (List<GalleryImage>) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val updateState by appUpdateState.collectAsStateWithLifecycle(initialValue = AppUpdateState.Idle)
    val updateNotice by installedUpdateNotice.collectAsStateWithLifecycle(initialValue = null)
    var showUpdateDialog by remember { mutableStateOf(false) }
    val updatePromptKey = when (val current = updateState) {
        is AppUpdateState.Available -> "available:${current.release.tagName}"
        is AppUpdateState.Ready -> "ready:${current.release.tagName}"
        else -> null
    }
    LaunchedEffect(updatePromptKey) {
        if (updatePromptKey != null) showUpdateDialog = true
    }
    val analysisTaskSnackbar = remember { SnackbarHostState() }
    val analyzeImages: (List<GalleryImage>) -> Unit = { images ->
        coroutineScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    gallerySelectionActions?.analyze(images.map(GalleryImage::localId))
                }
            }
            analysisTaskSnackbar.showSnackbar(
                analysisEnqueueMessage(context, result.getOrNull(), result.isFailure),
            )
        }
    }
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
    val isImageDetail = currentRoute == ImageDetailDestination.route ||
        currentRoute == PrivateImageDetailDestination.route ||
        currentRoute == LibraryCollectionImageDestination.route
    val isSecondaryGallery = currentRoute == PrivateGalleryDestination.route ||
        currentRoute == UnprocessedGalleryDestination.route ||
        currentRoute == LibraryCollectionDestination.route
    val isSearch = currentRoute == SearchDestination.route
    val isAiSettings = currentRoute?.startsWith(AiSettingsDestination.baseRoute) == true
    val isModelProviders = currentRoute == ModelProvidersDestination.route
    val isAnalysisSettings = currentRoute == AnalysisSettingsDestination.route
    val showBottomNavigation = !isImageDetail && !isSecondaryGallery && !isSearch && !isAiSettings && !isModelProviders && !isAnalysisSettings

    Scaffold(
        contentWindowInsets = if (isImageDetail) {
            WindowInsets(0, 0, 0, 0)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        snackbarHost = {
            SnackbarHost(
                hostState = analysisTaskSnackbar,
                modifier = Modifier.testTag("analysis_task_snackbar"),
            )
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
                        runtimeSettings = aiConfigurationRepository?.runtimeSetting ?: flowOf(null),
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
                        onAnalyzeImages = analyzeImages,
                        onMoveToPrivateImages = { images ->
                            coroutineScope.launch(Dispatchers.IO) {
                                gallerySelectionActions?.moveToPrivate(images.map(GalleryImage::localId))
                            }
                        },
                    )
                }
                composable(AppDestination.LIBRARY.route) {
                    LibraryBrowserScreen(
                        repository = galleryRepository,
                        syncRuns = syncRuns,
                        runtimeSettings = aiConfigurationRepository?.runtimeSetting ?: flowOf(null),
                        galleryAccessState = galleryAccessState,
                        onImageClick = { localId ->
                            navController.navigate(ImageDetailDestination.createRoute(localId))
                        },
                        onOpenCollection = { type, collection ->
                            navController.navigate(
                                LibraryCollectionDestination.createRoute(type, collection),
                            )
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
                        onAnalyzeImages = analyzeImages,
                        onMoveToPrivateImages = { images ->
                            coroutineScope.launch(Dispatchers.IO) {
                                gallerySelectionActions?.moveToPrivate(images.map(GalleryImage::localId))
                            }
                        },
                    )
                }
                composable(
                    route = LibraryCollectionDestination.route,
                    arguments = listOf(
                        navArgument(LibraryCollectionDestination.typeArgument) {
                            type = NavType.StringType
                        },
                        navArgument(LibraryCollectionDestination.keyArgument) {
                            type = NavType.StringType
                        },
                        navArgument(LibraryCollectionDestination.titleArgument) {
                            type = NavType.StringType
                        },
                    ),
                ) { entry ->
                    val type = GalleryCollectionType.valueOf(
                        requireNotNull(
                            entry.arguments?.getString(LibraryCollectionDestination.typeArgument),
                        ),
                    )
                    val key = requireNotNull(
                        entry.arguments?.getString(LibraryCollectionDestination.keyArgument),
                    )
                    val title = requireNotNull(
                        entry.arguments?.getString(LibraryCollectionDestination.titleArgument),
                    )
                    val source = galleryCollectionSource(type, key, title)
                    LibraryScreen(
                        repository = galleryRepository,
                        syncRuns = syncRuns,
                        runtimeSettings = aiConfigurationRepository?.runtimeSetting ?: flowOf(null),
                        galleryAccessState = galleryAccessState,
                        initialSource = source,
                        onBack = navController::navigateUp,
                        viewModelKey = "library_collection_${type.name}_$key",
                        selectionKey = "library_collection_selection_${type.name}_$key",
                        titleText = title,
                        onImageClick = { localId ->
                            navController.navigate(
                                LibraryCollectionImageDestination.createRoute(
                                    localId,
                                    type,
                                    key,
                                    title,
                                ),
                            )
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
                        onAnalyzeImages = analyzeImages,
                        onMoveToPrivateImages = { images ->
                            coroutineScope.launch(Dispatchers.IO) {
                                gallerySelectionActions?.moveToPrivate(images.map(GalleryImage::localId))
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
                        estimatedAnalysisCounts = galleryRepository.observeUnprocessedCount(),
                        onAnalyzeAll = {
                            coroutineScope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        gallerySelectionActions?.analyzeAll()
                                    }
                                }
                                analysisTaskSnackbar.showSnackbar(
                                    analysisEnqueueMessage(
                                        context,
                                        result.getOrNull(),
                                        result.isFailure,
                                    ),
                                )
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
                        onOpenGeneralSettings = { navController.navigate(AnalysisSettingsDestination.route) },
                        onOpenAiSettings = { navController.navigate(ModelProvidersDestination.route) },
                        onOpenPrivateGallery = { navController.navigate(PrivateGalleryDestination.route) },
                        onOpenUnprocessedGallery = { navController.navigate(UnprocessedGalleryDestination.route) },
                        appUpdateState = appUpdateState,
                        onCheckForUpdate = onCheckForUpdate,
                        onOpenUpdateDetails = { showUpdateDialog = true },
                    )
                }
                composable(UnprocessedGalleryDestination.route) {
                    LibraryScreen(
                        repository = galleryRepository,
                        syncRuns = syncRuns,
                        runtimeSettings = aiConfigurationRepository?.runtimeSetting ?: flowOf(null),
                        galleryAccessState = galleryAccessState,
                        initialSource = GallerySource.Unanalyzed,
                        allowMoveToPrivate = false,
                        onBack = navController::navigateUp,
                        viewModelKey = "unprocessed_gallery",
                        selectionKey = "unprocessed_gallery_selection",
                        titleRes = cn.soul2.imageai.R.string.unprocessed_gallery_title,
                        onImageClick = {},
                        onImageClickWithSource = { localId, source ->
                            navController.navigate(PrivateImageDetailDestination.createRoute(localId, source))
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
                        onAnalyzeImages = analyzeImages,
                    )
                }
                composable(PrivateGalleryDestination.route) {
                    LibraryScreen(
                        repository = galleryRepository,
                        syncRuns = syncRuns,
                        runtimeSettings = aiConfigurationRepository?.runtimeSetting ?: flowOf(null),
                        galleryAccessState = galleryAccessState,
                        initialSource = GallerySource.Private,
                        privateMode = true,
                        onBack = navController::navigateUp,
                        viewModelKey = "private_gallery",
                        selectionKey = "private_gallery_selection",
                        titleRes = cn.soul2.imageai.R.string.private_gallery_title,
                        onImageClick = {},
                        onImageClickWithSource = { localId, source ->
                            navController.navigate(PrivateImageDetailDestination.createRoute(localId, source))
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
                        onAnalyzeImages = analyzeImages,
                    )
                }
                composable(AnalysisSettingsDestination.route) {
                    val repository = aiConfigurationRepository
                    if (repository != null) {
                        AnalysisSettingsScreen(repository, navController::navigateUp)
                    }
                }
                composable(ModelProvidersDestination.route) {
                    val repository = aiConfigurationRepository
                    if (repository != null) {
                        ModelProvidersScreen(
                            repository = repository,
                            onBack = navController::navigateUp,
                            onAdd = { partition ->
                                navController.navigate(AiSettingsDestination.createNewRoute(partition))
                            },
                            onEdit = { partition, id ->
                                navController.navigate(AiSettingsDestination.createRoute(id, partition))
                            },
                        )
                    }
                }
                composable(
                    route = AiSettingsDestination.route,
                    arguments = listOf(navArgument(AiSettingsDestination.providerIdArgument) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }, navArgument(AiSettingsDestination.partitionArgument) {
                        type = NavType.StringType
                        defaultValue = ImagePartition.MAIN.name
                    }),
                ) { entry ->
                    val repository = aiConfigurationRepository
                    val credentials = aiCredentialStore
                    if (repository != null && credentials != null) {
                        AiSettingsScreen(
                            repository = repository,
                            credentialStore = credentials,
                            providerId = entry.arguments?.getString(AiSettingsDestination.providerIdArgument),
                            partition = runCatching {
                                ImagePartition.valueOf(
                                    entry.arguments?.getString(AiSettingsDestination.partitionArgument)
                                        ?: ImagePartition.MAIN.name,
                                )
                            }.getOrDefault(ImagePartition.MAIN),
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
                composable(
                    route = PrivateImageDetailDestination.route,
                    arguments = listOf(navArgument(PrivateImageDetailDestination.localIdArgument) {
                        type = NavType.LongType
                    }, navArgument(PrivateImageDetailDestination.sourceArgument) {
                        type = NavType.StringType
                    }),
                ) { entry ->
                    val localId = requireNotNull(entry.arguments?.getLong(PrivateImageDetailDestination.localIdArgument))
                    val source = entry.arguments?.getString(PrivateImageDetailDestination.sourceArgument)
                        ?.let(::privateSourceFromRoute)
                        ?: GallerySource.Private
                    ImageDetailScreen(
                        repository = galleryRepository,
                        singleImageAnalyzer = singleImageAnalyzer,
                        canonicalMetadataRepository = canonicalMetadataRepository,
                        localId = localId,
                        source = source,
                        onBack = navController::navigateUp,
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
                    route = LibraryCollectionImageDestination.route,
                    arguments = listOf(
                        navArgument(LibraryCollectionImageDestination.localIdArgument) {
                            type = NavType.LongType
                        },
                        navArgument(LibraryCollectionImageDestination.typeArgument) {
                            type = NavType.StringType
                        },
                        navArgument(LibraryCollectionImageDestination.keyArgument) {
                            type = NavType.StringType
                        },
                        navArgument(LibraryCollectionImageDestination.titleArgument) {
                            type = NavType.StringType
                        },
                    ),
                ) { entry ->
                    val localId = requireNotNull(
                        entry.arguments?.getLong(LibraryCollectionImageDestination.localIdArgument),
                    )
                    val type = GalleryCollectionType.valueOf(
                        requireNotNull(
                            entry.arguments?.getString(LibraryCollectionImageDestination.typeArgument),
                        ),
                    )
                    val key = requireNotNull(
                        entry.arguments?.getString(LibraryCollectionImageDestination.keyArgument),
                    )
                    val title = requireNotNull(
                        entry.arguments?.getString(LibraryCollectionImageDestination.titleArgument),
                    )
                    ImageDetailScreen(
                        repository = galleryRepository,
                        singleImageAnalyzer = singleImageAnalyzer,
                        canonicalMetadataRepository = canonicalMetadataRepository,
                        localId = localId,
                        source = galleryCollectionSource(type, key, title),
                        onBack = navController::navigateUp,
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
    if (updateNotice != null) {
        InstalledUpdateNoticeDialog(
            notice = checkNotNull(updateNotice),
            onDismiss = onDismissInstalledUpdateNotice,
        )
    } else if (showUpdateDialog) {
        UpdateDialog(
            state = updateState,
            onDismiss = {
                showUpdateDialog = false
                if (updateState is AppUpdateState.Failed) onDismissUpdateFailure()
            },
            onCheck = onCheckForUpdate,
            onDownload = onDownloadUpdate,
            onCancelDownload = onCancelUpdateDownload,
            onInstall = onInstallUpdate,
        )
    }
}

@Composable
private fun InstalledUpdateNoticeDialog(
    notice: InstalledUpdateNotice,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已更新到 ${notice.tagName}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (notice.releaseName.isNotBlank() && notice.releaseName != notice.tagName) {
                    Text(notice.releaseName, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    notice.notes.ifBlank { "此版本没有附加更新说明。" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } },
    )
}

internal fun analysisEnqueueMessage(
    context: android.content.Context,
    result: BatchAnalysisEnqueueResult?,
    failed: Boolean,
): String = when {
    failed || result == null -> context.getString(cn.soul2.imageai.R.string.analysis_task_start_failed)
    result.addedCount > 0 -> context.getString(
        cn.soul2.imageai.R.string.analysis_task_started,
        result.addedCount,
    )
    result.run != null -> context.getString(cn.soul2.imageai.R.string.analysis_task_already_queued)
    else -> context.getString(cn.soul2.imageai.R.string.analysis_task_nothing_to_add)
}

private fun GallerySource.routeName(): String = when (this) {
    GallerySource.Unanalyzed -> "unprocessed"
    GallerySource.PrivateUnanalyzable,
    GallerySource.Rejected,
    -> "unanalyzable"
    else -> "private"
}

private fun privateSourceFromRoute(value: String): GallerySource = when (value) {
    "unprocessed" -> GallerySource.Unanalyzed
    "unanalyzable" -> GallerySource.PrivateUnanalyzable
    else -> GallerySource.Private
}

private fun galleryCollectionSource(
    type: GalleryCollectionType,
    key: String,
    title: String,
): GallerySource = when (type) {
    GalleryCollectionType.ALBUM -> if (key.startsWith("name:")) {
        GallerySource.Album(bucketId = null, bucketName = key.removePrefix("name:"))
    } else {
        GallerySource.Album(bucketId = key.toLong(), bucketName = title)
    }
    GalleryCollectionType.TAG -> GallerySource.Tag(key)
    GalleryCollectionType.CATEGORY -> GallerySource.Category(key)
}

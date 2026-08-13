package cn.soul2.imageai.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import cn.soul2.imageai.R
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.gallery.GalleryLayout
import cn.soul2.imageai.ui.gallery.GalleryScreen
import cn.soul2.imageai.ui.gallery.HomeViewModel
import cn.soul2.imageai.ui.gallery.GallerySelectionViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import cn.soul2.imageai.home.HomeConfigurationRepository
import cn.soul2.imageai.home.HomeModule
import cn.soul2.imageai.home.HomeModuleType
import cn.soul2.imageai.home.HomeModuleSort
import cn.soul2.imageai.home.HomeModuleLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import cn.soul2.imageai.gallery.GalleryQuery
import cn.soul2.imageai.gallery.GallerySource
import cn.soul2.imageai.gallery.GallerySort
import androidx.paging.map
import cn.soul2.imageai.ui.gallery.GalleryImageTile
import cn.soul2.imageai.ui.gallery.GalleryTileLayout

@Composable
fun HomeScreen(
    repository: GalleryRepository,
    syncRuns: Flow<MediaSyncRunEntity?>,
    galleryAccessState: GalleryAccessState,
    onImageClick: (Long) -> Unit,
    isPermissionRequestInFlight: Boolean = false,
    onRequestGalleryPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onShareImages: (List<GalleryImage>) -> Unit = {},
    onDeleteImages: (List<GalleryImage>) -> Unit = {},
    onRemoveImages: (List<GalleryImage>) -> Unit = {},
    onAnalyzeImages: (List<GalleryImage>) -> Unit = {},
    onMoveToPrivateImages: (List<GalleryImage>) -> Unit = {},
    runtimeSettings: Flow<AiRuntimeSettingEntity?> = flowOf(null),
    homeConfigurationRepository: HomeConfigurationRepository? = null,
    onOpenModule: (HomeModule) -> Unit = {},
) {
    val modules by (homeConfigurationRepository?.modules ?: flowOf(listOf(HomeConfigurationRepository.defaultModule())))
        .collectAsStateWithLifecycle(initialValue = listOf(HomeConfigurationRepository.defaultModule()))
    val enabledModules = modules.filter(HomeModule::enabled)
    if (enabledModules.size > 1) {
        MultiModuleHome(repository, enabledModules, onImageClick, onSearchClick, onOpenModule)
        return
    }
    val singleModule = enabledModules.singleOrNull()
    val singleSource = singleModule?.toGallerySource() ?: GallerySource.Recent
    val viewModel: HomeViewModel = viewModel(
        key = "home_gallery_${singleModule?.id ?: "recent"}",
        factory = HomeViewModel.factory(
            repository,
            syncRuns,
            runtimeSettings,
            singleSource,
            singleModule?.sort?.toGallerySort() ?: GallerySort.NEWEST,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()
    val images = viewModel.images.collectAsLazyPagingItems()
    val selectionViewModel: GallerySelectionViewModel = viewModel(key = "home_selection")
    val selected by selectionViewModel.selected.collectAsState()

    GalleryScreen(
        titleRes = R.string.nav_home,
        screenTag = "screen_home",
        collectionTag = "home_waterfall",
        layout = GalleryLayout.Waterfall,
        galleryAccessState = galleryAccessState,
        uiState = uiState,
        images = images,
        isPermissionRequestInFlight = isPermissionRequestInFlight,
        onRequestGalleryPermission = onRequestGalleryPermission,
        onOpenAppSettings = onOpenAppSettings,
        onImageClick = onImageClick,
        selectedImages = selected,
        onToggleSelection = selectionViewModel::toggle,
        onClearSelection = selectionViewModel::clear,
        onShareSelection = onShareImages,
        onDeleteSelection = { selectedImages ->
            onDeleteImages(selectedImages)
            selectionViewModel.clear()
        },
        onRemoveSelection = { selectedImages ->
            onRemoveImages(selectedImages)
            selectionViewModel.clear()
        },
        onAnalyzeSelection = { selectedImages ->
            onAnalyzeImages(selectedImages)
            selectionViewModel.clear()
        },
        onMoveToPrivateSelection = { selectedImages ->
            onMoveToPrivateImages(selectedImages)
            selectionViewModel.clear()
        },
        topBarAction = {
            IconButton(onClick = onSearchClick) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.search_open),
                )
            }
        },
    )
}

@Composable
private fun MultiModuleHome(
    repository: GalleryRepository,
    modules: List<HomeModule>,
    onImageClick: (Long) -> Unit,
    onSearchClick: () -> Unit,
    onOpenModule: (HomeModule) -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("screen_home")) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("首页", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearchClick) { Icon(Icons.Outlined.Search, "搜索") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(modules, key = HomeModule::id) { module ->
                HomeModuleSection(repository, module, onImageClick, onOpenModule)
            }
        }
    }
}

@Composable
private fun HomeModuleSection(
    repository: GalleryRepository,
    module: HomeModule,
    onImageClick: (Long) -> Unit,
    onOpenModule: (HomeModule) -> Unit,
) {
    val source = module.toGallerySource()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(module.title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(module.type.name.lowercase()) },
            modifier = Modifier.testTag("home_module_${module.id}"),
            trailingContent = { androidx.compose.material3.TextButton(onClick = { onOpenModule(module) }) { Text("查看") } },
        )
        if (source != null) {
            val paging = remember(module.id, module.sort) {
                repository.observe(GalleryQuery(source, module.sort.toGallerySort()))
            }
            val images = paging.collectAsLazyPagingItems()
            if (module.layout == HomeModuleLayout.STRIP) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(count = minOf(images.itemCount, module.previewCount), key = { index -> images.peek(index)?.localId ?: index }) { index ->
                        images[index]?.let { image ->
                            GalleryImageTile(
                                image = image, layout = GalleryTileLayout.Square,
                                onClick = onImageClick, modifier = Modifier.size(116.dp),
                            )
                        }
                    }
                }
            } else {
                val visible = minOf(images.itemCount, module.previewCount.coerceAtMost(12))
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat((visible + 3) / 4) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(4) { column ->
                                val index = row * 4 + column
                                Box(Modifier.weight(1f).aspectRatio(1f)) {
                                    if (index < visible) images[index]?.let { image ->
                                        GalleryImageTile(
                                            image = image, layout = GalleryTileLayout.Square,
                                            onClick = onImageClick, modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Text("动态查询将在搜索页打开", Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun HomeModuleSort.toGallerySort() = when (this) {
    HomeModuleSort.NEWEST -> GallerySort.NEWEST
    HomeModuleSort.NAME -> GallerySort.NAME
    HomeModuleSort.SIZE -> GallerySort.SIZE
}

private fun HomeModule.toGallerySource(): GallerySource? = when (type) {
    HomeModuleType.RECENT -> GallerySource.Recent
    HomeModuleType.ALBUM -> if (sourceKey.startsWith("name:")) {
        GallerySource.Album(null, sourceKey.removePrefix("name:"))
    } else sourceKey.toLongOrNull()?.let { GallerySource.Album(it, title) }
    HomeModuleType.TAG -> GallerySource.Tag(sourceKey)
    HomeModuleType.CATEGORY -> GallerySource.Category(sourceKey)
    HomeModuleType.SAVED_SEARCH -> null
    HomeModuleType.THEME -> null
}

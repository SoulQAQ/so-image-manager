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
) {
    val viewModel: HomeViewModel = viewModel(
        key = "home_gallery",
        factory = HomeViewModel.factory(repository, syncRuns, runtimeSettings),
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

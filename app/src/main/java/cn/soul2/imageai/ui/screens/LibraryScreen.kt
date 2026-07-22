package cn.soul2.imageai.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import cn.soul2.imageai.R
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.gallery.GalleryLayout
import cn.soul2.imageai.ui.gallery.GalleryScreen
import cn.soul2.imageai.ui.gallery.LibraryViewModel
import cn.soul2.imageai.ui.gallery.GallerySelectionViewModel
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GallerySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun LibraryScreen(
    repository: GalleryRepository,
    syncRuns: Flow<MediaSyncRunEntity?>,
    galleryAccessState: GalleryAccessState,
    onImageClick: (Long) -> Unit,
    isPermissionRequestInFlight: Boolean = false,
    onRequestGalleryPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onShareImages: (List<GalleryImage>) -> Unit = {},
    onDeleteImages: (List<GalleryImage>) -> Unit = {},
    onRemoveImages: (List<GalleryImage>) -> Unit = {},
    onAnalyzeImages: (List<GalleryImage>) -> Unit = {},
    runtimeSettings: Flow<AiRuntimeSettingEntity?> = flowOf(null),
) {
    val viewModel: LibraryViewModel = viewModel(
        key = "library_gallery",
        factory = LibraryViewModel.factory(repository, syncRuns, runtimeSettings),
    )
    val uiState by viewModel.uiState.collectAsState()
    val images = viewModel.images.collectAsLazyPagingItems()
    val selectionViewModel: GallerySelectionViewModel = viewModel(key = "library_selection")
    val selected by selectionViewModel.selected.collectAsState()
    var sourceMenuExpanded by remember { mutableStateOf(false) }

    GalleryScreen(
        titleRes = R.string.nav_library,
        screenTag = "screen_library",
        collectionTag = "library_grid",
        layout = GalleryLayout.Grid,
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
        topBarAction = {
            IconButton(onClick = { sourceMenuExpanded = true }) {
                Icon(Icons.Outlined.FilterList, stringResource(R.string.library_filter_groups))
            }
            DropdownMenu(
                expanded = sourceMenuExpanded,
                onDismissRequest = { sourceMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_group_default)) },
                    onClick = { sourceMenuExpanded = false; viewModel.showSource(null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_group_unanalyzed)) },
                    onClick = { sourceMenuExpanded = false; viewModel.showSource(GallerySource.Unanalyzed) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_group_rejected)) },
                    onClick = { sourceMenuExpanded = false; viewModel.showSource(GallerySource.Rejected) },
                )
            }
        },
    )
}

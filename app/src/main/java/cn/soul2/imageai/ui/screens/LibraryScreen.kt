package cn.soul2.imageai.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.Flow

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
) {
    val viewModel: LibraryViewModel = viewModel(
        key = "library_gallery",
        factory = LibraryViewModel.factory(repository, syncRuns),
    )
    val uiState by viewModel.uiState.collectAsState()
    val images = viewModel.images.collectAsLazyPagingItems()
    val selectionViewModel: GallerySelectionViewModel = viewModel(key = "library_selection")
    val selected by selectionViewModel.selected.collectAsState()

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
    )
}

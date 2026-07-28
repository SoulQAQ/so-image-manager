package cn.soul2.imageai.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onImageClickWithSource: ((Long, GallerySource) -> Unit)? = null,
    isPermissionRequestInFlight: Boolean = false,
    onRequestGalleryPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onShareImages: (List<GalleryImage>) -> Unit = {},
    onDeleteImages: (List<GalleryImage>) -> Unit = {},
    onRemoveImages: (List<GalleryImage>) -> Unit = {},
    onAnalyzeImages: (List<GalleryImage>) -> Unit = {},
    onMoveToPrivateImages: (List<GalleryImage>) -> Unit = {},
    runtimeSettings: Flow<AiRuntimeSettingEntity?> = flowOf(null),
    initialSource: GallerySource? = null,
    viewModelKey: String = "library_gallery",
    selectionKey: String = "library_selection",
    titleRes: Int = R.string.nav_library,
    titleText: String? = null,
    privateMode: Boolean = false,
    allowMoveToPrivate: Boolean = !privateMode,
    contentHeader: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val viewModel: LibraryViewModel = viewModel(
        key = viewModelKey,
        factory = LibraryViewModel.factory(repository, syncRuns, runtimeSettings, initialSource),
    )
    val uiState by viewModel.uiState.collectAsState()
    val currentSource by viewModel.currentSource.collectAsState()
    val images = viewModel.images.collectAsLazyPagingItems()
    val selectionViewModel: GallerySelectionViewModel = viewModel(key = selectionKey)
    val selected by selectionViewModel.selected.collectAsState()

    GalleryScreen(
        titleRes = titleRes,
        titleText = titleText,
        screenTag = "screen_library",
        collectionTag = "library_grid",
        layout = GalleryLayout.Grid,
        galleryAccessState = galleryAccessState,
        uiState = uiState,
        images = images,
        isPermissionRequestInFlight = isPermissionRequestInFlight,
        onRequestGalleryPermission = onRequestGalleryPermission,
        onOpenAppSettings = onOpenAppSettings,
        onImageClick = { localId ->
            onImageClickWithSource?.invoke(localId, currentSource) ?: onImageClick(localId)
        },
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
        onMoveToPrivateSelection = if (!allowMoveToPrivate) null else { selectedImages ->
            onMoveToPrivateImages(selectedImages)
            selectionViewModel.clear()
        },
        contentHeader = if (privateMode) {
            { PrivateGallerySectionSelector(currentSource, viewModel::showSource) }
        } else contentHeader,
        onNavigateBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivateGallerySectionSelector(
    currentSource: GallerySource,
    onSourceSelected: (GallerySource) -> Unit,
) {
    val sections = listOf(
        GallerySource.Private to "隐私图片",
        GallerySource.PrivateUnanalyzable to "无法分析",
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        sections.forEachIndexed { index, (source, label) ->
            SegmentedButton(
                selected = currentSource == source,
                onClick = { onSourceSelected(source) },
                shape = SegmentedButtonDefaults.itemShape(index, sections.size),
            ) {
                Text(label)
            }
        }
    }
}

package cn.soul2.imageai.ui.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import cn.soul2.imageai.R
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.media.permission.GalleryAccessState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryScreen(
    @StringRes titleRes: Int,
    screenTag: String,
    collectionTag: String,
    layout: GalleryLayout,
    galleryAccessState: GalleryAccessState,
    uiState: GalleryUiState,
    images: LazyPagingItems<GalleryImage>,
    isPermissionRequestInFlight: Boolean,
    onRequestGalleryPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onImageClick: (Long) -> Unit,
    selectedImages: Map<Long, GalleryImage> = emptyMap(),
    onToggleSelection: ((GalleryImage) -> Unit)? = null,
    onClearSelection: () -> Unit = {},
    onShareSelection: (List<GalleryImage>) -> Unit = {},
    onDeleteSelection: (List<GalleryImage>) -> Unit = {},
    onRemoveSelection: (List<GalleryImage>) -> Unit = {},
    onAnalyzeSelection: (List<GalleryImage>) -> Unit = {},
    topBarAction: @Composable (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().testTag(screenTag)) {
        TopAppBar(
            title = {
                if (selectedImages.isEmpty()) Text(stringResource(titleRes))
                else Text(
                    text = stringResource(R.string.gallery_selection_count_compact, selectedImages.size),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                if (selectedImages.isNotEmpty()) {
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.gallery_selection_cancel))
                    }
                }
            },
            actions = { if (selectedImages.isEmpty()) topBarAction?.invoke() },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        when (val contentState = uiState.contentState(galleryAccessState)) {
            GalleryContentState.Loading -> CenteredContent {
                CircularProgressIndicator()
            }
            GalleryContentState.NoPermission -> PermissionEmptyState(
                deniedState = galleryAccessState as GalleryAccessState.Denied,
                requestInFlight = isPermissionRequestInFlight,
                onRequestPermission = onRequestGalleryPermission,
                onOpenSettings = onOpenAppSettings,
            )
            is GalleryContentState.Syncing -> SyncingEmptyState(contentState.indexedCount)
            GalleryContentState.Empty -> CenteredContent {
                Text(
                    text = stringResource(R.string.gallery_empty_index),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            GalleryContentState.Content -> {
                GalleryCollection(
                    images = images,
                    layout = layout,
                    collectionTag = collectionTag,
                    onImageClick = onImageClick,
                    selectedImages = selectedImages,
                    onToggleSelection = onToggleSelection,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (selectedImages.isNotEmpty()) {
            SelectionActionBar(
                selected = selectedImages.values.toList(),
                onAnalyze = onAnalyzeSelection,
                onShare = onShareSelection,
                onRemove = onRemoveSelection,
                onDelete = onDeleteSelection,
            )
        }
    }
}

@Composable
private fun SelectionActionBar(
    selected: List<GalleryImage>,
    onAnalyze: (List<GalleryImage>) -> Unit,
    onShare: (List<GalleryImage>) -> Unit,
    onRemove: (List<GalleryImage>) -> Unit,
    onDelete: (List<GalleryImage>) -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(70.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction(Icons.Outlined.AutoAwesome, R.string.gallery_selection_analyze) {
                onAnalyze(selected)
            }
            SelectionAction(Icons.Outlined.Share, R.string.gallery_selection_share) {
                onShare(selected)
            }
            SelectionAction(Icons.Outlined.VisibilityOff, R.string.gallery_selection_remove) {
                onRemove(selected)
            }
            SelectionAction(Icons.Outlined.Delete, R.string.gallery_selection_delete) {
                onDelete(selected)
            }
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
            Icon(icon, contentDescription = stringResource(label))
        }
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun PermissionEmptyState(
    deniedState: GalleryAccessState.Denied,
    requestInFlight: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    CenteredContent {
        Text(
            text = stringResource(R.string.gallery_empty_no_permission),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = if (deniedState.canRequestAgain) onRequestPermission else onOpenSettings,
            enabled = !requestInFlight,
        ) {
            Text(
                stringResource(
                    if (deniedState.canRequestAgain) {
                        R.string.gallery_permission_retry
                    } else {
                        R.string.gallery_permission_open_settings
                    },
                ),
            )
        }
    }
}

@Composable
private fun SyncingEmptyState(indexedCount: Int) {
    CenteredContent {
        Text(
            text = stringResource(R.string.gallery_syncing),
            style = MaterialTheme.typography.bodyLarge,
        )
        LinearProgressIndicator(
            modifier = Modifier.width(160.dp).testTag("gallery_sync_progress"),
        )
        Text(
            text = stringResource(R.string.gallery_sync_count, indexedCount),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CenteredContent(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun GalleryCollection(
    images: LazyPagingItems<GalleryImage>,
    layout: GalleryLayout,
    collectionTag: String,
    onImageClick: (Long) -> Unit,
    selectedImages: Map<Long, GalleryImage>,
    onToggleSelection: ((GalleryImage) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (images.itemCount == 0 && images.loadState.refresh is LoadState.Loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@BoxWithConstraints
        }
        if (images.itemCount == 0 && images.loadState.refresh is LoadState.Error) {
            CenteredContent {
                Text(stringResource(R.string.gallery_load_failed))
                TextButton(onClick = images::retry) {
                    Text(stringResource(R.string.gallery_retry))
                }
            }
            return@BoxWithConstraints
        }

        val columnCount = galleryColumnCount(layout, maxWidth.value.toInt())
        when (layout) {
            GalleryLayout.Waterfall -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columnCount),
                modifier = Modifier.fillMaxSize().testTag(collectionTag),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalItemSpacing = 2.dp,
            ) {
                items(
                    count = images.itemCount,
                    key = { index -> images.peek(index)?.localId ?: "waterfall-$index" },
                ) { index ->
                    images[index]?.let { image ->
                        GalleryImageTile(
                            image = image,
                            layout = GalleryTileLayout.OriginalAspect,
                            onClick = { id ->
                                if (selectedImages.isEmpty()) onImageClick(id)
                                else onToggleSelection?.invoke(image)
                            },
                            onLongClick = onToggleSelection,
                            selected = selectedImages.containsKey(image.localId),
                        )
                    }
                }
            }
            GalleryLayout.Grid -> LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                modifier = Modifier.fillMaxSize().testTag(collectionTag),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    count = images.itemCount,
                    key = { index -> images.peek(index)?.localId ?: "grid-$index" },
                ) { index ->
                    images[index]?.let { image ->
                        GalleryImageTile(
                            image = image,
                            layout = GalleryTileLayout.Square,
                            onClick = { id ->
                                if (selectedImages.isEmpty()) onImageClick(id)
                                else onToggleSelection?.invoke(image)
                            },
                            onLongClick = onToggleSelection,
                            selected = selectedImages.containsKey(image.localId),
                        )
                    }
                }
            }
        }
    }
}

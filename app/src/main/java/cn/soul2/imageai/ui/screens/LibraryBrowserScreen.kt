package cn.soul2.imageai.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.gallery.GalleryCollectionSummary
import cn.soul2.imageai.gallery.GalleryCollectionType
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.media.permission.GalleryAccessState
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class LibraryBrowseMode {
    IMAGES,
    ALBUMS,
    TAGS,
    CATEGORIES,
}

@Composable
fun LibraryBrowserScreen(
    repository: GalleryRepository,
    syncRuns: Flow<MediaSyncRunEntity?>,
    galleryAccessState: GalleryAccessState,
    onImageClick: (Long) -> Unit,
    onOpenCollection: (GalleryCollectionType, GalleryCollectionSummary) -> Unit,
    isPermissionRequestInFlight: Boolean = false,
    onRequestGalleryPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onShareImages: (List<GalleryImage>) -> Unit = {},
    onDeleteImages: (List<GalleryImage>) -> Unit = {},
    onRemoveImages: (List<GalleryImage>) -> Unit = {},
    onAnalyzeImages: (List<GalleryImage>) -> Unit = {},
    onMoveToPrivateImages: (List<GalleryImage>) -> Unit = {},
    runtimeSettings: Flow<AiRuntimeSettingEntity?> = flowOf(null),
) {
    var mode by rememberSaveable { mutableStateOf(LibraryBrowseMode.IMAGES) }
    if (mode == LibraryBrowseMode.IMAGES) {
        LibraryScreen(
            repository = repository,
            syncRuns = syncRuns,
            galleryAccessState = galleryAccessState,
            onImageClick = onImageClick,
            isPermissionRequestInFlight = isPermissionRequestInFlight,
            onRequestGalleryPermission = onRequestGalleryPermission,
            onOpenAppSettings = onOpenAppSettings,
            onShareImages = onShareImages,
            onDeleteImages = onDeleteImages,
            onRemoveImages = onRemoveImages,
            onAnalyzeImages = onAnalyzeImages,
            onMoveToPrivateImages = onMoveToPrivateImages,
            runtimeSettings = runtimeSettings,
            contentHeader = { LibraryBrowseTabs(mode, onModeSelected = { mode = it }) },
        )
    } else {
        val type = requireNotNull(mode.collectionType())
        val collectionsFlow = remember(repository, type) { repository.observeCollections(type) }
        val collections by collectionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        LibraryCollectionList(
            mode = mode,
            collections = collections,
            onModeSelected = { mode = it },
            onOpenCollection = { collection -> onOpenCollection(type, collection) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryCollectionList(
    mode: LibraryBrowseMode,
    collections: List<GalleryCollectionSummary>,
    onModeSelected: (LibraryBrowseMode) -> Unit,
    onOpenCollection: (GalleryCollectionSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("library_collection_list")) {
        TopAppBar(
            title = { Text("图库") },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LibraryBrowseTabs(mode, onModeSelected)
        if (collections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = mode.emptyText(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(collections, key = GalleryCollectionSummary::key) { collection ->
                    CollectionRow(mode, collection, onOpenCollection)
                    HorizontalDivider(Modifier.padding(start = 92.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryBrowseTabs(
    selected: LibraryBrowseMode,
    onModeSelected: (LibraryBrowseMode) -> Unit,
) {
    val modes = LibraryBrowseMode.entries
    PrimaryTabRow(selectedTabIndex = modes.indexOf(selected)) {
        modes.forEach { mode ->
            Tab(
                selected = mode == selected,
                onClick = { onModeSelected(mode) },
                text = { Text(mode.title()) },
                modifier = Modifier.testTag("library_tab_${mode.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun CollectionRow(
    mode: LibraryBrowseMode,
    collection: GalleryCollectionSummary,
    onClick: (GalleryCollectionSummary) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onClick(collection) }
            .testTag("library_collection_${collection.key}"),
        leadingContent = {
            CollectionCover(collection.coverUri, mode.icon())
        },
        headlineContent = { Text(collection.displayName, maxLines = 1) },
        supportingContent = { Text("${collection.imageCount} 张") },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
    )
}

@Composable
private fun CollectionCover(coverUri: String?, fallback: ImageVector) {
    Box(
        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUri == null) {
            Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(
                model = Uri.parse(coverUri),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun LibraryBrowseMode.collectionType(): GalleryCollectionType? = when (this) {
    LibraryBrowseMode.IMAGES -> null
    LibraryBrowseMode.ALBUMS -> GalleryCollectionType.ALBUM
    LibraryBrowseMode.TAGS -> GalleryCollectionType.TAG
    LibraryBrowseMode.CATEGORIES -> GalleryCollectionType.CATEGORY
}

private fun LibraryBrowseMode.title(): String = when (this) {
    LibraryBrowseMode.IMAGES -> "图片"
    LibraryBrowseMode.ALBUMS -> "相册"
    LibraryBrowseMode.TAGS -> "标签"
    LibraryBrowseMode.CATEGORIES -> "分类"
}

private fun LibraryBrowseMode.emptyText(): String = when (this) {
    LibraryBrowseMode.IMAGES -> "暂无图片"
    LibraryBrowseMode.ALBUMS -> "暂无系统相册"
    LibraryBrowseMode.TAGS -> "暂无图片标签"
    LibraryBrowseMode.CATEGORIES -> "暂无图片分类"
}

private fun LibraryBrowseMode.icon(): ImageVector = when (this) {
    LibraryBrowseMode.IMAGES,
    LibraryBrowseMode.ALBUMS,
    -> Icons.Outlined.PhotoAlbum
    LibraryBrowseMode.TAGS -> Icons.AutoMirrored.Outlined.Label
    LibraryBrowseMode.CATEGORIES -> Icons.Outlined.Category
}

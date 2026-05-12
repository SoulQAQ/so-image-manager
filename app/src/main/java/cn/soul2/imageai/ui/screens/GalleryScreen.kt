package cn.soul2.imageai.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.soul2.imageai.data.api.AiService
import cn.soul2.imageai.domain.service.ImagePreprocessor
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectedImage(
    val uri: String,
    val name: String,
    val tagStatus: TagStatus = TagStatus.IDLE,
    val tagMessage: String? = null,
    val aiCaption: String? = null,
    val aiTags: List<String> = emptyList(),
    val aiCategories: List<String> = emptyList(),
    val aiSearchTokens: List<String> = emptyList()
)

enum class TagStatus {
    IDLE,
    PROCESSING,
    SUCCESS,
    FAILED
}

data class AlbumEntry(
    val bucketId: String,
    val bucketName: String,
    val count: Int
)

object GalleryStore {
    private val images = mutableListOf<SelectedImage>()
    fun set(list: List<SelectedImage>) {
        images.clear()
        images.addAll(list)
    }
    fun get(index: Int): SelectedImage? = images.getOrNull(index)
}

class GalleryViewModel : ViewModel() {
    private val _selectedImages = MutableStateFlow<List<SelectedImage>>(emptyList())
    val selectedImages: StateFlow<List<SelectedImage>> = _selectedImages.asStateFlow()

    private val _markedForLabel = MutableStateFlow<Set<Int>>(emptySet())
    val markedForLabel: StateFlow<Set<Int>> = _markedForLabel.asStateFlow()

    private val _isTagging = MutableStateFlow(false)
    val isTagging: StateFlow<Boolean> = _isTagging.asStateFlow()

    val isSelectMode: Boolean
        get() = _markedForLabel.value.isNotEmpty()

    fun addImages(items: List<Pair<String, String>>) {
        val current = _selectedImages.value.toMutableList()
        for ((uri, name) in items) {
            if (!current.any { it.uri == uri }) {
                current.add(SelectedImage(uri = uri, name = name))
            }
        }
        _selectedImages.value = current
        GalleryStore.set(_selectedImages.value)
    }

    fun toggleMarkForLabel(index: Int) {
        val current = _markedForLabel.value.toMutableSet()
        if (current.contains(index)) {
            current.remove(index)
        } else {
            current.add(index)
        }
        _markedForLabel.value = current
    }

    fun selectAll(indices: IntRange) {
        _markedForLabel.value = indices.toSet()
    }

    fun clearSelection() {
        _markedForLabel.value = emptySet()
    }

    fun removeImage(index: Int) {
        val current = _selectedImages.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _selectedImages.value = current
            val marked = _markedForLabel.value.toMutableSet()
            marked.remove(index)
            _markedForLabel.value = marked.map { if (it > index) it - 1 else it }.toSet()
            GalleryStore.set(_selectedImages.value)
        }
    }

    fun runAiLabeling(context: Context, visibleToAbsolute: Map<Int, Int>) {
        if (_isTagging.value || _markedForLabel.value.isEmpty()) return

        val selectedVisibleIndexes = _markedForLabel.value.toList().sorted()
        _isTagging.value = true

        viewModelScope.launch {
            val preprocessor = ImagePreprocessor(context)
            val aiService = AiService()

            for (visibleIndex in selectedVisibleIndexes) {
                val absIndex = visibleToAbsolute[visibleIndex] ?: continue
                val item = _selectedImages.value.getOrNull(absIndex) ?: continue
                updateStatus(absIndex, TagStatus.PROCESSING, "正在标注", null, emptyList(), emptyList(), emptyList())

                val result = withContext(Dispatchers.IO) {
                    try {
                        val uri = Uri.parse(item.uri)
                        val bytes = preprocessor.compressImage(uri)
                        if (bytes == null) Result.failure(Exception("图片预处理失败")) else aiService.analyzeImage(uri, bytes)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }

                if (result.isSuccess) {
                    val ai = result.getOrNull()
                    updateStatus(
                        absIndex,
                        TagStatus.SUCCESS,
                        "标注完成",
                        ai?.caption,
                        ai?.tags?.map { it.name } ?: emptyList(),
                        ai?.categories ?: emptyList(),
                        ai?.searchTokens ?: emptyList()
                    )
                } else {
                    updateStatus(absIndex, TagStatus.FAILED, result.exceptionOrNull()?.message ?: "标注失败", null, emptyList(), emptyList(), emptyList())
                }
            }

            _isTagging.value = false
            clearSelection()
        }
    }

    private fun updateStatus(
        absIndex: Int,
        status: TagStatus,
        message: String?,
        caption: String?,
        tags: List<String>,
        categories: List<String>,
        searchTokens: List<String>
    ) {
        val current = _selectedImages.value.toMutableList()
        if (absIndex !in current.indices) return
        current[absIndex] = current[absIndex].copy(
            tagStatus = status,
            tagMessage = message,
            aiCaption = caption,
            aiTags = tags,
            aiCategories = categories,
            aiSearchTokens = searchTokens
        )
        _selectedImages.value = current
        GalleryStore.set(_selectedImages.value)
    }
}

@Composable
fun GalleryScreenDetailBridge(index: Int, onBack: () -> Unit) {
    val image = GalleryStore.get(index)
    if (image != null) {
        ImageDetailScreen(image = image, onBack = onBack)
    } else {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text("图片不存在或已移除")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpenDetail: (Int) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWebView: () -> Unit,
    viewModel: GalleryViewModel = viewModel()
) {
    val context = LocalContext.current
    val selectedImages by viewModel.selectedImages.collectAsState()
    val markedForLabel by viewModel.markedForLabel.collectAsState()
    val isTagging by viewModel.isTagging.collectAsState()
    val isSelectMode = viewModel.isSelectMode

    var searchText by remember { mutableStateOf("") }
    var showAlbumDialog by remember { mutableStateOf(false) }
    var albumEntries by remember { mutableStateOf<List<AlbumEntry>>(emptyList()) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        val items = uris.map { uri ->
            val name = uri.lastPathSegment ?: "image_${System.currentTimeMillis()}"
            uri.toString() to name
        }
        viewModel.addImages(items)
    }

    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            val images = loadImageUrisFromTree(context, treeUri)
            viewModel.addImages(images)
        }
    }

    val filteredWithIndex = remember(selectedImages, searchText) {
        selectedImages.mapIndexed { idx, item -> idx to item }
            .filter { (_, item) ->
                if (searchText.isBlank()) true
                else {
                    val q = searchText.trim()
                    item.name.contains(q, true) ||
                        (item.aiCaption?.contains(q, true) == true) ||
                        item.aiTags.any { it.contains(q, true) } ||
                        item.aiSearchTokens.any { it.contains(q, true) }
                }
            }
    }
    val filteredImages = filteredWithIndex.map { it.second }
    val visibleToAbsolute = filteredWithIndex.mapIndexed { v, pair -> v to pair.first }.toMap()

    if (showAlbumDialog) {
        AlertDialog(
            onDismissRequest = { showAlbumDialog = false },
            title = { Text("选择图集") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (albumEntries.isEmpty()) {
                        Text("当前无可见图集")
                    } else {
                        albumEntries.forEach { album ->
                            TextButton(
                                onClick = {
                                    val images = loadImagesByBucket(context, album.bucketId)
                                    viewModel.addImages(images)
                                    showAlbumDialog = false
                                }
                            ) {
                                Text(album.bucketName)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAlbumDialog = false
                    treePickerLauncher.launch(null)
                }) { Text("选择文档目录") }
            },
            dismissButton = {
                TextButton(onClick = { showAlbumDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("ImageAI") },
                    actions = {
                        IconButton(onClick = onNavigateToWebView) {
                            Icon(Icons.Default.Search, contentDescription = "搜索管理")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                    placeholder = { Text("搜索：文件名/描述/标签") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }
    ) { paddingValues ->
        if (selectedImages.isEmpty()) {
            EmptyStateContent(
                onSelectImages = {
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onSelectAlbum = {
                    albumEntries = listAlbums(context)
                    showAlbumDialog = true
                },
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            Column(modifier = Modifier.padding(paddingValues)) {
                AnimatedVisibility(visible = isSelectMode, enter = fadeIn(), exit = fadeOut()) {
                    SelectionActionBar(
                        selectedCount = markedForLabel.size,
                        totalCount = filteredImages.size,
                        onSelectAll = { viewModel.selectAll(filteredImages.indices) },
                        onCancel = { viewModel.clearSelection() }
                    )
                }

                ImageGridWithSelection(
                    images = filteredImages,
                    markedIndices = markedForLabel,
                    onImageClick = { visibleIndex ->
                        if (isSelectMode) viewModel.toggleMarkForLabel(visibleIndex)
                        else visibleToAbsolute[visibleIndex]?.let(onOpenDetail)
                    },
                    onImageLongClick = { visibleIndex ->
                        if (!isSelectMode) viewModel.toggleMarkForLabel(visibleIndex)
                    },
                    onRemoveImage = { visibleIndex ->
                        visibleToAbsolute[visibleIndex]?.let { viewModel.removeImage(it) }
                    },
                    onAddMoreImages = {
                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onAddAlbum = {
                        albumEntries = listAlbums(context)
                        showAlbumDialog = true
                    },
                    modifier = Modifier.weight(1f)
                )

                AnimatedVisibility(
                    visible = markedForLabel.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isTagging) { viewModel.runAiLabeling(context, visibleToAbsolute) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isTagging) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (isTagging) "AI 标注中..." else "AI 标注 (${markedForLabel.size})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateContent(
    onSelectImages: () -> Unit,
    onSelectAlbum: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(14.dp))
        Text("添加图集或图片", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "仅处理你主动选择的图集或图片，不会全盘扫描系统相册。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onSelectAlbum) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("按图集添加")
            }
            OutlinedButton(onClick = onSelectImages) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("按图片添加")
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("返回") }
            Spacer(Modifier.weight(1f))
            Text(
                "已选 $selectedCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSelectAll, enabled = selectedCount < totalCount) { Text("全选") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGridWithSelection(
    images: List<SelectedImage>,
    markedIndices: Set<Int>,
    onImageClick: (Int) -> Unit,
    onImageLongClick: (Int) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onAddMoreImages: () -> Unit,
    onAddAlbum: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        modifier = modifier.fillMaxSize()
    ) {
        itemsIndexed(images) { index, image ->
            ImageCardModern(
                image = image,
                isSelected = markedIndices.contains(index),
                onClick = { onImageClick(index) },
                onLongClick = { onImageLongClick(index) },
                onRemove = { onRemoveImage(index) }
            )
        }
        item { AddCard(label = "图片", icon = Icons.Default.AddPhotoAlternate, onClick = onAddMoreImages) }
        item { AddCard(label = "图集", icon = Icons.Default.FolderOpen, onClick = onAddAlbum) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageCardModern(
    image: SelectedImage,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.94f else 1f,
        animationSpec = tween(120),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(3.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(image.uri)
                .crossfade(true)
                .build(),
            contentDescription = image.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(18.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Check, contentDescription = "已选择", modifier = Modifier.padding(2.dp))
            }
        }

        if (!isSelected) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除",
                    tint = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        if (image.tagStatus != TagStatus.IDLE) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(4.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
            ) {
                Text(
                    text = when (image.tagStatus) {
                        TagStatus.PROCESSING -> "标注中"
                        TagStatus.SUCCESS -> "已完成"
                        TagStatus.FAILED -> "失败"
                        TagStatus.IDLE -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AddCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .aspectRatio(1f)
            .padding(3.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text("添加$label", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun loadImageUrisFromTree(context: Context, treeUri: Uri): List<Pair<String, String>> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    val result = mutableListOf<Pair<String, String>>()

    fun walk(dir: DocumentFile) {
        if (dir.isDirectory) {
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    walk(file)
                } else {
                    val mime = file.type ?: ""
                    if (mime.startsWith("image/")) {
                        result.add(file.uri.toString() to (file.name ?: "image"))
                    }
                }
            }
        }
    }

    walk(root)
    return result
}

private fun listAlbums(context: Context): List<AlbumEntry> {
    val map = linkedMapOf<String, Pair<String, Int>>()
    val projection = arrayOf(
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    )
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        null
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val id = cursor.getString(idCol)
            val name = cursor.getString(nameCol) ?: "未命名图集"
            val old = map[id]
            map[id] = if (old == null) name to 1 else old.first to old.second + 1
        }
    }
    return map.entries.map { AlbumEntry(it.key, it.value.first, it.value.second) }
        .sortedByDescending { it.count }
}

private fun loadImagesByBucket(context: Context, bucketId: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
    val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
    val args = arrayOf(bucketId)
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: "image_$id"
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            result.add(uri.toString() to name)
        }
    }
    return result
}

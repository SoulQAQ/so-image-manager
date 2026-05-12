package cn.soul2.imageai.ui.screens

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SelectedImage(
    val uri: String,
    val name: String
)

class GalleryViewModel : ViewModel() {
    private val _selectedImages = MutableStateFlow<List<SelectedImage>>(emptyList())
    val selectedImages: StateFlow<List<SelectedImage>> = _selectedImages.asStateFlow()

    private val _markedForLabel = MutableStateFlow<Set<Int>>(emptySet())
    val markedForLabel: StateFlow<Set<Int>> = _markedForLabel.asStateFlow()

    val isSelectMode: Boolean
        get() = _markedForLabel.value.isNotEmpty()

    fun addImage(uri: String, name: String) {
        val current = _selectedImages.value.toMutableList()
        if (!current.any { it.uri == uri }) {
            current.add(SelectedImage(uri, name))
            _selectedImages.value = current
        }
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

    fun selectAll() {
        _markedForLabel.value = _selectedImages.value.indices.toSet()
    }

    fun clearSelection() {
        _markedForLabel.value = emptySet()
    }

    fun removeImage(index: Int) {
        val current = _selectedImages.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _selectedImages.value = current
            // 调整选中状态
            val marked = _markedForLabel.value.toMutableSet()
            marked.remove(index)
            _markedForLabel.value = marked.map { if (it > index) it - 1 else it }.toSet()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToWebView: () -> Unit,
    viewModel: GalleryViewModel = viewModel()
) {
    val context = LocalContext.current
    val selectedImages by viewModel.selectedImages.collectAsState()
    val markedForLabel by viewModel.markedForLabel.collectAsState()
    val isSelectMode = viewModel.isSelectMode

    // PhotoPicker - 多选图片
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        uris.forEach { uri ->
            val name = uri.lastPathSegment ?: "image_${System.currentTimeMillis()}"
            viewModel.addImage(uri.toString(), name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "ImageAI",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (selectedImages.isNotEmpty()) {
                            Text(
                                "已添加 ${selectedImages.size} 张图片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (selectedImages.isEmpty()) {
            EmptyStateContent(
                onSelectImages = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onOpenWebView = onNavigateToWebView,
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            Column(modifier = Modifier.padding(paddingValues)) {
                AnimatedVisibility(
                    visible = isSelectMode,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    SelectionActionBar(
                        selectedCount = markedForLabel.size,
                        onSelectAll = { viewModel.selectAll() },
                        onCancel = { viewModel.clearSelection() }
                    )
                }

                ImageGridWithSelection(
                    images = selectedImages,
                    markedIndices = markedForLabel,
                    onImageClick = { index ->
                        if (isSelectMode) {
                            viewModel.toggleMarkForLabel(index)
                        }
                    },
                    onImageLongClick = { index ->
                        if (!isSelectMode) {
                            viewModel.toggleMarkForLabel(index)
                        }
                    },
                    onRemoveImage = { viewModel.removeImage(it) },
                    onAddMore = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
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
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable { /* TODO: AI标注逻辑 */ }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "AI 标注 (${markedForLabel.size} 张)",
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
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
    onOpenWebView: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "选择图片开始",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "应用仅处理你主动选择的照片",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        FilledTonalButton(
            onClick = onSelectImages,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("选择图片")
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onOpenWebView,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text("打开管理界面")
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "已选 $selectedCount 张",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSelectAll) {
                Text("全选")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ImageGridWithSelection(
    images: List<SelectedImage>,
    markedIndices: Set<Int>,
    onImageClick: (Int) -> Unit,
    onImageLongClick: (Int) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onAddMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(2.dp),
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
        item {
            AddMoreCard(onClick = onAddMore)
        }
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
        targetValue = if (isSelected) 0.92f else 1f,
        animationSpec = tween(150),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .scale(scale)
            .clip(RoundedCornerShape(6.dp))
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
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        )

        // 选中态边框
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            )
            // 小勾选标记 - 右上角
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选择",
                    modifier = Modifier
                        .padding(2.dp)
                        .size(16.dp)
                )
            }
        }

        // 删除按钮 - 左上角（非选中状态）
        if (!isSelected) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun AddMoreCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加更多",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "添加",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
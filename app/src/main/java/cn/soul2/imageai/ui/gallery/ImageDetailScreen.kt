package cn.soul2.imageai.ui.gallery

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cn.soul2.imageai.R
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryRepository
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object ImageDetailDestination {
    const val localIdArgument = "localId"
    const val route = "image/{$localIdArgument}"

    fun createRoute(localId: Long): String {
        require(localId > 0L) { "localId must be positive" }
        return "image/$localId"
    }
}

@Composable
fun ImageDetailScreen(
    repository: GalleryRepository,
    localId: Long,
    onBack: () -> Unit,
) {
    val viewModel: ImageDetailViewModel = viewModel(
        key = "image_detail_$localId",
        factory = ImageDetailViewModel.factory(repository, localId),
    )
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("screen_image_detail"),
    ) {
        when (val state = uiState) {
            ImageDetailUiState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            ImageDetailUiState.Missing -> Text(
                text = stringResource(R.string.image_detail_missing),
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            is ImageDetailUiState.Ready -> {
                ZoomableImage(state.image)
                MetadataPanel(state.image, Modifier.align(Alignment.BottomCenter))
            }
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.image_detail_back),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ZoomableImage(image: GalleryImage) {
    val context = LocalContext.current
    var scale by remember(image.localId) { mutableFloatStateOf(1f) }
    var offset by remember(image.localId) { mutableStateOf(Offset.Zero) }
    var viewport by remember(image.localId) { mutableStateOf(IntSize.Zero) }
    val request = remember(image.contentUri) {
        ImageRequest.Builder(context)
            .data(Uri.parse(image.contentUri))
            .crossfade(false)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = image.displayName,
        contentScale = ContentScale.Fit,
        error = rememberVectorPainter(Icons.Outlined.BrokenImage),
        fallback = rememberVectorPainter(Icons.Outlined.BrokenImage),
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(image.localId, viewport) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    if (nextScale == 1f) {
                        offset = Offset.Zero
                    } else {
                        val maxX = viewport.width * (nextScale - 1f) / 2f
                        val maxY = viewport.height * (nextScale - 1f) / 2f
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                            y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                        )
                    }
                    scale = nextScale
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .testTag("image_detail_zoomable"),
    )
}

@Composable
private fun MetadataPanel(image: GalleryImage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timestamp = image.capturedAtEpochMillis ?: image.modifiedAtEpochMillis
    val formattedDate = remember(timestamp) {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.SIMPLIFIED_CHINESE,
        ).format(Date(timestamp))
    }
    val formattedSize = remember(image.sizeBytes) {
        Formatter.formatShortFileSize(context, image.sizeBytes)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = image.displayName,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.image_detail_dimensions, image.width, image.height),
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = image.mimeType,
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = formattedSize,
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = image.bucketName ?: stringResource(R.string.image_detail_unknown_album),
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = formattedDate,
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

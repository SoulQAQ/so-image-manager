package cn.soul2.imageai.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cn.soul2.imageai.gallery.GalleryImage

enum class GalleryTileLayout {
    OriginalAspect,
    Square,
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun GalleryImageTile(
    image: GalleryImage,
    layout: GalleryTileLayout,
    onClick: (Long) -> Unit,
    onLongClick: ((GalleryImage) -> Unit)? = null,
    onPreview: ((Long) -> Unit)? = null,
    selected: Boolean = false,
    selectionMode: Boolean = selected,
    modifier: Modifier = Modifier,
) {
    val aspectRatio = galleryTileAspectRatio(image, layout)
    val background = MaterialTheme.colorScheme.surfaceVariant
    val context = LocalContext.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RectangleShape)
            .background(background)
            .combinedClickable(
                role = Role.Button,
                onClick = { onClick(image.localId) },
                onLongClick = { onLongClick?.invoke(image) },
            )
            .testTag("gallery_image_${image.localId}"),
    ) {
        val requestWidth = constraints.maxWidth.coerceAtLeast(1)
        val requestHeight = constraints.maxHeight.coerceAtLeast(1)
        val request = remember(image.contentUri, requestWidth, requestHeight) {
            ImageRequest.Builder(context)
                .data(Uri.parse(image.contentUri))
                .size(requestWidth, requestHeight)
                .crossfade(false)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = image.displayName,
            modifier = Modifier.matchParentSize(),
            placeholder = ColorPainter(background),
            error = rememberVectorPainter(Icons.Outlined.BrokenImage),
            fallback = rememberVectorPainter(Icons.Outlined.BrokenImage),
            contentScale = when (layout) {
                GalleryTileLayout.OriginalAspect -> ContentScale.Fit
                GalleryTileLayout.Square -> ContentScale.Crop
            },
            colorFilter = null,
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = 0.24f))
                    .testTag("gallery_selection_scrim_${image.localId}"),
            )
        }
        if (selectionMode) {
            SelectionIndicator(
                selected = selected,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(6.dp)
                    .testTag("gallery_selection_indicator_${image.localId}"),
            )
        }
        if (selected && onPreview != null) {
            IconButton(
                onClick = { onPreview(image.localId) },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                    .padding(6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .testTag("gallery_preview_${image.localId}"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ZoomOutMap,
                    contentDescription = "全屏预览",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Black.copy(alpha = 0.32f)
        },
        border = BorderStroke(2.dp, Color.White),
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        if (selected) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "已选择",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

fun galleryTileAspectRatio(image: GalleryImage, layout: GalleryTileLayout): Float = when (layout) {
    GalleryTileLayout.OriginalAspect -> image.originalAspectRatio
    GalleryTileLayout.Square -> 1f
}

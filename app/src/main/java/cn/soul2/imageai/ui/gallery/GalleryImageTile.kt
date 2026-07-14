package cn.soul2.imageai.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cn.soul2.imageai.gallery.GalleryImage

enum class GalleryTileLayout {
    OriginalAspect,
    Square,
}

@Composable
fun GalleryImageTile(
    image: GalleryImage,
    layout: GalleryTileLayout,
    onClick: (Long) -> Unit,
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
            .clickable(role = Role.Button) { onClick(image.localId) }
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
    }
}

fun galleryTileAspectRatio(image: GalleryImage, layout: GalleryTileLayout): Float = when (layout) {
    GalleryTileLayout.OriginalAspect -> image.originalAspectRatio
    GalleryTileLayout.Square -> 1f
}

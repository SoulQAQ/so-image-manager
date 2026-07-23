package cn.soul2.imageai.ui.gallery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cn.soul2.imageai.R
import cn.soul2.imageai.ai.analysis.SingleImageAnalysisFailure
import cn.soul2.imageai.ai.analysis.SingleImageAnalyzer
import cn.soul2.imageai.analysis.EffectiveImageMetadata
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.analysis.CorrectionCommand
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction
import cn.soul2.imageai.gallery.GalleryImage
import cn.soul2.imageai.gallery.GalleryImageWindow
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.gallery.GallerySource
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
    singleImageAnalyzer: SingleImageAnalyzer? = null,
    canonicalMetadataRepository: CanonicalMetadataRepository? = null,
    localId: Long,
    source: GallerySource = GallerySource.All,
    onBack: () -> Unit,
) {
    key(localId) {
    val viewModel: ImageDetailViewModel = viewModel(
        key = "image_detail_${source::class.simpleName}_$localId",
        factory = ImageDetailViewModel.factory(
            repository,
            localId,
            singleImageAnalyzer,
            canonicalMetadataRepository,
            source,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()
    val metadata by viewModel.effectiveMetadata.collectAsState()
    val analysisState by viewModel.analysisState.collectAsState()
    val correctionState by viewModel.correctionState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var showFullAiResult by remember { mutableStateOf(false) }

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
                LaunchedEffect(state.image.localId) {
                    controlsVisible = true
                }
                ImageDetailPager(
                    window = state.window,
                    onSelectImage = viewModel::showImage,
                    onToggleControls = { controlsVisible = !controlsVisible },
                )
                if (controlsVisible) {
                    DetailTopBar(
                        image = state.image,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    DetailActionBar(
                        image = state.image,
                        metadata = metadata,
                        analysisState = analysisState,
                        onAnalyze = viewModel::analyzeCurrentImage,
                        onShowFullResult = { showFullAiResult = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
    if (showFullAiResult && metadata?.activeAnalysis != null) {
        FullAiResultSheet(
            metadata = requireNotNull(metadata),
            correctionState = correctionState,
            onCorrection = viewModel::applyCorrection,
            onDismiss = { showFullAiResult = false },
        )
    }
    }
}

@Composable
private fun ImageDetailPager(
    window: GalleryImageWindow,
    onSelectImage: (Long) -> Unit,
    onToggleControls: () -> Unit,
) {
    val model = detailPagerModel(window)

    key(model.stateKey) {
        val pagerState = rememberPagerState(
            initialPage = model.currentPage,
            pageCount = model.images::size,
        )
        LaunchedEffect(pagerState.settledPage) {
            val settledImage = model.images[pagerState.settledPage]
            if (settledImage.localId != window.current.localId) {
                onSelectImage(settledImage.localId)
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().testTag("image_detail_pager"),
            key = { page -> model.images[page].localId },
        ) { page ->
            val image = model.images[page]
            Box(Modifier.fillMaxSize()) {
                ZoomableImage(
                    image = image,
                    onToggleControls = onToggleControls,
                )
            }
        }
    }
}

internal data class ImageDetailPagerModel(
    val images: List<GalleryImage>,
    val currentPage: Int,
    val stateKey: List<Long>,
)

internal fun detailPagerModel(window: GalleryImageWindow): ImageDetailPagerModel {
    val images = buildList {
        window.previous?.let(::add)
        add(window.current)
        window.next?.let(::add)
    }
    return ImageDetailPagerModel(
        images = images,
        currentPage = if (window.previous == null) 0 else 1,
        stateKey = images.map(GalleryImage::localId),
    )
}

@Composable
private fun ZoomableImage(
    image: GalleryImage,
    onToggleControls: () -> Unit,
) {
    val context = LocalContext.current
    var scale by remember(image.localId) { mutableFloatStateOf(1f) }
    var offset by remember(image.localId) { mutableStateOf(Offset.Zero) }
    var viewport by remember(image.localId) { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
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
        contentScale = ContentScale.Fit,
        error = rememberVectorPainter(Icons.Outlined.BrokenImage),
        fallback = rememberVectorPainter(Icons.Outlined.BrokenImage),
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(image.localId) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                        }
                    },
                )
            }
            .pointerInput(image.localId, viewport) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        if (pressedPointers >= 2 || scale > 1f) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            if (nextScale == 1f) {
                                offset = Offset.Zero
                            } else {
                                val pan = event.calculatePan()
                                val maxX = viewport.width * (nextScale - 1f) / 2f
                                val maxY = viewport.height * (nextScale - 1f) / 2f
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                            scale = nextScale
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
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
}

@Composable
private fun DetailActionBar(
    image: GalleryImage,
    metadata: EffectiveImageMetadata?,
    analysisState: ImageAnalysisUiState,
    onAnalyze: () -> Unit,
    onShowFullResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentAnalysis = analysisState.takeIf {
        when (it) {
            ImageAnalysisUiState.Idle -> true
            is ImageAnalysisUiState.Running -> it.imageLocalId == image.localId
            is ImageAnalysisUiState.Success -> it.imageLocalId == image.localId
            is ImageAnalysisUiState.Failure -> it.imageLocalId == image.localId
        }
    } ?: ImageAnalysisUiState.Idle
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable { }
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("image_detail_controls"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val failure = currentAnalysis as? ImageAnalysisUiState.Failure
        if (failure != null) {
            Text(
                text = stringResource(analysisFailureMessage(failure.reason)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = metadata?.caption.orEmpty(),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MetadataTermLine(
            label = stringResource(R.string.image_analysis_tags),
            values = metadata?.terms
                ?.filter { it.kind == AnalysisTermKind.TAG }
                ?.map { it.displayValue }
                .orEmpty(),
            color = Color.White.copy(alpha = 0.76f),
        )
        if (metadata?.activeAnalysis != null) {
            TextButton(
                onClick = onShowFullResult,
                modifier = Modifier.testTag("image_detail_full_ai_result"),
            ) {
                Text(stringResource(R.string.image_analysis_view_full_result))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = imageDetailSummary(image),
                color = Color.White.copy(alpha = 0.76f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onAnalyze,
                enabled = currentAnalysis !is ImageAnalysisUiState.Running,
                modifier = Modifier.testTag("image_detail_analyze"),
            ) {
                if (currentAnalysis is ImageAnalysisUiState.Running) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Text(
                        stringResource(
                            if (metadata?.activeAnalysis != null) {
                                R.string.image_analysis_again
                            } else {
                                R.string.image_analysis_start
                            },
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullAiResultSheet(
    metadata: EffectiveImageMetadata,
    correctionState: ImageCorrectionUiState,
    onCorrection: (CorrectionCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.image_analysis_full_result_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            metadata.caption?.let { caption ->
                SelectionContainer {
                    Text(caption, style = MaterialTheme.typography.bodyLarge)
                }
            }
            EditableCaption(
                metadata = metadata,
                saving = correctionState is ImageCorrectionUiState.Saving,
                onCorrection = onCorrection,
            )
            FullAiTermSection(
                title = R.string.image_analysis_tags,
                values = metadata.terms
                    .filter { it.kind == AnalysisTermKind.TAG }
                    .map { it.displayValue },
                kind = AnalysisTermKind.TAG,
                saving = correctionState is ImageCorrectionUiState.Saving,
                onCorrection = onCorrection,
            )
            FullAiTermSection(
                title = R.string.image_analysis_categories,
                values = metadata.terms
                    .filter { it.kind == AnalysisTermKind.CATEGORY }
                    .map { it.displayValue },
                kind = AnalysisTermKind.CATEGORY,
                saving = correctionState is ImageCorrectionUiState.Saving,
                onCorrection = onCorrection,
            )
            EditableTermAdd(
                saving = correctionState is ImageCorrectionUiState.Saving,
                onCorrection = onCorrection,
            )
            metadata.termOverrides
                .filter { it.action == UserTermOverrideAction.TOMBSTONE }
                .forEach { override ->
                    TextButton(
                        onClick = {
                            onCorrection(
                                CorrectionCommand.RestoreTerm(
                                    override.kind,
                                    override.normalizedKey,
                                ),
                            )
                        },
                        enabled = correctionState !is ImageCorrectionUiState.Saving,
                    ) {
                        Text(
                            stringResource(
                                R.string.image_analysis_restore_term,
                                override.normalizedKey,
                            ),
                        )
                    }
                }
            if (correctionState is ImageCorrectionUiState.Failed) {
                Text(
                    text = stringResource(R.string.image_analysis_correction_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FullAiTermSection(
    @androidx.annotation.StringRes title: Int,
    values: List<String>,
    kind: AnalysisTermKind,
    saving: Boolean,
    onCorrection: (CorrectionCommand) -> Unit,
) {
    if (values.isNotEmpty()) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        values.forEach { value ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = { onCorrection(CorrectionCommand.DeleteTerm(kind, value)) },
                    enabled = !saving,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.image_analysis_delete_term, value),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableCaption(
    metadata: EffectiveImageMetadata,
    saving: Boolean,
    onCorrection: (CorrectionCommand) -> Unit,
) {
    var draft by remember(metadata.projectionGeneration) { mutableStateOf(metadata.caption.orEmpty()) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.image_analysis_edit_caption)) },
        minLines = 3,
        enabled = !saving,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                onCorrection(
                    if (draft.isBlank()) CorrectionCommand.ClearCaption
                    else CorrectionCommand.SetCaption(draft),
                )
            },
            enabled = !saving,
        ) { Text(stringResource(R.string.image_analysis_save_caption)) }
        TextButton(
            onClick = { onCorrection(CorrectionCommand.ClearCaption) },
            enabled = !saving && metadata.caption != null,
        ) { Text(stringResource(R.string.image_analysis_clear_caption)) }
        if (metadata.captionCorrection != null) {
            TextButton(
                onClick = { onCorrection(CorrectionCommand.InheritCaption) },
                enabled = !saving,
            ) { Text(stringResource(R.string.image_analysis_restore_caption)) }
        }
    }
}

@Composable
private fun EditableTermAdd(
    saving: Boolean,
    onCorrection: (CorrectionCommand) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AnalysisTermKind.TAG) }
    Text(
        text = stringResource(R.string.image_analysis_add_term),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = kind == AnalysisTermKind.TAG,
            onClick = { kind = AnalysisTermKind.TAG },
            label = { Text(stringResource(R.string.image_analysis_tags)) },
            enabled = !saving,
        )
        FilterChip(
            selected = kind == AnalysisTermKind.CATEGORY,
            onClick = { kind = AnalysisTermKind.CATEGORY },
            label = { Text(stringResource(R.string.image_analysis_categories)) },
            enabled = !saving,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.image_analysis_term_value)) },
            singleLine = true,
            enabled = !saving,
        )
        TextButton(
            onClick = {
                onCorrection(CorrectionCommand.AddTerm(kind, value))
                value = ""
            },
            enabled = !saving && value.isNotBlank(),
        ) { Text(stringResource(R.string.image_analysis_add_term_action)) }
    }
}

@Composable
private fun DetailTopBar(
    image: GalleryImage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timestamp = image.capturedAtEpochMillis ?: image.modifiedAtEpochMillis
    val formattedDate = remember(timestamp) {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.SIMPLIFIED_CHINESE,
        ).format(Date(timestamp))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable { }
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.image_detail_back),
                tint = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = image.displayName,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = formattedDate,
                color = Color.White.copy(alpha = 0.76f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun imageDetailSummary(image: GalleryImage): String = buildString {
    append(image.width)
    append('×')
    append(image.height)
    append(" · ")
    append(image.mimeType)
}

@Composable
private fun MetadataTermLine(label: String, values: List<String>, color: Color) {
    if (values.isNotEmpty()) {
        Text(
            text = "$label：${values.joinToString("、")}",
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@androidx.annotation.StringRes
private fun analysisFailureMessage(failure: SingleImageAnalysisFailure): Int = when (failure) {
    SingleImageAnalysisFailure.CONFIGURATION_REQUIRED -> R.string.image_analysis_error_configuration
    SingleImageAnalysisFailure.IMAGE_UNAVAILABLE -> R.string.image_analysis_error_image
    SingleImageAnalysisFailure.IMAGE_PREPARATION_FAILED -> R.string.image_analysis_error_prepare
    SingleImageAnalysisFailure.PROTOCOL_UNSUPPORTED -> R.string.image_analysis_error_protocol
    SingleImageAnalysisFailure.CREDENTIAL_REQUIRED -> R.string.image_analysis_error_credential
    SingleImageAnalysisFailure.CREDENTIAL_UNAVAILABLE -> R.string.image_analysis_error_credential_unavailable
    SingleImageAnalysisFailure.REQUEST_LIMITED -> R.string.image_analysis_error_limited
    SingleImageAnalysisFailure.NETWORK_FAILED -> R.string.image_analysis_error_network
    SingleImageAnalysisFailure.PROVIDER_REJECTED -> R.string.image_analysis_error_provider
    SingleImageAnalysisFailure.RESPONSE_INVALID -> R.string.image_analysis_error_response
    SingleImageAnalysisFailure.INDEX_PROJECTION_BLOCKED -> R.string.image_analysis_error_index
    SingleImageAnalysisFailure.INTERNAL_ERROR -> R.string.image_analysis_error_internal
}

private const val DOUBLE_TAP_SCALE = 2.5f

package cn.soul2.imageai.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.soul2.imageai.R
import cn.soul2.imageai.gallery.GalleryRepository
import cn.soul2.imageai.search.ImageSearchRepository
import cn.soul2.imageai.search.SearchField
import cn.soul2.imageai.search.SearchPartialReason
import cn.soul2.imageai.search.SearchResult
import cn.soul2.imageai.search.SearchTier
import cn.soul2.imageai.ui.gallery.GalleryImageTile
import cn.soul2.imageai.ui.gallery.GalleryTileLayout

object SearchDestination {
    const val route = "search"
}

@Composable
fun SearchScreen(
    searchRepository: ImageSearchRepository,
    galleryRepository: GalleryRepository,
    onBack: () -> Unit,
    onImageClick: (Long) -> Unit,
    onRebuildIndex: () -> Unit,
) {
    val viewModel: SearchViewModel = viewModel(
        key = "image_search",
        factory = SearchViewModel.factory(searchRepository, galleryRepository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SearchScreenContent(
        uiState = uiState,
        onQueryChanged = viewModel::onQueryChanged,
        onClear = viewModel::clearQuery,
        onBack = onBack,
        onImageClick = onImageClick,
        onRebuildIndex = onRebuildIndex,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreenContent(
    uiState: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onImageClick: (Long) -> Unit,
    onRebuildIndex: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().testTag("screen_search")) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.search_back),
                    )
                }
            },
            title = {
                TextField(
                    value = uiState.query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        .testTag("search_input"),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { keyboardController?.hide() },
                    ),
                    trailingIcon = if (uiState.query.isNotEmpty()) {
                        {
                            IconButton(onClick = onClear) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.search_clear),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (uiState.isRefining) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp).testTag("search_refining"),
                )
            }
        }
        SearchStatus(uiState, onRebuildIndex)
        when {
            uiState.error != null -> SearchCenteredMessage(
                when (uiState.error) {
                    SearchUiError.QUERY_TOO_LONG -> stringResource(R.string.search_query_too_long)
                    SearchUiError.SEARCH_FAILED -> stringResource(R.string.search_failed)
                },
            )
            uiState.isIdle -> Spacer(Modifier.weight(1f))
            uiState.isEmptyResult -> SearchCenteredMessage(stringResource(R.string.search_empty))
            else -> SearchResultGrid(
                items = uiState.items,
                onImageClick = onImageClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SearchStatus(
    uiState: SearchUiState,
    onRebuildIndex: () -> Unit,
) {
    when {
        uiState.requiresRebuild -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.search_rebuild_required),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRebuildIndex) {
                Text(stringResource(R.string.search_rebuild_action))
            }
        }
        uiState.partialReasons.isNotEmpty() -> Text(
            text = partialReasonText(uiState.partialReasons),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun partialReasonText(reasons: Set<SearchPartialReason>): String = when {
    reasons.any { it in CAP_REASONS } -> stringResource(R.string.search_partial_cap)
    reasons.any { it in TIMEOUT_REASONS } -> stringResource(R.string.search_partial_timeout)
    else -> stringResource(R.string.search_partial_degraded)
}

@Composable
private fun SearchCenteredMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchResultGrid(
    items: List<SearchResultItem>,
    onImageClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 5 else 3
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag("search_result_grid"),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { it.image.localId }) { item ->
                Column(Modifier.fillMaxWidth()) {
                    GalleryImageTile(
                        image = item.image,
                        layout = GalleryTileLayout.Square,
                        onClick = onImageClick,
                    )
                    Text(
                        text = searchHitLabel(item.hit),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun searchHitLabel(hit: SearchResult): String {
    val field = stringResource(
        when (hit.field) {
            SearchField.FILE_NAME -> R.string.search_field_file_name
            SearchField.ALBUM -> R.string.search_field_album
            SearchField.CAPTION -> R.string.search_field_caption
            SearchField.TAG -> R.string.search_field_tag
            SearchField.CATEGORY -> R.string.search_field_category
            SearchField.SEARCH_TOKEN -> R.string.search_field_token
            SearchField.MEDIA_TEXT -> R.string.search_field_media
        },
    )
    val match = stringResource(
        when (hit.tier) {
            SearchTier.USER -> R.string.search_match_user
            SearchTier.EXACT_STRUCTURED -> R.string.search_match_exact
            SearchTier.FTS4 -> R.string.search_match_fts
            SearchTier.SUBSTRING -> R.string.search_match_substring
            SearchTier.TYPO -> R.string.search_match_typo
            SearchTier.PINYIN -> R.string.search_match_pinyin
        },
    )
    return stringResource(R.string.search_hit_format, field, match)
}

private val CAP_REASONS = setOf(
    SearchPartialReason.GRAM_TERM_CAP,
    SearchPartialReason.TYPO_TERM_CAP,
    SearchPartialReason.IMAGE_CANDIDATE_CAP,
)

private val TIMEOUT_REASONS = setOf(
    SearchPartialReason.STRUCTURED_TIMEOUT,
    SearchPartialReason.SUBSTRING_TIMEOUT,
    SearchPartialReason.FUZZY_TIMEOUT,
)

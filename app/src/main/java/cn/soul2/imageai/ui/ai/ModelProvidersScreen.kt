package cn.soul2.imageai.ui.ai

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.data.db.entity.ImagePartition
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import cn.soul2.imageai.data.db.entity.ProviderRouteEntity
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

object ModelProvidersDestination {
    const val route = "model_providers"
}

private data class ProviderListData(
    val routes: List<ProviderRouteEntity>,
    val providers: Map<String, ProviderProfileEntity>,
    val models: Map<String, ModelProfileEntity>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelProvidersScreen(
    repository: AiConfigurationRepository,
    onBack: () -> Unit,
    onAdd: (ImagePartition) -> Unit,
    onEdit: (ImagePartition, String) -> Unit,
) {
    val providersAndModels = repository.providers.combine(repository.models) { providers, models ->
        providers.associateBy(ProviderProfileEntity::providerId) to
            models.associateBy(ModelProfileEntity::providerId)
    }
    val mainData by repository.providerRoutes(ImagePartition.MAIN)
        .combine(providersAndModels) { routes, data -> ProviderListData(routes, data.first, data.second) }
        .collectAsStateWithLifecycle(ProviderListData(emptyList(), emptyMap(), emptyMap()))
    val privateData by repository.providerRoutes(ImagePartition.PRIVATE)
        .combine(providersAndModels) { routes, data -> ProviderListData(routes, data.first, data.second) }
        .collectAsStateWithLifecycle(ProviderListData(emptyList(), emptyMap(), emptyMap()))
    var partition by remember { mutableStateOf(ImagePartition.MAIN) }
    val data = if (partition == ImagePartition.MAIN) mainData else privateData
    val scope = rememberCoroutineScope()
    val rowHeightPx = with(LocalDensity.current) { 76.dp.toPx() }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(repository) {
        repository.removeLegacySharedPrivateRoutes()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型提供方") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onAdd(partition) }) {
                        Icon(Icons.Outlined.Add, "新增模型")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(selectedTabIndex = if (partition == ImagePartition.MAIN) 0 else 1) {
                Tab(
                    selected = partition == ImagePartition.MAIN,
                    onClick = { partition = ImagePartition.MAIN },
                    text = { Text("主分区") },
                )
                Tab(
                    selected = partition == ImagePartition.PRIVATE,
                    onClick = { partition = ImagePartition.PRIVATE },
                    text = { Text("隐私分区") },
                )
            }
            Text(
                text = "从上到下自动顺延；明确拒绝时立即停止。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (data.routes.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("尚未配置模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "点击右上角添加当前分区使用的模型。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(data.routes, key = { _, route -> route.providerId }) { index, route ->
                        val provider = data.providers[route.providerId]
                        val model = data.models[route.providerId]
                        ListItem(
                            modifier = Modifier.graphicsLayer {
                                translationY = if (draggedIndex == index) dragOffset else 0f
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.DragHandle,
                                    contentDescription = "长按拖动排序",
                                    modifier = Modifier
                                        .size(40.dp)
                                        .padding(8.dp)
                                        .pointerInput(route.providerId) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggedIndex = index
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggedIndex = -1
                                                    dragOffset = 0f
                                                },
                                                onDragEnd = {
                                                    val target = (index + (dragOffset / rowHeightPx).roundToInt())
                                                        .coerceIn(0, data.routes.lastIndex)
                                                    if (target != index) {
                                                        scope.launch {
                                                            repository.saveProviderRoutes(
                                                                partition,
                                                                data.routes.move(index, target),
                                                            )
                                                        }
                                                    }
                                                    draggedIndex = -1
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.y
                                                },
                                            )
                                        },
                                )
                            },
                            headlineContent = {
                                Text(provider?.displayName ?: "配置已不存在", maxLines = 1)
                            },
                            supportingContent = {
                                Text(
                                    listOfNotNull(model?.modelId, provider?.baseUrl?.host()).joinToString("  ·  "),
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = route.enabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                repository.saveProviderRoutes(
                                                    partition,
                                                    data.routes.map {
                                                        if (it.providerId == route.providerId) it.copy(enabled = enabled) else it
                                                    },
                                                )
                                            }
                                        },
                                    )
                                    IconButton(onClick = {
                                        scope.launch {
                                            val isolatedId = repository.isolateProviderForPartition(
                                                partition,
                                                route.providerId,
                                            )
                                            onEdit(partition, isolatedId)
                                        }
                                    }) {
                                        Icon(Icons.Outlined.Edit, "编辑")
                                    }
                                }
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

private fun String.host(): String = runCatching { URI(this).host }.getOrNull().orEmpty()

private fun List<ProviderRouteEntity>.move(from: Int, to: Int): List<ProviderRouteEntity> =
    toMutableList().also { items -> items.add(to, items.removeAt(from)) }

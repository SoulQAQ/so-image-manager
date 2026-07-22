package cn.soul2.imageai.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.data.db.entity.ImagePartition
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import cn.soul2.imageai.data.db.entity.ProviderRouteEntity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

object ModelProvidersDestination {
    const val route = "model_providers"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelProvidersScreen(
    repository: AiConfigurationRepository,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val mainRoutes by repository.providerRoutes(ImagePartition.MAIN)
        .combine(repository.providers) { routes, providers -> routes to providers }
        .collectAsStateWithLifecycle(emptyList<ProviderRouteEntity>() to emptyList())
    val privateRoutes by repository.providerRoutes(ImagePartition.PRIVATE)
        .combine(repository.providers) { routes, providers -> routes to providers }
        .collectAsStateWithLifecycle(emptyList<ProviderRouteEntity>() to emptyList())
    val scope = rememberCoroutineScope()
    val selected = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ImagePartition.MAIN) }
    val data = if (selected.value == ImagePartition.MAIN) mainRoutes else privateRoutes
    val profiles = data.second.associateBy(ProviderProfileEntity::providerId)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型提供方") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
                actions = { IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, "新增提供方") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selected.value == ImagePartition.MAIN, onClick = { selected.value = ImagePartition.MAIN }, label = { Text("主分区") })
                FilterChip(selected = selected.value == ImagePartition.PRIVATE, onClick = { selected.value = ImagePartition.PRIVATE }, label = { Text("隐私分区") })
            }
            Text("按顺序自动顺延。供应方明确拒绝时停止顺延。", modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(data.first.size, key = { data.first[it].providerId }) { index ->
                    val route = data.first[index]
                    val provider = profiles[route.providerId]
                    ListItem(
                        headlineContent = { Text(provider?.displayName ?: route.providerId) },
                        supportingContent = { Text(provider?.baseUrl ?: "配置已不存在") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = route.enabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch { repository.saveProviderRoutes(selected.value, data.first.map { if (it.providerId == route.providerId) it.copy(enabled = enabled) else it }) }
                                    },
                                )
                                IconButton(enabled = index > 0, onClick = {
                                    scope.launch { repository.saveProviderRoutes(selected.value, data.first.swap(index, index - 1)) }
                                }) { Icon(Icons.Outlined.ArrowUpward, "上移") }
                                IconButton(enabled = index < data.first.lastIndex, onClick = {
                                    scope.launch { repository.saveProviderRoutes(selected.value, data.first.swap(index, index + 1)) }
                                }) { Icon(Icons.Outlined.ArrowDownward, "下移") }
                                IconButton(onClick = { onEdit(route.providerId) }) { Icon(Icons.Outlined.Edit, "编辑") }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun List<ProviderRouteEntity>.swap(first: Int, second: Int): List<ProviderRouteEntity> =
    toMutableList().also { items ->
        val value = items[first]
        items[first] = items[second]
        items[second] = value
    }

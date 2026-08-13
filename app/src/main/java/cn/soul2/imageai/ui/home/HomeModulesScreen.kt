package cn.soul2.imageai.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.soul2.imageai.gallery.*
import cn.soul2.imageai.home.*
import kotlinx.coroutines.launch
import java.util.UUID

object HomeModulesDestination { const val route = "home_modules" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeModulesScreen(
    configuration: HomeConfigurationRepository,
    gallery: GalleryRepository,
    onBack: () -> Unit,
) {
    val stored by configuration.modules.collectAsState(initial = emptyList())
    val savedSearches by configuration.savedSearches.collectAsState(initial = emptyList())
    val themes by configuration.themes.collectAsState(initial = emptyList())
    val tags by gallery.observeCollections(GalleryCollectionType.TAG).collectAsState(initial = emptyList())
    val categories by gallery.observeCollections(GalleryCollectionType.CATEGORY).collectAsState(initial = emptyList())
    var modules by remember(stored) { mutableStateOf(stored) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun persist(next: List<HomeModule>) { modules = next; scope.launch { configuration.saveModules(next) } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("首页模块") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        }, actions = { IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, "添加模块") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Text("只有一个模块时使用连续瀑布流；多个模块使用分段布局。",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                ListItem(
                    headlineContent = { Text("主题") },
                    supportingContent = { Text("根据现有 AI 标签和分类生成动态主题，不会发起模型请求") },
                    trailingContent = {
                        TextButton(onClick = {
                            scope.launch {
                                configuration.replaceRecommendedThemes(
                                    (categories.take(6).map { "${it.displayName} · ${it.imageCount} 张" to "分类:${it.displayName}" } +
                                        tags.take(6).map { "${it.displayName} · ${it.imageCount} 张" to "标签:${it.displayName}" }),
                                )
                            }
                        }) { Text("刷新推荐") }
                    },
                )
            }
            items(themes, key = UserTheme::id) { theme ->
                ListItem(
                    headlineContent = { Text(theme.name) },
                    supportingContent = { Text(if (theme.aiRecommended) "AI 内容推荐 · ${theme.query}" else theme.query) },
                    leadingContent = { Icon(Icons.Outlined.CollectionsBookmark, null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                if (modules.none { it.type == HomeModuleType.THEME && it.sourceKey == theme.id }) {
                                    persist(modules + HomeModule(UUID.randomUUID().toString(), theme.name, HomeModuleType.THEME, theme.id))
                                }
                            }) { Icon(Icons.Outlined.Add, "添加到首页") }
                            IconButton(onClick = { scope.launch { configuration.deleteTheme(theme.id) } }) {
                                Icon(Icons.Outlined.DeleteOutline, "删除主题")
                            }
                        }
                    },
                )
            }
            itemsIndexed(modules, key = { _, item -> item.id }) { index, module ->
                var menu by remember(module.id) { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(module.title) },
                    supportingContent = {
                        Text("${module.type.label()} · ${module.sort.label()} · ${module.layout.label()} · ${module.previewCount} 张")
                    },
                    leadingContent = { Icon(module.type.icon(), null) },
                    trailingContent = {
                        Row {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Tune, "配置模块") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    HomeModuleSort.entries.forEach { sort ->
                                        DropdownMenuItem(
                                            text = { Text("排序：${sort.label()}") },
                                            onClick = { menu = false; persist(modules.toMutableList().apply { this[index] = module.copy(sort = sort) }) },
                                            trailingIcon = if (module.sort == sort) ({ Icon(Icons.Outlined.Check, null) }) else null,
                                        )
                                    }
                                    HomeModuleLayout.entries.forEach { layout ->
                                        DropdownMenuItem(
                                            text = { Text("布局：${layout.label()}") },
                                            onClick = { menu = false; persist(modules.toMutableList().apply { this[index] = module.copy(layout = layout) }) },
                                            trailingIcon = if (module.layout == layout) ({ Icon(Icons.Outlined.Check, null) }) else null,
                                        )
                                    }
                                    listOf(8, 12, 20, 32).forEach { count ->
                                        DropdownMenuItem(
                                            text = { Text("预览：$count 张") },
                                            onClick = { menu = false; persist(modules.toMutableList().apply { this[index] = module.copy(previewCount = count) }) },
                                            trailingIcon = if (module.previewCount == count) ({ Icon(Icons.Outlined.Check, null) }) else null,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { if (index > 0) persist(modules.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0) {
                                Icon(Icons.Outlined.ArrowUpward, "上移")
                            }
                            IconButton(onClick = { if (index < modules.lastIndex) persist(modules.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index < modules.lastIndex) {
                                Icon(Icons.Outlined.ArrowDownward, "下移")
                            }
                            IconButton(onClick = { persist(modules.filterNot { it.id == module.id }) }) {
                                Icon(Icons.Outlined.DeleteOutline, "删除")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (showAdd) AddModuleDialog(gallery, savedSearches, themes, onDismiss = { showAdd = false }) { title, type, key ->
        persist(modules + HomeModule(UUID.randomUUID().toString(), title, type, key))
        showAdd = false
    }
}
private fun HomeModuleSort.label() = when (this) {
    HomeModuleSort.NEWEST -> "时间"
    HomeModuleSort.NAME -> "名称"
    HomeModuleSort.SIZE -> "大小"
}
private fun HomeModuleLayout.label() = when (this) {
    HomeModuleLayout.STRIP -> "横向"
    HomeModuleLayout.GRID -> "网格"
}

@Composable
private fun AddModuleDialog(
    gallery: GalleryRepository,
    savedSearches: List<SavedSearch>,
    themes: List<UserTheme>,
    onDismiss: () -> Unit,
    onAdd: (String, HomeModuleType, String) -> Unit,
) {
    val albums by gallery.observeCollections(GalleryCollectionType.ALBUM).collectAsState(initial = emptyList())
    val tags by gallery.observeCollections(GalleryCollectionType.TAG).collectAsState(initial = emptyList())
    val categories by gallery.observeCollections(GalleryCollectionType.CATEGORY).collectAsState(initial = emptyList())
    val options = buildList {
        add(Triple("最近图片", HomeModuleType.RECENT, ""))
        albums.forEach { add(Triple(it.displayName, HomeModuleType.ALBUM, it.key)) }
        tags.forEach { add(Triple(it.displayName, HomeModuleType.TAG, it.key)) }
        categories.forEach { add(Triple(it.displayName, HomeModuleType.CATEGORY, it.key)) }
        savedSearches.forEach { add(Triple(it.name, HomeModuleType.SAVED_SEARCH, it.id)) }
        themes.forEach { add(Triple(it.name, HomeModuleType.THEME, it.id)) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加首页模块") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(options.size) { index -> val option = options[index]
                ListItem(modifier = Modifier.clickable { onAdd(option.first, option.second, option.third) },
                    headlineContent = { Text(option.first) }, supportingContent = { Text(option.second.label()) })
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun HomeModuleType.label() = when (this) {
    HomeModuleType.RECENT -> "最近图片"
    HomeModuleType.ALBUM -> "系统相册"
    HomeModuleType.TAG -> "标签"
    HomeModuleType.CATEGORY -> "分类"
    HomeModuleType.SAVED_SEARCH -> "保存的搜索"
    HomeModuleType.THEME -> "动态主题"
}
private fun HomeModuleType.icon() = when (this) {
    HomeModuleType.RECENT -> Icons.Outlined.History
    HomeModuleType.ALBUM -> Icons.Outlined.PhotoAlbum
    HomeModuleType.TAG -> Icons.Outlined.Label
    HomeModuleType.CATEGORY -> Icons.Outlined.Category
    HomeModuleType.SAVED_SEARCH -> Icons.Outlined.Bookmark
    HomeModuleType.THEME -> Icons.Outlined.CollectionsBookmark
}

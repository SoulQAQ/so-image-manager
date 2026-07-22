package cn.soul2.imageai.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AnalysisSettingsDestination {
    const val route = "analysis_settings"
}

private data class AnalysisSettingsDraft(
    val globalConcurrency: String = "2",
    val globalRequestsPerMinute: String = "30",
    val globalRequestsPerDay: String = "1000",
    val dailyImageLimit: String = "0",
    val onlyShowAnalyzed: Boolean = false,
    val prompt: String = AiSettingsForm.DEFAULT_PROMPT,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisSettingsScreen(
    repository: AiConfigurationRepository,
    onBack: () -> Unit,
) {
    val runtime by repository.runtimeSetting.collectAsStateWithLifecycle(null)
    var draft by remember { mutableStateOf(AnalysisSettingsDraft()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(runtime) {
        if (!loaded) {
            runtime?.let {
                draft = AnalysisSettingsDraft(
                    globalConcurrency = it.globalMaxConcurrency.toString(),
                    globalRequestsPerMinute = it.globalRequestsPerMinute.toString(),
                    globalRequestsPerDay = it.globalRequestsPerDay.toString(),
                    dailyImageLimit = it.dailyImageLimit.toString(),
                    onlyShowAnalyzed = it.onlyShowAnalyzed,
                    prompt = it.promptText,
                )
            }
            loaded = true
        }
    }
    fun save() {
        if (saving) return
        val concurrency = draft.globalConcurrency.toIntOrNull()
        val perMinute = draft.globalRequestsPerMinute.toIntOrNull()
        val perDay = draft.globalRequestsPerDay.toIntOrNull()
        val dailyImages = draft.dailyImageLimit.toIntOrNull()
        if (concurrency == null || perMinute == null || perDay == null || dailyImages == null) {
            scope.launch { snackbar.showSnackbar("请检查数值设置") }
            return
        }
        saving = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.saveRuntimeSetting(
                        AiRuntimeSettingEntity(
                            defaultModelProfileId = runtime?.defaultModelProfileId,
                            globalMaxConcurrency = concurrency,
                            globalRequestsPerMinute = perMinute,
                            globalRequestsPerDay = perDay,
                            dailyImageLimit = dailyImages,
                            onlyShowAnalyzed = draft.onlyShowAnalyzed,
                            automaticFailoverEnabled = true,
                            promptText = draft.prompt.trim(),
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            saving = false
            snackbar.showSnackbar(if (result.isSuccess) "设置已保存" else "保存失败，请检查输入")
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("常规设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = ::save, enabled = !saving) {
                        Icon(Icons.Outlined.Save, "保存")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { SettingsSectionTitle("分析调度") }
            item {
                NumericSettingRow("全局最大并发", "所有分区共享", draft.globalConcurrency) {
                    draft = draft.copy(globalConcurrency = it)
                }
            }
            item {
                NumericSettingRow("每分钟请求数", "0 表示不限制", draft.globalRequestsPerMinute) {
                    draft = draft.copy(globalRequestsPerMinute = it)
                }
            }
            item {
                NumericSettingRow("每日请求数", "0 表示不限制", draft.globalRequestsPerDay) {
                    draft = draft.copy(globalRequestsPerDay = it)
                }
            }
            item {
                NumericSettingRow("每日图片分析上限", "0 表示不限制", draft.dailyImageLimit) {
                    draft = draft.copy(dailyImageLimit = it)
                }
            }
            item { SettingsSectionTitle("图库显示") }
            item {
                ListItem(
                    headlineContent = { Text("默认只显示已分析图片") },
                    supportingContent = { Text("未处理图片仍可从图库二级入口查看") },
                    trailingContent = {
                        Switch(
                            checked = draft.onlyShowAnalyzed,
                            onCheckedChange = { draft = draft.copy(onlyShowAnalyzed = it) },
                        )
                    },
                )
            }
            item { SettingsSectionTitle("分析规则") }
            item {
                OutlinedTextField(
                    value = draft.prompt,
                    onValueChange = { draft = draft.copy(prompt = it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    label = { Text("图片分析 Prompt") },
                    minLines = 4,
                )
            }
        }
    }
}

@Composable
private fun NumericSettingRow(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(0.32f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
    )
}

@Composable
internal fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

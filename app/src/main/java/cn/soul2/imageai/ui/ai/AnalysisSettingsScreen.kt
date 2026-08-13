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
    val dailyTokenLimit: String = "0",
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val batteryNotLow: Boolean = true,
    val startHour: String = "0",
    val endHour: String = "0",
    val retryLimit: String = "4",
    val circuitThreshold: String = "5",
    val cooldownMinutes: String = "30",
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
                    dailyTokenLimit = it.dailyTokenLimit.toString(),
                    wifiOnly = it.wifiOnly,
                    chargingOnly = it.chargingOnly,
                    batteryNotLow = it.batteryNotLow,
                    startHour = (it.executionStartMinute / 60).toString(),
                    endHour = (it.executionEndMinute / 60).toString(),
                    retryLimit = it.retryLimit.toString(),
                    circuitThreshold = it.circuitBreakerThreshold.toString(),
                    cooldownMinutes = it.circuitBreakerCooldownMinutes.toString(),
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
        val dailyTokens = draft.dailyTokenLimit.toLongOrNull()
        val startHour = draft.startHour.toIntOrNull()
        val endHour = draft.endHour.toIntOrNull()
        val retryLimit = draft.retryLimit.toIntOrNull()
        val threshold = draft.circuitThreshold.toIntOrNull()
        val cooldown = draft.cooldownMinutes.toIntOrNull()
        if (listOf(concurrency, perMinute, perDay, dailyImages, startHour, endHour, retryLimit, threshold, cooldown).any { it == null } || dailyTokens == null) {
            scope.launch { snackbar.showSnackbar("请检查数值设置") }
            return
        }
        val validated = AiRuntimeSettingEntity(
            defaultModelProfileId = runtime?.defaultModelProfileId,
            globalMaxConcurrency = checkNotNull(concurrency),
            globalRequestsPerMinute = checkNotNull(perMinute),
            globalRequestsPerDay = checkNotNull(perDay),
            dailyImageLimit = checkNotNull(dailyImages),
            dailyTokenLimit = dailyTokens,
            wifiOnly = draft.wifiOnly,
            chargingOnly = draft.chargingOnly,
            batteryNotLow = draft.batteryNotLow,
            executionStartMinute = checkNotNull(startHour) * 60,
            executionEndMinute = checkNotNull(endHour) * 60,
            retryLimit = checkNotNull(retryLimit),
            circuitBreakerThreshold = checkNotNull(threshold),
            circuitBreakerCooldownMinutes = checkNotNull(cooldown),
            onlyShowAnalyzed = draft.onlyShowAnalyzed,
            automaticFailoverEnabled = true,
            promptText = draft.prompt.trim(),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        saving = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.saveRuntimeSetting(validated)
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
            item {
                NumericSettingRow("每日 Token 上限", "按供应方实际返回用量统计，0 表示不限制", draft.dailyTokenLimit) {
                    draft = draft.copy(dailyTokenLimit = it)
                }
            }
            item { SettingsSectionTitle("设备条件") }
            item { ToggleSettingRow("仅 Wi-Fi", "等待不计费网络后执行", draft.wifiOnly) { draft = draft.copy(wifiOnly = it) } }
            item { ToggleSettingRow("仅充电时", "接入电源后执行批量分析", draft.chargingOnly) { draft = draft.copy(chargingOnly = it) } }
            item { ToggleSettingRow("电量充足", "低电量时暂停后台分析", draft.batteryNotLow) { draft = draft.copy(batteryNotLow = it) } }
            item { SettingsSectionTitle("执行时段") }
            item { NumericSettingRow("开始小时", "0-23；与结束相同表示全天", draft.startHour) { draft = draft.copy(startHour = it) } }
            item { NumericSettingRow("结束小时", "跨午夜时会自动识别", draft.endHour) { draft = draft.copy(endHour = it) } }
            item { SettingsSectionTitle("故障恢复") }
            item { NumericSettingRow("单图重试次数", "网络、限流和服务临时故障", draft.retryLimit) { draft = draft.copy(retryLimit = it) } }
            item { NumericSettingRow("熔断阈值", "连续失败达到此次数后冷却", draft.circuitThreshold) { draft = draft.copy(circuitThreshold = it) } }
            item { NumericSettingRow("冷却时间（分钟）", "冷却结束后自动恢复", draft.cooldownMinutes) { draft = draft.copy(cooldownMinutes = it) } }
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
private fun ToggleSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
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

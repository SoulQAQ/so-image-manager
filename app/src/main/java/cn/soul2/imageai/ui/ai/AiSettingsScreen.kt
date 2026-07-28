package cn.soul2.imageai.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.soul2.imageai.R
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ImagePartition

object AiSettingsDestination {
    const val providerIdArgument = "providerId"
    const val partitionArgument = "partition"
    const val route = "ai_settings?providerId={providerId}&partition={partition}"
    const val baseRoute = "ai_settings"

    fun createRoute(providerId: String, partition: ImagePartition): String =
        "ai_settings?providerId=$providerId&partition=${partition.name}"

    fun createNewRoute(partition: ImagePartition): String =
        "ai_settings?providerId=new&partition=${partition.name}"
}

@Composable
fun AiSettingsScreen(
    repository: AiConfigurationRepository,
    credentialStore: AiCredentialStore,
    providerId: String? = null,
    partition: ImagePartition = ImagePartition.MAIN,
    onBack: () -> Unit,
) {
    val viewModel: AiSettingsViewModel = viewModel(
        factory = AiSettingsViewModel.factory(repository, credentialStore, providerId, partition),
    )
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val savedMessage = stringResource(R.string.ai_settings_saved)
    LaunchedEffect(state.saveGeneration) {
        if (state.saveGeneration > 0) {
            keyboardController?.hide()
            snackbar.showSnackbar(savedMessage)
        }
    }
    LaunchedEffect(state.deleteGeneration) {
        if (state.deleteGeneration > 0) onBack()
    }
    AiSettingsContent(
        state = state,
        snackbarHostState = snackbar,
        onFormChange = viewModel::updateForm,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onBack = onBack,
        isNew = providerId == null || providerId == "new",
        partitionLabel = if (partition == ImagePartition.MAIN) "主分区" else "隐私分区",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSettingsContent(
    state: AiSettingsUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onFormChange: (AiSettingsForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit = {},
    onBack: () -> Unit,
    isNew: Boolean = false,
    partitionLabel: String? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("screen_ai_settings"),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(if (isNew) "新增模型" else "编辑模型") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.image_detail_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onSave,
                            enabled = !state.loading && !state.saving,
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = "保存")
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
        ) { padding ->
            if (state.loading) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                AiSettingsFormContent(
                    state = state,
                    onFormChange = onFormChange,
                    onSave = onSave,
                    onDelete = { confirmDelete = true },
                    partitionLabel = partitionLabel,
                    modifier = Modifier.padding(padding),
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 64.dp)
                .testTag("ai_settings_snackbar"),
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除此模型？") },
            text = { Text("该模型会从当前分区的顺延列表中移除，操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AiSettingsFormContent(
    state: AiSettingsUiState,
    onFormChange: (AiSettingsForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    partitionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    var advancedExpanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.error?.let { error ->
            item {
                Text(
                    text = stringResource(errorMessage(error)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag("ai_settings_error"),
                )
            }
        }
        partitionLabel?.let { label ->
            item {
                Text(
                    text = "用于$label",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item { SectionTitle(R.string.ai_settings_section_provider) }
        item {
            TextField(
                value = form.providerName,
                label = R.string.ai_settings_provider_name,
                onValueChange = { onFormChange(form.copy(providerName = it)) },
            )
        }
        item {
            TextField(
                value = form.baseUrl,
                label = R.string.ai_settings_base_url,
                onValueChange = { onFormChange(form.copy(baseUrl = it)) },
            )
        }
        item {
            ChoiceRow(
                options = listOf(
                    ProviderAuthMode.BEARER to R.string.ai_settings_auth_bearer,
                    ProviderAuthMode.API_KEY_HEADER to R.string.ai_settings_auth_header,
                    ProviderAuthMode.NONE to R.string.ai_settings_auth_none,
                ),
                selected = form.authMode,
                onSelected = { onFormChange(form.copy(authMode = it)) },
            )
        }
        if (form.authMode != ProviderAuthMode.NONE) {
            item {
                OutlinedTextField(
                    value = form.apiKey,
                    onValueChange = { onFormChange(form.copy(apiKey = it)) },
                    modifier = Modifier.fillMaxWidth().testTag("ai_api_key"),
                    label = { Text(stringResource(R.string.ai_settings_api_key)) },
                    placeholder = if (state.credentialConfigured) {
                        { Text(stringResource(R.string.ai_settings_api_key_saved)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }
        if (form.authMode == ProviderAuthMode.API_KEY_HEADER) {
            item {
                TextField(
                    value = form.apiKeyHeader,
                    label = R.string.ai_settings_api_key_header,
                    onValueChange = { onFormChange(form.copy(apiKeyHeader = it)) },
                )
            }
        }
        item {
            ToggleRow(
                label = R.string.ai_settings_allow_http,
                checked = form.cleartextApproved,
                onCheckedChange = { onFormChange(form.copy(cleartextApproved = it)) },
            )
        }
        item { SectionTitle(R.string.ai_settings_section_model) }
        item {
            TextField(
                value = form.modelId,
                label = R.string.ai_settings_model_id,
                onValueChange = { onFormChange(form.copy(modelId = it)) },
            )
        }
        item {
            ChoiceRow(
                options = listOf(
                    ModelProtocolType.OPENAI_RESPONSES to R.string.ai_settings_protocol_responses,
                    ModelProtocolType.CUSTOM_JSON to R.string.ai_settings_protocol_custom,
                ),
                selected = form.protocolType,
                onSelected = { onFormChange(form.copy(protocolType = it)) },
            )
        }
        if (form.protocolType == ModelProtocolType.CUSTOM_JSON) {
            item {
                TextField(
                    value = form.customProtocolName,
                    label = R.string.ai_settings_protocol_name,
                    onValueChange = { onFormChange(form.copy(customProtocolName = it)) },
                )
            }
            item {
                TextField(
                    value = form.customProtocolJson,
                    label = R.string.ai_settings_protocol_json,
                    onValueChange = { onFormChange(form.copy(customProtocolJson = it)) },
                    singleLine = false,
                    minLines = 8,
                )
            }
        }
        item { SectionTitle(R.string.ai_settings_section_generation) }
        item { NumericField(form.maxOutputTokens, R.string.ai_settings_max_output_tokens) { onFormChange(form.copy(maxOutputTokens = it)) } }
        item { NumericField(form.temperature, R.string.ai_settings_temperature, decimal = true) { onFormChange(form.copy(temperature = it)) } }
        item { NumericField(form.maxImageEdge, R.string.ai_settings_max_image_edge) { onFormChange(form.copy(maxImageEdge = it)) } }
        item { NumericField(form.maxImageBytes, R.string.ai_settings_max_image_bytes) { onFormChange(form.copy(maxImageBytes = it)) } }
        item { SectionTitle(R.string.ai_settings_section_quota) }
        item { QuotaTitle(R.string.ai_settings_quota_provider) }
        item { NumericField(form.providerConcurrency, R.string.ai_settings_concurrency) { onFormChange(form.copy(providerConcurrency = it)) } }
        item { NumericField(form.providerRequestsPerMinute, R.string.ai_settings_rpm) { onFormChange(form.copy(providerRequestsPerMinute = it)) } }
        item { NumericField(form.providerRequestsPerDay, R.string.ai_settings_daily) { onFormChange(form.copy(providerRequestsPerDay = it)) } }
        item {
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(if (advancedExpanded) "收起高级设置" else "展开高级设置")
            }
        }
        if (advancedExpanded) {
            item { SectionTitle(R.string.ai_settings_section_advanced) }
            item { NumericField(form.connectTimeoutMillis, R.string.ai_settings_connect_timeout) { onFormChange(form.copy(connectTimeoutMillis = it)) } }
            item { NumericField(form.readTimeoutMillis, R.string.ai_settings_read_timeout) { onFormChange(form.copy(readTimeoutMillis = it)) } }
            item { NumericField(form.writeTimeoutMillis, R.string.ai_settings_write_timeout) { onFormChange(form.copy(writeTimeoutMillis = it)) } }
            item {
            TextField(
                value = form.headersJson,
                label = R.string.ai_settings_headers_json,
                onValueChange = { onFormChange(form.copy(headersJson = it)) },
                singleLine = false,
            )
            }
            item {
            TextField(
                value = form.redirectOriginsJson,
                label = R.string.ai_settings_redirect_json,
                onValueChange = { onFormChange(form.copy(redirectOriginsJson = it)) },
                singleLine = false,
            )
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onSave,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("ai_settings_save"),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.ai_settings_save))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (state.existingProvider) {
            item {
                TextButton(
                    onClick = onDelete,
                    enabled = !state.deleting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Text("删除此模型", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(label: Int) {
    Text(
        text = stringResource(label),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun QuotaTitle(label: Int) {
    Text(
        text = stringResource(label),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TextField(
    value: String,
    label: Int,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumericField(
    value: String,
    label: Int,
    decimal: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, Int>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@androidx.annotation.StringRes
private fun errorMessage(error: AiSettingsError): Int = when (error) {
    AiSettingsError.INVALID_FIELDS -> R.string.ai_settings_error_invalid
    AiSettingsError.CREDENTIAL_REQUIRED -> R.string.ai_settings_error_credential_required
    AiSettingsError.CREDENTIAL_STORAGE_FAILED -> R.string.ai_settings_error_credential_storage
    AiSettingsError.SAVE_FAILED -> R.string.ai_settings_error_save
}

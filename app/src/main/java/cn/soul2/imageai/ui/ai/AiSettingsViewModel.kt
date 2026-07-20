package cn.soul2.imageai.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.ai.credential.AiCredentialStore
import cn.soul2.imageai.ai.credential.CredentialReadResult
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AiSettingsForm(
    val providerName: String = "OpenAI",
    val baseUrl: String = "https://api.openai.com/v1",
    val authMode: ProviderAuthMode = ProviderAuthMode.BEARER,
    val apiKey: String = "",
    val apiKeyHeader: String = "X-API-Key",
    val headersJson: String = "{}",
    val redirectOriginsJson: String = "[]",
    val cleartextApproved: Boolean = false,
    val connectTimeoutMillis: String = "10000",
    val readTimeoutMillis: String = "60000",
    val writeTimeoutMillis: String = "60000",
    val providerConcurrency: String = "2",
    val providerRequestsPerMinute: String = "20",
    val providerRequestsPerDay: String = "500",
    val modelName: String = "视觉模型",
    val modelId: String = "",
    val protocolType: ModelProtocolType = ModelProtocolType.OPENAI_RESPONSES,
    val maxOutputTokens: String = "2048",
    val temperature: String = "0.2",
    val maxImageEdge: String = "1600",
    val maxImageBytes: String = "1500000",
    val modelConcurrency: String = "1",
    val modelRequestsPerMinute: String = "10",
    val modelRequestsPerDay: String = "200",
    val globalConcurrency: String = "2",
    val globalRequestsPerMinute: String = "30",
    val globalRequestsPerDay: String = "1000",
    val prompt: String = DEFAULT_PROMPT,
    val customProtocolName: String = "自定义 JSON 协议",
    val customProtocolJson: String = "",
) {
    companion object {
        const val DEFAULT_PROMPT =
            "请用简体中文描述图片，并生成适合搜索的标签、分类和关键词。只输出要求的结构化数据。"
    }
}

enum class AiSettingsError {
    INVALID_FIELDS,
    CREDENTIAL_REQUIRED,
    CREDENTIAL_STORAGE_FAILED,
    SAVE_FAILED,
}

data class AiSettingsUiState(
    val form: AiSettingsForm = AiSettingsForm(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val credentialConfigured: Boolean = false,
    val error: AiSettingsError? = null,
    val saveGeneration: Int = 0,
)

class AiSettingsViewModel(
    private val repository: AiConfigurationRepository,
    private val credentialStore: AiCredentialStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = mutableState.asStateFlow()
    private var dirty = false
    private var providerCreatedAt = 0L
    private var modelCreatedAt = 0L
    private var protocolCreatedAt = 0L

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { loadCurrentConfiguration() }
    }

    fun updateForm(form: AiSettingsForm) {
        dirty = true
        mutableState.value = mutableState.value.copy(form = form, error = null)
    }

    fun save() {
        if (mutableState.value.saving) return
        val form = mutableState.value.form
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = withContext(ioDispatcher) { saveForm(form) }
            mutableState.value = when (result) {
                null -> mutableState.value.copy(
                    form = form.copy(apiKey = ""),
                    saving = false,
                    credentialConfigured = form.authMode != ProviderAuthMode.NONE,
                    error = null,
                    saveGeneration = mutableState.value.saveGeneration + 1,
                )
                else -> mutableState.value.copy(saving = false, error = result)
            }
        }
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private suspend fun loadCurrentConfiguration() {
        val snapshot = try {
            withContext(ioDispatcher) {
                val runtime = repository.getRuntimeSetting()
                val model = runtime?.defaultModelProfileId?.let { repository.getModel(it) }
                val provider = model?.let { repository.getProvider(it.providerId) }
                val protocol = model?.protocolDefinitionId?.let { repository.getProtocol(it) }
                ConfigurationSnapshot(runtime, provider, model, protocol)
            }
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = AiSettingsError.SAVE_FAILED,
            )
            return
        }
        if (dirty) return
        providerCreatedAt = snapshot.provider?.createdAtEpochMillis ?: 0L
        modelCreatedAt = snapshot.model?.createdAtEpochMillis ?: 0L
        protocolCreatedAt = snapshot.protocol?.createdAtEpochMillis ?: 0L
        val credentialConfigured = credentialPresent(snapshot.provider?.credentialId)
        mutableState.value = AiSettingsUiState(
            form = snapshot.toForm(),
            loading = false,
            credentialConfigured = credentialConfigured,
        )
    }

    private fun credentialPresent(credentialId: String?): Boolean {
        if (credentialId == null) return false
        return when (val result = credentialStore.read(credentialId)) {
            is CredentialReadResult.Available -> {
                result.secret.fill('\u0000')
                true
            }
            CredentialReadResult.Missing,
            is CredentialReadResult.Unavailable,
            -> false
        }
    }

    private suspend fun saveForm(form: AiSettingsForm): AiSettingsError? {
        val now = nowEpochMillis()
        val credentialId = if (form.authMode == ProviderAuthMode.NONE) null else CREDENTIAL_ID
        val provider = try {
            ProviderProfileEntity(
                providerId = PROVIDER_ID,
                displayName = form.providerName.trim(),
                baseUrl = form.baseUrl.trim(),
                authMode = form.authMode,
                authHeaderName = if (form.authMode == ProviderAuthMode.API_KEY_HEADER) {
                    form.apiKeyHeader.trim()
                } else {
                    null
                },
                authPrefix = null,
                credentialId = credentialId,
                headersJson = form.headersJson.trim(),
                allowedRedirectOriginsJson = form.redirectOriginsJson.trim(),
                cleartextApproved = form.cleartextApproved,
                connectTimeoutMillis = form.connectTimeoutMillis.requiredInt(),
                readTimeoutMillis = form.readTimeoutMillis.requiredInt(),
                writeTimeoutMillis = form.writeTimeoutMillis.requiredInt(),
                maxConcurrency = form.providerConcurrency.requiredInt(),
                requestsPerMinute = form.providerRequestsPerMinute.requiredInt(),
                requestsPerDay = form.providerRequestsPerDay.requiredInt(),
                enabled = true,
                createdAtEpochMillis = providerCreatedAt.takeIf { it > 0L } ?: now,
                updatedAtEpochMillis = now,
            )
        } catch (_: IllegalArgumentException) {
            return AiSettingsError.INVALID_FIELDS
        }
        val protocolId = if (form.protocolType == ModelProtocolType.CUSTOM_JSON) PROTOCOL_ID else null
        val model = try {
            ModelProfileEntity(
                modelProfileId = MODEL_ID,
                providerId = PROVIDER_ID,
                displayName = form.modelName.trim(),
                modelId = form.modelId.trim(),
                protocolType = form.protocolType,
                protocolDefinitionId = protocolId,
                supportsVision = true,
                maxOutputTokens = form.maxOutputTokens.optionalInt(),
                temperature = form.temperature.optionalDouble(),
                maxImageEdge = form.maxImageEdge.requiredInt(),
                maxImageBytes = form.maxImageBytes.requiredInt(),
                maxConcurrency = form.modelConcurrency.requiredInt(),
                requestsPerMinute = form.modelRequestsPerMinute.requiredInt(),
                requestsPerDay = form.modelRequestsPerDay.requiredInt(),
                enabled = true,
                createdAtEpochMillis = modelCreatedAt.takeIf { it > 0L } ?: now,
                updatedAtEpochMillis = now,
            )
        } catch (_: IllegalArgumentException) {
            return AiSettingsError.INVALID_FIELDS
        }
        val runtime = try {
            AiRuntimeSettingEntity(
                defaultModelProfileId = MODEL_ID,
                globalMaxConcurrency = form.globalConcurrency.requiredInt(),
                globalRequestsPerMinute = form.globalRequestsPerMinute.requiredInt(),
                globalRequestsPerDay = form.globalRequestsPerDay.requiredInt(),
                promptText = form.prompt.trim(),
                updatedAtEpochMillis = now,
            )
        } catch (_: IllegalArgumentException) {
            return AiSettingsError.INVALID_FIELDS
        }
        val protocol = if (protocolId != null) {
            ProtocolDefinitionEntity(
                protocolDefinitionId = protocolId,
                displayName = form.customProtocolName.trim(),
                definitionJson = form.customProtocolJson.trim(),
                enabled = true,
                createdAtEpochMillis = protocolCreatedAt.takeIf { it > 0L } ?: now,
                updatedAtEpochMillis = now,
            )
        } else {
            null
        }
        try {
            repository.validateBundle(provider, model, runtime, protocol)
        } catch (_: IllegalArgumentException) {
            return AiSettingsError.INVALID_FIELDS
        }
        if (form.authMode != ProviderAuthMode.NONE) {
            if (form.apiKey.isBlank() && !mutableState.value.credentialConfigured) {
                return AiSettingsError.CREDENTIAL_REQUIRED
            }
            if (form.apiKey.isNotBlank()) {
                val secret = form.apiKey.toCharArray()
                try {
                    credentialStore.put(CREDENTIAL_ID, secret)
                } catch (_: Exception) {
                    return AiSettingsError.CREDENTIAL_STORAGE_FAILED
                } finally {
                    secret.fill('\u0000')
                }
            }
        }
        return try {
            repository.saveBundle(provider, model, runtime, protocol)
            if (form.authMode == ProviderAuthMode.NONE) credentialStore.delete(CREDENTIAL_ID)
            providerCreatedAt = provider.createdAtEpochMillis
            modelCreatedAt = model.createdAtEpochMillis
            protocolCreatedAt = protocol?.createdAtEpochMillis ?: 0L
            null
        } catch (_: Exception) {
            AiSettingsError.SAVE_FAILED
        }
    }

    private fun ConfigurationSnapshot.toForm(): AiSettingsForm {
        val defaults = AiSettingsForm()
        return defaults.copy(
            providerName = provider?.displayName ?: defaults.providerName,
            baseUrl = provider?.baseUrl ?: defaults.baseUrl,
            authMode = provider?.authMode ?: defaults.authMode,
            apiKeyHeader = provider?.authHeaderName ?: defaults.apiKeyHeader,
            headersJson = provider?.headersJson ?: defaults.headersJson,
            redirectOriginsJson = provider?.allowedRedirectOriginsJson ?: defaults.redirectOriginsJson,
            cleartextApproved = provider?.cleartextApproved ?: defaults.cleartextApproved,
            connectTimeoutMillis = provider?.connectTimeoutMillis?.toString() ?: defaults.connectTimeoutMillis,
            readTimeoutMillis = provider?.readTimeoutMillis?.toString() ?: defaults.readTimeoutMillis,
            writeTimeoutMillis = provider?.writeTimeoutMillis?.toString() ?: defaults.writeTimeoutMillis,
            providerConcurrency = provider?.maxConcurrency?.toString() ?: defaults.providerConcurrency,
            providerRequestsPerMinute = provider?.requestsPerMinute?.toString()
                ?: defaults.providerRequestsPerMinute,
            providerRequestsPerDay = provider?.requestsPerDay?.toString()
                ?: defaults.providerRequestsPerDay,
            modelName = model?.displayName ?: defaults.modelName,
            modelId = model?.modelId ?: defaults.modelId,
            protocolType = model?.protocolType ?: defaults.protocolType,
            maxOutputTokens = model?.maxOutputTokens?.toString().orEmpty(),
            temperature = model?.temperature?.toString().orEmpty(),
            maxImageEdge = model?.maxImageEdge?.toString() ?: defaults.maxImageEdge,
            maxImageBytes = model?.maxImageBytes?.toString() ?: defaults.maxImageBytes,
            modelConcurrency = model?.maxConcurrency?.toString() ?: defaults.modelConcurrency,
            modelRequestsPerMinute = model?.requestsPerMinute?.toString()
                ?: defaults.modelRequestsPerMinute,
            modelRequestsPerDay = model?.requestsPerDay?.toString() ?: defaults.modelRequestsPerDay,
            globalConcurrency = runtime?.globalMaxConcurrency?.toString() ?: defaults.globalConcurrency,
            globalRequestsPerMinute = runtime?.globalRequestsPerMinute?.toString()
                ?: defaults.globalRequestsPerMinute,
            globalRequestsPerDay = runtime?.globalRequestsPerDay?.toString()
                ?: defaults.globalRequestsPerDay,
            prompt = runtime?.promptText ?: defaults.prompt,
            customProtocolName = protocol?.displayName ?: defaults.customProtocolName,
            customProtocolJson = protocol?.definitionJson.orEmpty(),
        )
    }

    private fun String.requiredInt(): Int = trim().toIntOrNull()
        ?: throw IllegalArgumentException("Expected integer")

    private fun String.optionalInt(): Int? = trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        ?: if (isBlank()) null else throw IllegalArgumentException("Expected optional integer")

    private fun String.optionalDouble(): Double? = trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
        ?: if (isBlank()) null else throw IllegalArgumentException("Expected optional number")

    private data class ConfigurationSnapshot(
        val runtime: AiRuntimeSettingEntity?,
        val provider: ProviderProfileEntity?,
        val model: ModelProfileEntity?,
        val protocol: ProtocolDefinitionEntity?,
    )

    companion object {
        const val PROVIDER_ID = "provider.active"
        const val MODEL_ID = "model.active"
        const val PROTOCOL_ID = "protocol.active"
        const val CREDENTIAL_ID = "credential.active"

        fun factory(
            repository: AiConfigurationRepository,
            credentialStore: AiCredentialStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { AiSettingsViewModel(repository, credentialStore) }
        }
    }
}

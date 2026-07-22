package cn.soul2.imageai.ai.config

import androidx.room.withTransaction
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.dao.AiConfigurationDao
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import cn.soul2.imageai.ai.protocol.CustomJsonProtocolDefinition
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

class AiConfigurationRepository(
    private val database: AppDatabase,
) {
    private val dao: AiConfigurationDao = database.aiConfigurationDao()

    val providers = dao.observeProviders()
    val models = dao.observeModels()
    val protocols = dao.observeProtocols()
    val runtimeSetting = dao.observeRuntimeSetting()

    suspend fun saveProvider(provider: ProviderProfileEntity) {
        validateProvider(provider)
        dao.upsertProvider(provider)
    }

    suspend fun saveProtocol(protocol: ProtocolDefinitionEntity) {
        validateProtocol(protocol)
        dao.upsertProtocol(protocol)
    }

    suspend fun saveBundle(
        provider: ProviderProfileEntity,
        model: ModelProfileEntity,
        runtime: AiRuntimeSettingEntity,
        protocol: ProtocolDefinitionEntity?,
    ) {
        validateBundle(provider, model, runtime, protocol)
        database.withTransaction {
            dao.upsertProvider(provider)
            if (protocol != null) dao.upsertProtocol(protocol)
            dao.upsertModel(model)
            dao.upsertRuntimeSetting(runtime)
        }
    }

    suspend fun saveFallbackBundle(
        provider: ProviderProfileEntity,
        model: ModelProfileEntity,
        protocol: ProtocolDefinitionEntity?,
    ) {
        validateProvider(provider)
        validateModel(model)
        if (model.providerId != provider.providerId) {
            invalid("model providerId must match the saved provider")
        }
        when (model.protocolType) {
            ModelProtocolType.OPENAI_RESPONSES -> if (protocol != null) {
                invalid("OPENAI_RESPONSES must not include a custom protocol definition")
            }
            ModelProtocolType.CUSTOM_JSON -> {
                val definition = protocol ?: invalid("CUSTOM_JSON requires a protocol definition")
                validateProtocol(definition)
                if (model.protocolDefinitionId != definition.protocolDefinitionId) {
                    invalid("model protocolDefinitionId must match the saved definition")
                }
            }
        }
        database.withTransaction {
            dao.upsertProvider(provider)
            if (protocol != null) dao.upsertProtocol(protocol)
            dao.upsertModel(model)
        }
    }

    fun validateBundle(
        provider: ProviderProfileEntity,
        model: ModelProfileEntity,
        runtime: AiRuntimeSettingEntity,
        protocol: ProtocolDefinitionEntity?,
    ) {
        validateProvider(provider)
        validateModel(model)
        validateRuntime(runtime)
        if (model.providerId != provider.providerId) {
            invalid("model providerId must match the saved provider")
        }
        if (runtime.defaultModelProfileId != model.modelProfileId) {
            invalid("runtime default model must match the saved model")
        }
        when (model.protocolType) {
            ModelProtocolType.OPENAI_RESPONSES -> if (
                model.protocolDefinitionId != null || protocol != null
            ) {
                invalid("OPENAI_RESPONSES must not include a custom protocol definition")
            }
            ModelProtocolType.CUSTOM_JSON -> {
                val definition = protocol
                    ?: invalid("CUSTOM_JSON requires a protocol definition")
                validateProtocol(definition)
                if (model.protocolDefinitionId != definition.protocolDefinitionId) {
                    invalid("model protocolDefinitionId must match the saved definition")
                }
            }
        }
    }

    private fun validateProtocol(protocol: ProtocolDefinitionEntity) {
        validateId("protocolDefinitionId", protocol.protocolDefinitionId)
        validateDisplayName(protocol.displayName)
        requireTextLength(
            "definitionJson",
            protocol.definitionJson,
            AiConfigurationLimits.PROTOCOL_DEFINITION_JSON_LENGTH,
        )
        try {
            CustomJsonProtocolDefinition.parse(protocol.definitionJson)
        } catch (error: IllegalArgumentException) {
            invalid("definitionJson is not a valid declarative protocol: ${error.message}")
        }
    }

    suspend fun saveModel(model: ModelProfileEntity) {
        validateModel(model)
        if (dao.getProvider(model.providerId) == null) {
            invalid("providerId does not reference an existing provider")
        }
        when (model.protocolType) {
            ModelProtocolType.OPENAI_RESPONSES -> if (model.protocolDefinitionId != null) {
                invalid("OPENAI_RESPONSES must not reference a custom protocol definition")
            }

            ModelProtocolType.CUSTOM_JSON -> {
                val protocolId = model.protocolDefinitionId
                    ?: invalid("CUSTOM_JSON requires a protocol definition")
                if (dao.getProtocol(protocolId) == null) {
                    invalid("protocolDefinitionId does not reference an existing definition")
                }
            }
        }
        dao.upsertModel(model)
    }

    suspend fun saveRuntimeSetting(setting: AiRuntimeSettingEntity) {
        validateRuntime(setting)
        val defaultModelId = setting.defaultModelProfileId
        if (defaultModelId != null && dao.getModel(defaultModelId) == null) {
            invalid("defaultModelProfileId does not reference an existing model")
        }
        dao.upsertRuntimeSetting(setting)
    }

    private fun validateRuntime(setting: AiRuntimeSettingEntity) {
        if (setting.singletonId != AiRuntimeSettingEntity.SINGLETON_ID) {
            invalid("singletonId must be ${AiRuntimeSettingEntity.SINGLETON_ID}")
        }
        validateQuota(
            "global",
            setting.globalMaxConcurrency,
            setting.globalRequestsPerMinute,
            setting.globalRequestsPerDay,
        )
        if (setting.dailyImageLimit !in 0..100_000) {
            invalid("dailyImageLimit must be between 0 and 100000")
        }
        requireTextLength("promptText", setting.promptText, AiConfigurationLimits.PROMPT_LENGTH)
    }

    suspend fun getProvider(providerId: String) = dao.getProvider(providerId)

    suspend fun getModel(modelProfileId: String) = dao.getModel(modelProfileId)

    suspend fun getEnabledVisionModels() = dao.getEnabledVisionModels()

    suspend fun getProtocol(protocolDefinitionId: String) = dao.getProtocol(protocolDefinitionId)

    suspend fun getRuntimeSetting() = dao.getRuntimeSetting()

    suspend fun deleteProvider(providerId: String): Boolean = dao.deleteProvider(providerId) > 0

    suspend fun deleteModel(modelProfileId: String): Boolean = dao.deleteModel(modelProfileId) > 0

    suspend fun deleteProtocol(protocolDefinitionId: String): Boolean =
        dao.deleteProtocol(protocolDefinitionId) > 0

    private fun validateProvider(provider: ProviderProfileEntity) {
        validateId("providerId", provider.providerId)
        validateDisplayName(provider.displayName)
        validateBaseUrl(provider.baseUrl, provider.cleartextApproved)
        requireTextLength("headersJson", provider.headersJson, AiConfigurationLimits.HEADERS_JSON_LENGTH)
        requireTextLength(
            "allowedRedirectOriginsJson",
            provider.allowedRedirectOriginsJson,
            AiConfigurationLimits.REDIRECT_ORIGINS_JSON_LENGTH,
        )
        validateHeadersJson(provider.headersJson)
        validateRedirectOriginsJson(provider.allowedRedirectOriginsJson)
        validateTimeout("connectTimeoutMillis", provider.connectTimeoutMillis)
        validateTimeout("readTimeoutMillis", provider.readTimeoutMillis)
        validateTimeout("writeTimeoutMillis", provider.writeTimeoutMillis)
        validateQuota(
            "provider",
            provider.maxConcurrency,
            provider.requestsPerMinute,
            provider.requestsPerDay,
        )
        when (provider.authMode) {
            ProviderAuthMode.NONE -> if (provider.credentialId != null) {
                invalid("NONE authentication must not reference a credential")
            }

            ProviderAuthMode.BEARER -> validateCredentialId(provider.credentialId)
            ProviderAuthMode.API_KEY_HEADER -> {
                validateCredentialId(provider.credentialId)
                validateHeaderName(provider.authHeaderName)
            }
        }
        provider.authHeaderName?.let(::validateHeaderName)
        provider.authPrefix?.let { requireTextLength("authPrefix", it, 64, allowBlank = true) }
    }

    private fun validateModel(model: ModelProfileEntity) {
        validateId("modelProfileId", model.modelProfileId)
        validateId("providerId", model.providerId)
        validateDisplayName(model.displayName)
        requireTextLength("modelId", model.modelId, AiConfigurationLimits.MODEL_ID_LENGTH)
        model.protocolDefinitionId?.let { validateId("protocolDefinitionId", it) }
        if (!model.supportsVision) invalid("image analysis models must support vision")
        model.maxOutputTokens?.let {
            if (it !in 1..AiConfigurationLimits.MAX_OUTPUT_TOKENS) {
                invalid("maxOutputTokens is outside the supported range")
            }
        }
        model.temperature?.let {
            if (!it.isFinite() || it !in 0.0..2.0) {
                invalid("temperature must be between 0 and 2")
            }
        }
        if (model.maxImageEdge !in AiConfigurationLimits.MIN_IMAGE_EDGE..AiConfigurationLimits.MAX_IMAGE_EDGE) {
            invalid("maxImageEdge is outside the supported range")
        }
        if (model.maxImageBytes !in AiConfigurationLimits.MIN_IMAGE_BYTES..AiConfigurationLimits.MAX_IMAGE_BYTES) {
            invalid("maxImageBytes is outside the supported range")
        }
        validateQuota("model", model.maxConcurrency, model.requestsPerMinute, model.requestsPerDay)
    }

    private fun validateBaseUrl(value: String, cleartextApproved: Boolean) {
        requireTextLength("baseUrl", value, AiConfigurationLimits.BASE_URL_LENGTH)
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            invalid("baseUrl is not a valid URL")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") invalid("baseUrl must use HTTPS or HTTP")
        if (uri.host.isNullOrBlank()) invalid("baseUrl must contain a host")
        if (uri.rawUserInfo != null) invalid("baseUrl must not contain user information")
        if (uri.rawQuery != null || uri.rawFragment != null) {
            invalid("baseUrl must not contain a query or fragment")
        }
        if (scheme == "http" && !cleartextApproved) {
            invalid("HTTP requires explicit approval for this provider profile")
        }
    }

    private fun validateQuota(scope: String, concurrency: Int, perMinute: Int, perDay: Int) {
        if (concurrency !in 1..AiConfigurationLimits.MAX_CONCURRENCY) {
            invalid("$scope maxConcurrency is outside the supported range")
        }
        if (perMinute !in 0..AiConfigurationLimits.MAX_REQUESTS_PER_MINUTE) {
            invalid("$scope requestsPerMinute is outside the supported range")
        }
        if (perDay !in 0..AiConfigurationLimits.MAX_REQUESTS_PER_DAY) {
            invalid("$scope requestsPerDay is outside the supported range")
        }
    }

    private fun validateHeadersJson(value: String) {
        val headers = try {
            JSONObject(value)
        } catch (_: Exception) {
            invalid("headersJson must be a JSON object")
        }
        headers.keys().forEach { name ->
            if (headers.opt(name) !is String) invalid("headersJson values must be strings")
            validateHeaderName(name)
        }
    }

    private fun validateRedirectOriginsJson(value: String) {
        val origins = try {
            JSONArray(value)
        } catch (_: Exception) {
            invalid("allowedRedirectOriginsJson must be a JSON array")
        }
        repeat(origins.length()) { index ->
            val origin = origins.opt(index) as? String
                ?: invalid("redirect origins must be strings")
            val uri = try {
                URI(origin)
            } catch (_: Exception) {
                invalid("redirect origin is not a valid URL")
            }
            if (
                uri.scheme?.lowercase() !in setOf("https", "http") ||
                uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                (uri.rawPath?.let { it.isNotEmpty() && it != "/" } == true) ||
                uri.rawQuery != null || uri.rawFragment != null
            ) {
                invalid("redirect entries must be URL origins without path, query, or fragment")
            }
        }
    }

    private fun validateTimeout(field: String, value: Int) {
        if (value !in AiConfigurationLimits.MIN_TIMEOUT_MILLIS..AiConfigurationLimits.MAX_TIMEOUT_MILLIS) {
            invalid("$field is outside the supported range")
        }
    }

    private fun validateId(field: String, value: String) {
        requireTextLength(field, value, AiConfigurationLimits.ID_LENGTH)
        if (!value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
            invalid("$field contains unsupported characters")
        }
    }

    private fun validateCredentialId(value: String?) {
        if (value == null) invalid("authentication requires a credential reference")
        validateId("credentialId", value)
    }

    private fun validateDisplayName(value: String) {
        requireTextLength("displayName", value, AiConfigurationLimits.DISPLAY_NAME_LENGTH)
    }

    private fun validateHeaderName(value: String?) {
        if (value.isNullOrBlank() || value.length > 128) invalid("authHeaderName is invalid")
        if (!value.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }) {
            invalid("authHeaderName contains unsupported characters")
        }
    }

    private fun requireTextLength(
        field: String,
        value: String,
        maximum: Int,
        allowBlank: Boolean = false,
    ) {
        if ((!allowBlank && value.isBlank()) || value.length > maximum) {
            invalid("$field is blank or exceeds $maximum characters")
        }
    }

    private fun invalid(message: String): Nothing = throw AiConfigurationValidationException(message)
}

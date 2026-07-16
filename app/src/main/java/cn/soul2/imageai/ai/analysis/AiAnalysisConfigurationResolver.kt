package cn.soul2.imageai.ai.analysis

import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity

data class ResolvedAiAnalysisConfiguration(
    val runtime: AiRuntimeSettingEntity,
    val provider: ProviderProfileEntity,
    val model: ModelProfileEntity,
    val protocolDefinition: ProtocolDefinitionEntity?,
)

enum class AiConfigurationFailure {
    RUNTIME_MISSING,
    DEFAULT_MODEL_MISSING,
    MODEL_DISABLED,
    MODEL_NOT_VISION_CAPABLE,
    PROVIDER_MISSING,
    PROVIDER_DISABLED,
    PROTOCOL_DEFINITION_MISSING,
    PROTOCOL_DEFINITION_DISABLED,
}

class AiConfigurationResolutionException(
    val failure: AiConfigurationFailure,
) : IllegalStateException("AI configuration is unavailable: $failure")

fun interface AiAnalysisConfigurationResolver {
    suspend fun resolve(): ResolvedAiAnalysisConfiguration
}

class RepositoryAiAnalysisConfigurationResolver(
    private val repository: AiConfigurationRepository,
) : AiAnalysisConfigurationResolver {
    override suspend fun resolve(): ResolvedAiAnalysisConfiguration {
        val runtime = repository.getRuntimeSetting()
            ?: unavailable(AiConfigurationFailure.RUNTIME_MISSING)
        val modelId = runtime.defaultModelProfileId
            ?: unavailable(AiConfigurationFailure.DEFAULT_MODEL_MISSING)
        val model = repository.getModel(modelId)
            ?: unavailable(AiConfigurationFailure.DEFAULT_MODEL_MISSING)
        if (!model.enabled) unavailable(AiConfigurationFailure.MODEL_DISABLED)
        if (!model.supportsVision) unavailable(AiConfigurationFailure.MODEL_NOT_VISION_CAPABLE)
        val provider = repository.getProvider(model.providerId)
            ?: unavailable(AiConfigurationFailure.PROVIDER_MISSING)
        if (!provider.enabled) unavailable(AiConfigurationFailure.PROVIDER_DISABLED)
        val protocol = when (model.protocolType) {
            ModelProtocolType.OPENAI_RESPONSES -> null
            ModelProtocolType.CUSTOM_JSON -> {
                val protocolId = model.protocolDefinitionId
                    ?: unavailable(AiConfigurationFailure.PROTOCOL_DEFINITION_MISSING)
                repository.getProtocol(protocolId)
                    ?: unavailable(AiConfigurationFailure.PROTOCOL_DEFINITION_MISSING)
            }
        }
        if (protocol != null && !protocol.enabled) {
            unavailable(AiConfigurationFailure.PROTOCOL_DEFINITION_DISABLED)
        }
        return ResolvedAiAnalysisConfiguration(runtime, provider, model, protocol)
    }

    private fun unavailable(failure: AiConfigurationFailure): Nothing =
        throw AiConfigurationResolutionException(failure)
}

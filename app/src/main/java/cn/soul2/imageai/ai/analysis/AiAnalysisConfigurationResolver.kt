package cn.soul2.imageai.ai.analysis

import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import cn.soul2.imageai.data.db.entity.ImagePartition

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

    suspend fun resolveCandidates(partition: ImagePartition = ImagePartition.MAIN): List<ResolvedAiAnalysisConfiguration> = listOf(resolve())
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
        return resolveModel(runtime, model)
    }

    override suspend fun resolveCandidates(partition: ImagePartition): List<ResolvedAiAnalysisConfiguration> {
        val runtime = repository.getRuntimeSetting()
            ?: unavailable(AiConfigurationFailure.RUNTIME_MISSING)
        val defaultId = runtime.defaultModelProfileId
            ?: unavailable(AiConfigurationFailure.DEFAULT_MODEL_MISSING)
        val default = repository.getModel(defaultId)
            ?: unavailable(AiConfigurationFailure.DEFAULT_MODEL_MISSING)
        val routedProviderIds = repository.getEnabledProviderIds(partition)
        return buildList {
            val candidates = repository.getEnabledVisionModels()
                .filter { it.providerId in routedProviderIds }
                .sortedBy { routedProviderIds.indexOf(it.providerId) }
            val first = candidates.firstOrNull { it.modelProfileId == default.modelProfileId }
                ?: candidates.firstOrNull()
                ?: unavailable(AiConfigurationFailure.DEFAULT_MODEL_MISSING)
            add(resolveModel(runtime, first))
            candidates
                .filter { it.modelProfileId != first.modelProfileId }
                .forEach { model ->
                    try {
                        add(resolveModel(runtime, model))
                    } catch (_: AiConfigurationResolutionException) {
                        // An incomplete fallback profile must not block other configured providers.
                    }
                }
        }
    }

    private suspend fun resolveModel(
        runtime: AiRuntimeSettingEntity,
        model: ModelProfileEntity,
    ): ResolvedAiAnalysisConfiguration {
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

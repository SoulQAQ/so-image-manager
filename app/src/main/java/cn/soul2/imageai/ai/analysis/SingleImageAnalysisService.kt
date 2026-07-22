package cn.soul2.imageai.ai.analysis

import cn.soul2.imageai.ai.image.ImagePreparationException
import cn.soul2.imageai.ai.image.ImagePreprocessor
import cn.soul2.imageai.ai.output.CanonicalAiDraftContext
import cn.soul2.imageai.ai.output.CanonicalAiOutputSchema
import cn.soul2.imageai.ai.quota.AiQuotaPolicy
import cn.soul2.imageai.analysis.ActivationResult
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class ImageAnalysisTarget(
    val imageLocalId: Long,
    val contentUri: String,
)

sealed interface SingleImageAnalysisResult {
    data class Success(
        val analysisId: String,
        val projectionGeneration: Long,
    ) : SingleImageAnalysisResult

    data class Failure(
        val reason: SingleImageAnalysisFailure,
        val detailCode: String? = null,
    ) : SingleImageAnalysisResult
}

enum class SingleImageAnalysisFailure {
    CONFIGURATION_REQUIRED,
    IMAGE_UNAVAILABLE,
    IMAGE_PREPARATION_FAILED,
    PROTOCOL_UNSUPPORTED,
    CREDENTIAL_REQUIRED,
    CREDENTIAL_UNAVAILABLE,
    REQUEST_LIMITED,
    NETWORK_FAILED,
    PROVIDER_REJECTED,
    RESPONSE_INVALID,
    INDEX_PROJECTION_BLOCKED,
    INTERNAL_ERROR,
}

fun interface SingleImageAnalyzer {
    suspend fun analyze(target: ImageAnalysisTarget): SingleImageAnalysisResult
}

class SingleImageAnalysisService(
    private val configurationResolver: AiAnalysisConfigurationResolver,
    private val imagePreprocessor: ImagePreprocessor,
    private val clients: AiModelClientRegistry,
    private val canonicalRepository: CanonicalMetadataRepository,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newAnalysisId: () -> String = { UUID.randomUUID().toString() },
) : SingleImageAnalyzer {
    override suspend fun analyze(target: ImageAnalysisTarget): SingleImageAnalysisResult {
        if (target.imageLocalId <= 0L || target.contentUri.isBlank()) {
            return SingleImageAnalysisResult.Failure(SingleImageAnalysisFailure.IMAGE_UNAVAILABLE)
        }
        val configurations = try {
            configurationResolver.resolveCandidates()
        } catch (error: AiConfigurationResolutionException) {
            return SingleImageAnalysisResult.Failure(
                SingleImageAnalysisFailure.CONFIGURATION_REQUIRED,
                error.failure.name,
            )
        }
        val startedAt = nowEpochMillis()
        val dailyLimit = configurations.first().runtime.dailyImageLimit
        if (dailyLimit > 0 && canonicalRepository.countCompletedAnalysesSince(utcDayStart(startedAt)) >= dailyLimit) {
            return SingleImageAnalysisResult.Failure(
                SingleImageAnalysisFailure.REQUEST_LIMITED,
                DAILY_IMAGE_LIMIT_DETAIL,
            )
        }
        var lastFailure: SingleImageAnalysisResult.Failure? = null
        for ((index, configuration) in configurations.withIndex()) {
            val image = try {
                imagePreprocessor.prepare(target.contentUri, configuration.model.maxImageEdge, configuration.model.maxImageBytes)
            } catch (error: ImagePreparationException) {
                return SingleImageAnalysisResult.Failure(SingleImageAnalysisFailure.IMAGE_PREPARATION_FAILED, error.failure.name)
            } catch (_: SecurityException) {
                return SingleImageAnalysisResult.Failure(SingleImageAnalysisFailure.IMAGE_UNAVAILABLE)
            }
            val quotaPolicy = try {
                AiQuotaPolicy.from(configuration.runtime, configuration.provider, configuration.model)
            } catch (_: IllegalArgumentException) {
                image.bytes.fill(0)
                lastFailure = SingleImageAnalysisResult.Failure(SingleImageAnalysisFailure.CONFIGURATION_REQUIRED)
                if (!configuration.runtime.automaticFailoverEnabled || index == configurations.lastIndex) {
                    break
                }
                continue
            }
            val payload = try {
                clients.require(configuration.model.protocolType).analyze(
                    AiModelInvocation(configuration, quotaPolicy, image),
                )
            } catch (error: AiModelException) {
                lastFailure = error.toAnalysisFailure()
                null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                lastFailure = SingleImageAnalysisResult.Failure(SingleImageAnalysisFailure.INTERNAL_ERROR)
                null
            } finally {
                image.bytes.fill(0)
            }
            if (payload == null) {
                if (!configuration.runtime.automaticFailoverEnabled ||
                    index == configurations.lastIndex ||
                    !lastFailure.isFailoverEligible()
                ) break
                continue
            }
            val completedAt = nowEpochMillis().coerceAtLeast(startedAt)
            val analysisId = newAnalysisId()
            val protocolId = configuration.protocolDefinition?.protocolDefinitionId
                ?: BUILTIN_OPENAI_RESPONSES_PROTOCOL_ID
            val draft = payload.toDraft(
                CanonicalAiDraftContext(
                    analysisId = analysisId,
                    imageLocalId = target.imageLocalId,
                    providerProfileId = configuration.provider.providerId,
                    modelProfileId = configuration.model.modelProfileId,
                    protocolDefinitionId = protocolId,
                    promptTemplateId = promptFingerprint(configuration.runtime.promptText),
                    createdAtEpochMillis = startedAt,
                    completedAtEpochMillis = completedAt,
                ),
            )
            return try {
                when (val activation = canonicalRepository.activateAnalysis(draft)) {
                    is ActivationResult.Activated -> SingleImageAnalysisResult.Success(
                        analysisId,
                        activation.projectionGeneration,
                    )
                    is ActivationResult.AlreadyActive -> SingleImageAnalysisResult.Success(
                        analysisId,
                        activation.projectionGeneration,
                    )
                    is ActivationResult.Blocked -> SingleImageAnalysisResult.Failure(
                        SingleImageAnalysisFailure.INDEX_PROJECTION_BLOCKED,
                        activation.code,
                    )
                }
            } catch (_: IllegalArgumentException) {
                SingleImageAnalysisResult.Failure(SingleImageAnalysisFailure.RESPONSE_INVALID)
            } catch (_: IllegalStateException) {
                SingleImageAnalysisResult.Failure(SingleImageAnalysisFailure.INTERNAL_ERROR)
            }
        }
        return lastFailure ?: SingleImageAnalysisResult.Failure(SingleImageAnalysisFailure.CONFIGURATION_REQUIRED)
    }

    private fun AiModelException.toAnalysisFailure() = SingleImageAnalysisResult.Failure(
        reason = when (failure) {
            AiModelFailure.UNSUPPORTED_PROTOCOL -> SingleImageAnalysisFailure.PROTOCOL_UNSUPPORTED
            AiModelFailure.CREDENTIAL_MISSING -> SingleImageAnalysisFailure.CREDENTIAL_REQUIRED
            AiModelFailure.CREDENTIAL_UNAVAILABLE -> SingleImageAnalysisFailure.CREDENTIAL_UNAVAILABLE
            AiModelFailure.QUOTA_REJECTED -> SingleImageAnalysisFailure.REQUEST_LIMITED
            AiModelFailure.NETWORK -> SingleImageAnalysisFailure.NETWORK_FAILED
            AiModelFailure.PROVIDER_HTTP_ERROR -> SingleImageAnalysisFailure.PROVIDER_REJECTED
            AiModelFailure.RESPONSE_INVALID -> SingleImageAnalysisFailure.RESPONSE_INVALID
        },
        detailCode = failure.name,
    )

    private fun SingleImageAnalysisResult.Failure?.isFailoverEligible(): Boolean =
        this?.reason in setOf(
            SingleImageAnalysisFailure.CREDENTIAL_REQUIRED,
            SingleImageAnalysisFailure.CREDENTIAL_UNAVAILABLE,
            SingleImageAnalysisFailure.REQUEST_LIMITED,
            SingleImageAnalysisFailure.NETWORK_FAILED,
            SingleImageAnalysisFailure.PROVIDER_REJECTED,
            SingleImageAnalysisFailure.PROTOCOL_UNSUPPORTED,
        )

    private fun promptFingerprint(prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "prompt.sha256.$digest"
    }

    private fun utcDayStart(epochMillis: Long): Long =
        epochMillis.coerceAtLeast(0L) / MILLIS_PER_DAY * MILLIS_PER_DAY

    companion object {
        const val BUILTIN_OPENAI_RESPONSES_PROTOCOL_ID = "builtin.openai-responses.v1"
        const val OUTPUT_SCHEMA_VERSION = CanonicalAiOutputSchema.VERSION
        const val DAILY_IMAGE_LIMIT_DETAIL = "DAILY_IMAGE_LIMIT"
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}

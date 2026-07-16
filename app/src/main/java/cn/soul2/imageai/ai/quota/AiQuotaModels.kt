package cn.soul2.imageai.ai.quota

import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity

data class AiQuotaLimit(
    val maxConcurrency: Int,
    val requestsPerMinute: Int,
    val requestsPerDay: Int,
) {
    init {
        require(maxConcurrency > 0)
        require(requestsPerMinute >= 0)
        require(requestsPerDay >= 0)
    }
}

data class AiQuotaPolicy(
    val providerId: String,
    val modelProfileId: String,
    val global: AiQuotaLimit,
    val provider: AiQuotaLimit,
    val model: AiQuotaLimit,
) {
    companion object {
        fun from(
            runtime: AiRuntimeSettingEntity,
            provider: ProviderProfileEntity,
            model: ModelProfileEntity,
        ): AiQuotaPolicy {
            require(model.providerId == provider.providerId) {
                "Model and provider do not belong to the same quota hierarchy"
            }
            return AiQuotaPolicy(
                providerId = provider.providerId,
                modelProfileId = model.modelProfileId,
                global = runtime.toQuotaLimit(),
                provider = provider.toQuotaLimit(),
                model = model.toQuotaLimit(),
            )
        }
    }
}

enum class AiQuotaScopeType {
    GLOBAL,
    PROVIDER,
    MODEL,
}

enum class AiQuotaRejectionReason {
    CONCURRENCY,
    REQUESTS_PER_MINUTE,
    REQUESTS_PER_DAY,
    STORAGE_UNAVAILABLE,
}

sealed interface AiQuotaAcquireResult {
    data class Granted(val lease: AiQuotaLease) : AiQuotaAcquireResult

    data class Rejected(
        val scope: AiQuotaScopeType,
        val reason: AiQuotaRejectionReason,
        val retryAtEpochMillis: Long?,
    ) : AiQuotaAcquireResult
}

interface AiQuotaLease : AutoCloseable {
    /** Call immediately before handing the request to the HTTP stack. */
    fun markDispatched()

    override fun close()
}

private fun AiRuntimeSettingEntity.toQuotaLimit() = AiQuotaLimit(
    maxConcurrency = globalMaxConcurrency,
    requestsPerMinute = globalRequestsPerMinute,
    requestsPerDay = globalRequestsPerDay,
)

private fun ProviderProfileEntity.toQuotaLimit() = AiQuotaLimit(
    maxConcurrency = maxConcurrency,
    requestsPerMinute = requestsPerMinute,
    requestsPerDay = requestsPerDay,
)

private fun ModelProfileEntity.toQuotaLimit() = AiQuotaLimit(
    maxConcurrency = maxConcurrency,
    requestsPerMinute = requestsPerMinute,
    requestsPerDay = requestsPerDay,
)

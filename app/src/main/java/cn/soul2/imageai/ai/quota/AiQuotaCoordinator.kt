package cn.soul2.imageai.ai.quota

import java.util.concurrent.atomic.AtomicInteger

class AiQuotaCoordinator internal constructor(
    private val stateStore: AiQuotaStateStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: android.content.Context) : this(
        SharedPreferencesAiQuotaStateStore(context),
    )

    private val lock = Any()
    private val concurrentByScope = mutableMapOf<String, Int>()

    fun tryAcquire(policy: AiQuotaPolicy): AiQuotaAcquireResult = synchronized(lock) {
        val scopes = policy.scopes()
        val now = nowEpochMillis().coerceAtLeast(0L)
        val usages = linkedMapOf<String, PersistentQuotaUsage>()

        scopes.forEach { scope ->
            val persisted = try {
                stateStore.load(scope.key)
            } catch (_: RuntimeException) {
                return@synchronized rejected(
                    scope,
                    AiQuotaRejectionReason.STORAGE_UNAVAILABLE,
                    null,
                )
            }
            val currentUsage = normalizeUsage(
                persisted,
                scope.key,
                now,
            )
            usages[scope.key] = currentUsage
            rejectionFor(scope, currentUsage)?.let { return@synchronized it }
        }

        val reservedUsages = usages.mapValues { (_, usage) ->
            usage.copy(
                minuteCount = usage.minuteCount + 1,
                dayCount = usage.dayCount + 1,
            )
        }
        val saved = try {
            stateStore.save(reservedUsages.values)
        } catch (_: RuntimeException) {
            false
        }
        if (!saved) {
            return@synchronized AiQuotaAcquireResult.Rejected(
                scope = AiQuotaScopeType.GLOBAL,
                reason = AiQuotaRejectionReason.STORAGE_UNAVAILABLE,
                retryAtEpochMillis = null,
            )
        }
        scopes.forEach { scope ->
            concurrentByScope[scope.key] = concurrentByScope.getOrDefault(scope.key, 0) + 1
        }
        AiQuotaAcquireResult.Granted(
            Lease(
                coordinator = this,
                scopeKeys = scopes.map(QuotaScope::key),
                reservedUsages = reservedUsages,
            ),
        )
    }

    private fun rejectionFor(
        scope: QuotaScope,
        usage: PersistentQuotaUsage,
    ): AiQuotaAcquireResult.Rejected? {
        if (concurrentByScope.getOrDefault(scope.key, 0) >= scope.limit.maxConcurrency) {
            return rejected(scope, AiQuotaRejectionReason.CONCURRENCY, null)
        }
        if (
            scope.limit.requestsPerMinute > 0 &&
            usage.minuteCount >= scope.limit.requestsPerMinute
        ) {
            return rejected(
                scope,
                AiQuotaRejectionReason.REQUESTS_PER_MINUTE,
                usage.minuteWindowStartEpochMillis + MILLIS_PER_MINUTE,
            )
        }
        if (scope.limit.requestsPerDay > 0 && usage.dayCount >= scope.limit.requestsPerDay) {
            return rejected(
                scope,
                AiQuotaRejectionReason.REQUESTS_PER_DAY,
                (usage.utcDayNumber + 1) * MILLIS_PER_DAY,
            )
        }
        return null
    }

    private fun rejected(
        scope: QuotaScope,
        reason: AiQuotaRejectionReason,
        retryAtEpochMillis: Long?,
    ) = AiQuotaAcquireResult.Rejected(scope.type, reason, retryAtEpochMillis)

    private fun normalizeUsage(
        persisted: PersistentQuotaUsage?,
        scopeKey: String,
        now: Long,
    ): PersistentQuotaUsage {
        val effectiveNow = maxOf(now, persisted?.lastSeenEpochMillis ?: now)
        val minuteStart = effectiveNow / MILLIS_PER_MINUTE * MILLIS_PER_MINUTE
        val utcDay = effectiveNow / MILLIS_PER_DAY
        return PersistentQuotaUsage(
            scopeKey = scopeKey,
            minuteWindowStartEpochMillis = minuteStart,
            minuteCount = if (persisted?.minuteWindowStartEpochMillis == minuteStart) {
                persisted.minuteCount
            } else {
                0
            },
            utcDayNumber = utcDay,
            dayCount = if (persisted?.utcDayNumber == utcDay) persisted.dayCount else 0,
            lastSeenEpochMillis = effectiveNow,
        )
    }

    private fun release(
        scopeKeys: List<String>,
        reservedUsages: Map<String, PersistentQuotaUsage>,
        dispatched: Boolean,
    ) = synchronized(lock) {
        scopeKeys.forEach { key ->
            val remaining = concurrentByScope.getOrDefault(key, 0) - 1
            if (remaining > 0) concurrentByScope[key] = remaining else concurrentByScope.remove(key)
        }
        if (!dispatched) {
            try {
                val rolledBack = reservedUsages.mapValues { (key, reserved) ->
                    val current = stateStore.load(key)
                    if (
                        current?.minuteWindowStartEpochMillis == reserved.minuteWindowStartEpochMillis &&
                        current.utcDayNumber == reserved.utcDayNumber
                    ) {
                        current.copy(
                            minuteCount = (current.minuteCount - 1).coerceAtLeast(0),
                            dayCount = (current.dayCount - 1).coerceAtLeast(0),
                        )
                    } else {
                        current ?: reserved
                    }
                }
                stateStore.save(rolledBack.values)
            } catch (_: RuntimeException) {
                // A failed rollback overcounts conservatively and never permits excess requests.
            }
        }
    }

    private class Lease(
        private val coordinator: AiQuotaCoordinator,
        private val scopeKeys: List<String>,
        private val reservedUsages: Map<String, PersistentQuotaUsage>,
    ) : AiQuotaLease {
        private val state = AtomicInteger(STATE_RESERVED)

        override fun markDispatched() {
            while (true) {
                when (state.get()) {
                    STATE_DISPATCHED -> return
                    STATE_CLOSED -> error("Quota lease is already closed")
                    STATE_RESERVED -> if (state.compareAndSet(STATE_RESERVED, STATE_DISPATCHED)) {
                        return
                    }
                }
            }
        }

        override fun close() {
            val previous = state.getAndSet(STATE_CLOSED)
            if (previous != STATE_CLOSED) {
                coordinator.release(
                    scopeKeys,
                    reservedUsages,
                    dispatched = previous == STATE_DISPATCHED,
                )
            }
        }

        private companion object {
            const val STATE_RESERVED = 0
            const val STATE_DISPATCHED = 1
            const val STATE_CLOSED = 2
        }
    }

    private data class QuotaScope(
        val key: String,
        val type: AiQuotaScopeType,
        val limit: AiQuotaLimit,
    )

    private fun AiQuotaPolicy.scopes() = listOf(
        QuotaScope(GLOBAL_SCOPE_KEY, AiQuotaScopeType.GLOBAL, global),
        QuotaScope("provider.$providerId", AiQuotaScopeType.PROVIDER, provider),
        QuotaScope("model.$modelProfileId", AiQuotaScopeType.MODEL, model),
    )

    private companion object {
        const val GLOBAL_SCOPE_KEY = "global"
        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

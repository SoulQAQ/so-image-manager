package cn.soul2.imageai.ai.quota

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiQuotaCoordinatorTest {
    @Test
    fun providerRejectionDoesNotOccupyGlobalLease() {
        val store = InMemoryQuotaStateStore()
        val coordinator = AiQuotaCoordinator(store) { NOW }
        val providerOne = policy(
            providerId = "one",
            modelId = "one-model",
            globalConcurrency = 2,
            providerConcurrency = 1,
        )
        val first = coordinator.requireLease(providerOne)

        val rejected = coordinator.tryAcquire(providerOne) as AiQuotaAcquireResult.Rejected
        assertEquals(AiQuotaScopeType.PROVIDER, rejected.scope)
        assertEquals(AiQuotaRejectionReason.CONCURRENCY, rejected.reason)

        val providerTwo = policy(
            providerId = "two",
            modelId = "two-model",
            globalConcurrency = 2,
            providerConcurrency = 1,
        )
        val second = coordinator.tryAcquire(providerTwo)
        assertTrue(second is AiQuotaAcquireResult.Granted)

        first.close()
        (second as AiQuotaAcquireResult.Granted).lease.close()
    }

    @Test
    fun modelRejectionDoesNotOccupyProviderLease() {
        val coordinator = AiQuotaCoordinator(InMemoryQuotaStateStore()) { NOW }
        val modelOne = policy(
            providerId = "provider",
            modelId = "one",
            globalConcurrency = 3,
            providerConcurrency = 2,
            modelConcurrency = 1,
        )
        val first = coordinator.requireLease(modelOne)

        val rejected = coordinator.tryAcquire(modelOne) as AiQuotaAcquireResult.Rejected
        assertEquals(AiQuotaScopeType.MODEL, rejected.scope)

        val modelTwo = modelOne.copy(modelProfileId = "two")
        val second = coordinator.tryAcquire(modelTwo)
        assertTrue(second is AiQuotaAcquireResult.Granted)

        first.close()
        (second as AiQuotaAcquireResult.Granted).lease.close()
    }

    @Test
    fun closingBeforeDispatchRollsBackRateReservation() {
        val coordinator = AiQuotaCoordinator(InMemoryQuotaStateStore()) { NOW }
        val policy = policy(providerRequestsPerMinute = 1)

        coordinator.requireLease(policy).close()
        val replacement = coordinator.tryAcquire(policy)

        assertTrue(replacement is AiQuotaAcquireResult.Granted)
        (replacement as AiQuotaAcquireResult.Granted).lease.close()
    }

    @Test
    fun dispatchedRequestCountsAgainstMinuteLimitAcrossCoordinatorRecreation() {
        val store = InMemoryQuotaStateStore()
        val policy = policy(providerRequestsPerMinute = 1)
        AiQuotaCoordinator(store) { NOW }.requireLease(policy).use { lease ->
            lease.markDispatched()
        }

        val rejected = AiQuotaCoordinator(store) { NOW }
            .tryAcquire(policy) as AiQuotaAcquireResult.Rejected

        assertEquals(AiQuotaScopeType.PROVIDER, rejected.scope)
        assertEquals(AiQuotaRejectionReason.REQUESTS_PER_MINUTE, rejected.reason)
        assertEquals(180_000L, rejected.retryAtEpochMillis)
    }

    @Test
    fun eachRedirectReservationCountsAsAnotherRealRequest() {
        val coordinator = AiQuotaCoordinator(InMemoryQuotaStateStore()) { NOW }
        val policy = policy(modelRequestsPerDay = 2)
        repeat(2) {
            coordinator.requireLease(policy).use { lease -> lease.markDispatched() }
        }

        val rejected = coordinator.tryAcquire(policy) as AiQuotaAcquireResult.Rejected

        assertEquals(AiQuotaScopeType.MODEL, rejected.scope)
        assertEquals(AiQuotaRejectionReason.REQUESTS_PER_DAY, rejected.reason)
        assertEquals(86_400_000L, rejected.retryAtEpochMillis)
    }

    @Test
    fun wallClockRollbackDoesNotResetMinuteOrDailyCounters() {
        var now = NOW
        val coordinator = AiQuotaCoordinator(InMemoryQuotaStateStore()) { now }
        val policy = policy(providerRequestsPerMinute = 1)
        coordinator.requireLease(policy).use { lease -> lease.markDispatched() }

        now = 1L
        val rejected = coordinator.tryAcquire(policy) as AiQuotaAcquireResult.Rejected

        assertEquals(AiQuotaRejectionReason.REQUESTS_PER_MINUTE, rejected.reason)
        assertEquals(180_000L, rejected.retryAtEpochMillis)
    }

    @Test
    fun storageFailureRejectsWithoutOccupyingConcurrency() {
        val store = InMemoryQuotaStateStore(saveSucceeds = false)
        val coordinator = AiQuotaCoordinator(store) { NOW }
        val policy = policy(globalConcurrency = 1)

        val rejected = coordinator.tryAcquire(policy) as AiQuotaAcquireResult.Rejected
        assertEquals(AiQuotaRejectionReason.STORAGE_UNAVAILABLE, rejected.reason)

        store.saveSucceeds = true
        val granted = coordinator.tryAcquire(policy)
        assertTrue(granted is AiQuotaAcquireResult.Granted)
        (granted as AiQuotaAcquireResult.Granted).lease.close()
    }

    @Test
    fun corruptedPersistentStateRejectsWithoutCrashingOrOccupyingConcurrency() {
        val store = InMemoryQuotaStateStore(throwOnLoad = true)
        val coordinator = AiQuotaCoordinator(store) { NOW }
        val policy = policy(globalConcurrency = 1)

        val rejected = coordinator.tryAcquire(policy) as AiQuotaAcquireResult.Rejected
        assertEquals(AiQuotaRejectionReason.STORAGE_UNAVAILABLE, rejected.reason)

        store.throwOnLoad = false
        val granted = coordinator.tryAcquire(policy)
        assertTrue(granted is AiQuotaAcquireResult.Granted)
        (granted as AiQuotaAcquireResult.Granted).lease.close()
    }

    @Test
    fun concurrentCallersNeverExceedTheGlobalLimit() {
        val coordinator = AiQuotaCoordinator(InMemoryQuotaStateStore()) { NOW }
        val policy = policy(globalConcurrency = 5, providerConcurrency = 10, modelConcurrency = 10)
        val start = CountDownLatch(1)
        val finish = CountDownLatch(20)
        val leases = Collections.synchronizedList(mutableListOf<AiQuotaLease>())
        val rejections = Collections.synchronizedList(mutableListOf<AiQuotaAcquireResult.Rejected>())
        val executor = Executors.newFixedThreadPool(20)

        repeat(20) {
            executor.execute {
                start.await()
                when (val result = coordinator.tryAcquire(policy)) {
                    is AiQuotaAcquireResult.Granted -> leases += result.lease
                    is AiQuotaAcquireResult.Rejected -> rejections += result
                }
                finish.countDown()
            }
        }
        start.countDown()
        assertTrue(finish.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(5, leases.size)
        assertEquals(15, rejections.size)
        assertTrue(rejections.all { it.scope == AiQuotaScopeType.GLOBAL })
        leases.forEach(AiQuotaLease::close)
    }

    private fun AiQuotaCoordinator.requireLease(policy: AiQuotaPolicy): AiQuotaLease {
        val result = tryAcquire(policy)
        assertTrue("Expected quota grant but got $result", result is AiQuotaAcquireResult.Granted)
        return (result as AiQuotaAcquireResult.Granted).lease
    }

    private fun policy(
        providerId: String = "provider",
        modelId: String = "model",
        globalConcurrency: Int = 4,
        providerConcurrency: Int = 3,
        modelConcurrency: Int = 2,
        providerRequestsPerMinute: Int = 0,
        modelRequestsPerDay: Int = 0,
    ) = AiQuotaPolicy(
        providerId = providerId,
        modelProfileId = modelId,
        global = AiQuotaLimit(globalConcurrency, 0, 0),
        provider = AiQuotaLimit(providerConcurrency, providerRequestsPerMinute, 0),
        model = AiQuotaLimit(modelConcurrency, 0, modelRequestsPerDay),
    )

    private class InMemoryQuotaStateStore(
        var saveSucceeds: Boolean = true,
        var throwOnLoad: Boolean = false,
    ) : AiQuotaStateStore {
        private val values = mutableMapOf<String, PersistentQuotaUsage>()

        @Synchronized
        override fun load(scopeKey: String): PersistentQuotaUsage? {
            if (throwOnLoad) error("corrupt state")
            return values[scopeKey]
        }

        @Synchronized
        override fun save(usages: Collection<PersistentQuotaUsage>): Boolean {
            if (!saveSucceeds) return false
            usages.forEach { values[it.scopeKey] = it }
            return true
        }
    }

    private companion object {
        const val NOW = 120_000L
    }
}

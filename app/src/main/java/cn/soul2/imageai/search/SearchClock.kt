package cn.soul2.imageai.search

internal fun interface SearchMonotonicClock {
    fun nowMillis(): Long

    companion object {
        val System = SearchMonotonicClock { java.lang.System.nanoTime() / 1_000_000L }
    }
}

internal class SearchDeadline(
    private val clock: SearchMonotonicClock,
    budgetMillis: Long,
) {
    private val expiresAt = clock.nowMillis() + budgetMillis

    fun expired(): Boolean = clock.nowMillis() >= expiresAt

    fun check() {
        if (expired()) throw SearchBudgetExceeded
    }
}

internal data object SearchBudgetExceeded : RuntimeException(null, null, false, false)

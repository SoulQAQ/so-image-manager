package cn.soul2.imageai.search

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SearchGenerationGuard {
    private val latestGeneration = AtomicLong(Long.MIN_VALUE)

    fun begin(generation: Long) {
        require(generation >= 0L) { "search generation must not be negative" }
        latestGeneration.set(generation)
    }

    suspend fun checkpoint(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (latestGeneration.get() != generation) throw CancellationException("obsolete search generation")
    }
}

package cn.soul2.imageai.update

import kotlin.math.roundToLong
import java.util.ArrayDeque

internal class DownloadSpeedEstimator(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
) {
    private val samples = ArrayDeque<Sample>()
    private var lastObservationMillis: Long? = null
    private var lastSpeedBytesPerSecond: Long? = null

    fun observe(downloadedBytes: Long, observedAtMillis: Long): Long? {
        val current = Sample(downloadedBytes.coerceAtLeast(0L), observedAtMillis)
        val previous = samples.peekLast()
        if (
            lastObservationMillis?.let { current.observedAtMillis <= it } == true ||
            (previous != null && current.downloadedBytes < previous.downloadedBytes)
        ) {
            samples.clear()
            lastSpeedBytesPerSecond = null
        }
        lastObservationMillis = current.observedAtMillis
        val latest = samples.peekLast()
        if (latest != null && current.downloadedBytes == latest.downloadedBytes) {
            return lastSpeedBytesPerSecond?.takeIf {
                current.observedAtMillis - latest.observedAtMillis <= staleAfterMillis
            }
        }
        samples.addLast(current)
        while (
            samples.size > 2 &&
            current.observedAtMillis - samples.elementAt(1).observedAtMillis >= windowMillis
        ) {
            samples.removeFirst()
        }
        val earlier = samples.peekFirst()
        if (earlier == null || earlier === current) {
            lastSpeedBytesPerSecond = null
            return null
        }
        val elapsedMillis = current.observedAtMillis - earlier.observedAtMillis
        if (elapsedMillis <= 0L) return null
        val downloadedSinceLastSample = current.downloadedBytes - earlier.downloadedBytes
        val bytesPerSecond = downloadedSinceLastSample.toDouble() * 1_000.0 /
            elapsedMillis.toDouble()
        return bytesPerSecond.roundToLong().coerceAtLeast(1L).also {
            lastSpeedBytesPerSecond = it
        }
    }

    private data class Sample(val downloadedBytes: Long, val observedAtMillis: Long)

    companion object {
        private const val DEFAULT_WINDOW_MILLIS = 5_000L
        private const val DEFAULT_STALE_AFTER_MILLIS = 3_000L
    }
}

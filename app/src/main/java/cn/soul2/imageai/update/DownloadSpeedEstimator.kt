package cn.soul2.imageai.update

import kotlin.math.roundToLong
import java.util.ArrayDeque

internal class DownloadSpeedEstimator(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val samples = ArrayDeque<Sample>()

    fun observe(downloadedBytes: Long, observedAtMillis: Long): Long? {
        val current = Sample(downloadedBytes.coerceAtLeast(0L), observedAtMillis)
        val previous = samples.peekLast()
        if (
            previous != null &&
            (current.observedAtMillis <= previous.observedAtMillis ||
                current.downloadedBytes < previous.downloadedBytes)
        ) {
            samples.clear()
        }
        samples.addLast(current)
        while (
            samples.size > 2 &&
            current.observedAtMillis - samples.elementAt(1).observedAtMillis >= windowMillis
        ) {
            samples.removeFirst()
        }
        val earlier = samples.peekFirst()
        if (earlier == null || earlier === current) return null
        val elapsedMillis = current.observedAtMillis - earlier.observedAtMillis
        if (elapsedMillis <= 0L) return null
        val downloadedSinceLastSample = current.downloadedBytes - earlier.downloadedBytes
        val bytesPerSecond = downloadedSinceLastSample.toDouble() * 1_000.0 /
            elapsedMillis.toDouble()
        return bytesPerSecond.roundToLong().coerceAtLeast(0L)
    }

    private data class Sample(val downloadedBytes: Long, val observedAtMillis: Long)

    companion object {
        private const val DEFAULT_WINDOW_MILLIS = 5_000L
    }
}

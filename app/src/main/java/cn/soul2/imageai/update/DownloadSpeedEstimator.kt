package cn.soul2.imageai.update

import kotlin.math.roundToLong

internal class DownloadSpeedEstimator {
    private var previous: Sample? = null

    fun observe(downloadedBytes: Long, observedAtMillis: Long): Long? {
        val current = Sample(downloadedBytes.coerceAtLeast(0L), observedAtMillis)
        val earlier = previous
        previous = current
        if (earlier == null || current.downloadedBytes < earlier.downloadedBytes) return null
        val elapsedMillis = current.observedAtMillis - earlier.observedAtMillis
        if (elapsedMillis <= 0L) return null
        val downloadedSinceLastSample = current.downloadedBytes - earlier.downloadedBytes
        val bytesPerSecond = downloadedSinceLastSample.toDouble() * 1_000.0 /
            elapsedMillis.toDouble()
        return bytesPerSecond.roundToLong().coerceAtLeast(0L)
    }

    private data class Sample(val downloadedBytes: Long, val observedAtMillis: Long)
}

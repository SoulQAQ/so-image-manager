package cn.soul2.imageai.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readForSizeValidation(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val detectionLimit = maxBytes.toLong() + 1L
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (total < detectionLimit) {
        val requested = minOf(buffer.size.toLong(), detectionLimit - total).toInt()
        val read = read(buffer, 0, requested)
        if (read < 0) break
        if (read == 0) {
            val byte = read()
            if (byte < 0) break
            output.write(byte)
            total++
            continue
        }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

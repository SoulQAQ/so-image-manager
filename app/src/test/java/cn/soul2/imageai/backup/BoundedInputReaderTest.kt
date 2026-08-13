package cn.soul2.imageai.backup

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedInputReaderTest {
    @Test
    fun returnsTheCompleteInputAtOrBelowTheLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readForSizeValidation(bytes.size))
    }

    @Test
    fun readsOnlyOneBytePastTheLimit() {
        val input = CountingInputStream(ByteArray(64) { it.toByte() })

        val result = input.readForSizeValidation(8)

        assertEquals(9, result.size)
        assertEquals(9, input.bytesRead)
    }

    @Test
    fun rejectsNegativeLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf()).readForSizeValidation(-1)
        }
    }

    @Test
    fun progressesWhenBulkReadTemporarilyReturnsZero() {
        val input = object : InputStream() {
            private val delegate = ByteArrayInputStream(byteArrayOf(7, 8))
            private var firstBulkRead = true

            override fun read(): Int = delegate.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (firstBulkRead) {
                    firstBulkRead = false
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }

        assertArrayEquals(byteArrayOf(7, 8), input.readForSizeValidation(2))
    }

    private class CountingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var bytesRead: Int = 0
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }
}

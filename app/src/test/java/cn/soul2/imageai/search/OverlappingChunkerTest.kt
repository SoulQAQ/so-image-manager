package cn.soul2.imageai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlappingChunkerTest {
    @Test
    fun handlesEmptyExactAndBoundaryLengths() {
        assertEquals(emptyList<SearchTextChunk>(), OverlappingChunker.chunkByCodePoints(""))
        assertEquals(listOf(512), OverlappingChunker.chunkByCodePoints("a".repeat(512)).map { it.text.length })

        val fiveHundredThirteen = OverlappingChunker.chunkByCodePoints("a".repeat(513))
        assertEquals(listOf(0, 385), fiveHundredThirteen.map { it.startOffset })
        assertEquals(listOf(512, 128), fiveHundredThirteen.map { it.text.length })

        val eightHundredNinetySix = OverlappingChunker.chunkByCodePoints("a".repeat(896))
        assertEquals(listOf(0, 385), eightHundredNinetySix.map { it.startOffset })
        assertEquals(listOf(512, 511), eightHundredNinetySix.map { it.text.length })
    }

    @Test
    fun codePointChunksNeverSplitSurrogatePairsAndUseCodePointOffsets() {
        val chunks = OverlappingChunker.chunkByCodePoints("😀".repeat(513))

        assertEquals(listOf(0, 385), chunks.map { it.startOffset })
        assertEquals(listOf(512, 128), chunks.map { it.text.codePointCount(0, it.text.length) })
        chunks.forEach { requireValidSearchUtf16(it.text, "chunk") }
    }

    @Test
    fun everySlidingBoundaryContainsAnyCrossingMaximumQuery() {
        val source = buildString {
            repeat(2_000) { append(('a'.code + it % 26).toChar()) }
        }
        val chunks = OverlappingChunker.chunkByCodePoints(source)
        val boundaries = chunks.drop(1).map { it.startOffset + SearchLimits.SOURCE_CHUNK_OVERLAP }

        boundaries.forEach { boundary ->
            val query = source.substring(boundary - 64, boundary + 64)
            assertTrue(chunks.any { query in it.text })
        }
    }

    @Test
    fun finalChunkAlwaysReachesTheSourceSuffix() {
        val source = "0123456789".repeat(137)
        val chunks = OverlappingChunker.chunkByCodePoints(source)
        val final = chunks.last()

        assertEquals(source.length, final.startOffset + final.text.length)
        assertEquals(source, reconstruct(chunks))
        assertEquals(chunks.indices.toList(), chunks.map { it.ordinal })
    }

    @Test
    fun characterChunksUseUtf16CharacterOffsets() {
        val chunks = OverlappingChunker.chunkByCharacters("x".repeat(513))

        assertEquals(listOf(0, 385), chunks.map { it.startOffset })
        assertEquals(listOf(512, 128), chunks.map { it.text.length })
    }

    @Test
    fun characterChunksPreservePairsAcrossNominalEndAndNextStartBoundaries() {
        val pairAcrossEnd = "a".repeat(511) + "😀" + "b".repeat(600)
        val pairAcrossNextStart = "a".repeat(384) + "😀" + "b".repeat(700)

        assertCharacterChunkContract(pairAcrossEnd)
        assertCharacterChunkContract(pairAcrossNextStart)
    }

    @Test
    fun rejectsInvalidConfigurationAndUtf16() {
        listOf(
            -1 to 0,
            1 to -1,
            1 to 1,
            1 to 2,
        ).forEach { (size, overlap) ->
            assertThrows(IllegalArgumentException::class.java) {
                OverlappingChunker.chunkByCodePoints("text", size, overlap)
            }
        }
        assertThrows(SearchValidationException::class.java) {
            OverlappingChunker.chunkByCodePoints("bad\uD800")
        }
        assertThrows(SearchValidationException::class.java) {
            OverlappingChunker.chunkByCharacters("bad\uDC00")
        }
    }

    private fun reconstruct(chunks: List<SearchTextChunk>): String = buildString {
        chunks.forEachIndexed { index, chunk ->
            if (index == 0) append(chunk.text) else append(chunk.text.drop(SearchLimits.SOURCE_CHUNK_OVERLAP))
        }
    }

    private fun assertCharacterChunkContract(source: String) {
        val chunks = OverlappingChunker.chunkByCharacters(source)
        chunks.forEach { chunk ->
            requireValidSearchUtf16(chunk.text, "chunk")
            assertTrue(chunk.text.length <= SearchLimits.ALIAS_CHUNK_CHARACTERS)
            assertEquals(
                chunk.text,
                source.substring(chunk.startOffset, chunk.startOffset + chunk.text.length),
            )
        }
        chunks.zipWithNext().forEach { (previous, next) ->
            val previousEnd = previous.startOffset + previous.text.length
            assertTrue(previousEnd - next.startOffset >= SearchLimits.ALIAS_CHUNK_OVERLAP)
        }
        assertEquals(source.length, chunks.last().startOffset + chunks.last().text.length)
        assertEquals(source, reconstructByOffsets(chunks))
    }

    private fun reconstructByOffsets(chunks: List<SearchTextChunk>): String = buildString {
        chunks.forEach { chunk ->
            val alreadyWritten = length - chunk.startOffset
            if (alreadyWritten < chunk.text.length) append(chunk.text.drop(alreadyWritten.coerceAtLeast(0)))
        }
    }
}

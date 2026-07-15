package cn.soul2.imageai.search

data class SearchTextChunk(
    val ordinal: Int,
    val startOffset: Int,
    val text: String,
)

object OverlappingChunker {
    fun chunkByCodePoints(
        text: String,
        size: Int = SearchLimits.SOURCE_CHUNK_CODE_POINTS,
        overlap: Int = SearchLimits.SOURCE_CHUNK_OVERLAP,
    ): List<SearchTextChunk> {
        validate(text, size, overlap)
        if (text.isEmpty()) return emptyList()

        val codePointCount = text.codePointCount(0, text.length)
        val utf16Offsets = IntArray(codePointCount + 1)
        var utf16Offset = 0
        for (codePointOffset in 0 until codePointCount) {
            utf16Offsets[codePointOffset] = utf16Offset
            utf16Offset += Character.charCount(text.codePointAt(utf16Offset))
        }
        utf16Offsets[codePointCount] = text.length

        return chunk(codePointCount, size, overlap) { ordinal, start, end ->
            SearchTextChunk(
                ordinal = ordinal,
                startOffset = start,
                text = text.substring(utf16Offsets[start], utf16Offsets[end]),
            )
        }
    }

    fun chunkByCharacters(
        text: String,
        size: Int = SearchLimits.ALIAS_CHUNK_CHARACTERS,
        overlap: Int = SearchLimits.ALIAS_CHUNK_OVERLAP,
    ): List<SearchTextChunk> {
        validate(text, size, overlap)
        if (text.isEmpty()) return emptyList()

        val chunks = mutableListOf<SearchTextChunk>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (splitsSurrogatePair(text, end)) end--
            require(end > start) { "size is too small to preserve a UTF-16 surrogate pair" }
            chunks += SearchTextChunk(chunks.size, start, text.substring(start, end))
            if (end == text.length) break

            var nextStart = end - overlap
            if (splitsSurrogatePair(text, nextStart)) nextStart--
            require(nextStart > start) {
                "size and overlap cannot advance without splitting a UTF-16 surrogate pair"
            }
            start = nextStart
        }
        return chunks
    }

    private fun validate(text: String, size: Int, overlap: Int) {
        require(size >= 0) { "size must not be negative" }
        require(overlap >= 0) { "overlap must not be negative" }
        require(size > overlap) { "size must be greater than overlap" }
        requireValidSearchUtf16(text, "text")
    }

    private fun splitsSurrogatePair(text: String, offset: Int): Boolean =
        offset > 0 &&
            offset < text.length &&
            Character.isHighSurrogate(text[offset - 1]) &&
            Character.isLowSurrogate(text[offset])

    private inline fun chunk(
        length: Int,
        size: Int,
        overlap: Int,
        create: (ordinal: Int, start: Int, end: Int) -> SearchTextChunk,
    ): List<SearchTextChunk> {
        val chunks = mutableListOf<SearchTextChunk>()
        val step = size - overlap
        var start = 0
        while (start < length) {
            val end = minOf(start + size, length)
            chunks += create(chunks.size, start, end)
            if (end == length) break
            start += step
        }
        return chunks
    }
}

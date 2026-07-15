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
        return chunk(text.length, size, overlap) { ordinal, start, end ->
            SearchTextChunk(ordinal, start, text.substring(start, end))
        }
    }

    private fun validate(text: String, size: Int, overlap: Int) {
        require(size >= 0) { "size must not be negative" }
        require(overlap >= 0) { "overlap must not be negative" }
        require(size > overlap) { "size must be greater than overlap" }
        requireValidSearchUtf16(text, "text")
    }

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

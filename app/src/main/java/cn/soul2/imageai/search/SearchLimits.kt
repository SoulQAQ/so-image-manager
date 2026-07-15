package cn.soul2.imageai.search

object SearchLimits {
    const val QUERY_CODE_POINTS = 128
    const val SOURCE_CHUNK_CODE_POINTS = 512
    const val SOURCE_CHUNK_OVERLAP = 127
    const val ALIAS_CHUNK_CHARACTERS = 512
    const val ALIAS_CHUNK_OVERLAP = 127
    const val LEXICAL_ALIAS_CHARACTERS = 128
    const val LEXICAL_ALIAS_COUNT = 3
    const val RELATIONSHIPS_PER_IMAGE = 768
    const val GRAM_TERM_CANDIDATES = 512
    const val TYPO_TERMS = 64
    const val IMAGE_CANDIDATES = 5_000
}

class SearchValidationException(message: String) : IllegalArgumentException(message)

internal fun requireValidSearchUtf16(value: String, field: String) {
    var index = 0
    while (index < value.length) {
        when {
            Character.isHighSurrogate(value[index]) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw SearchValidationException("$field contains an unpaired surrogate")
                }
                index += 2
            }
            Character.isLowSurrogate(value[index]) -> {
                throw SearchValidationException("$field contains an unpaired surrogate")
            }
            else -> index++
        }
    }
}

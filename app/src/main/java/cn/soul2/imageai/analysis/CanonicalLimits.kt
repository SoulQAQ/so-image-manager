package cn.soul2.imageai.analysis

object CanonicalLimits {
    const val CAPTION_UTF8_BYTES = 4 * 1_024
    const val TERM_CODE_POINTS = 128
    const val TAGS = 128
    const val CATEGORIES = 32
    const val SEARCH_TOKENS = 256
    const val DEFAULT_EXTENSION_JSON_UTF8_BYTES = 16 * 1_024
    const val EXTENSION_JSON_UTF8_BYTES = 64 * 1_024
    const val EXTENSION_JSON_MAX_DEPTH = 32
    const val EXTENSION_JSON_MAX_NODES = 50_000
    const val PROVENANCE_ID_CODE_POINTS = 256
}

class CanonicalValidationException(message: String) : IllegalArgumentException(message)

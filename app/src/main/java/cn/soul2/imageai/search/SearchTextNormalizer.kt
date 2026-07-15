package cn.soul2.imageai.search

import java.text.Normalizer
import java.util.Locale

data class NormalizedSearchQuery(
    val text: String,
)

object SearchTextNormalizer {
    private val whitespace = Regex("[\\p{Z}\\s]+")

    fun normalizeQuery(raw: String): NormalizedSearchQuery {
        requireValidSearchUtf16(raw, "query")
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()
        if (text.codePointCount(0, text.length) > SearchLimits.QUERY_CODE_POINTS) {
            throw SearchValidationException(
                "query exceeds ${SearchLimits.QUERY_CODE_POINTS} code points",
            )
        }
        return NormalizedSearchQuery(text)
    }
}

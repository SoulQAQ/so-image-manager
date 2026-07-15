package cn.soul2.imageai.analysis

import java.text.Normalizer
import java.util.Locale

data class NormalizedMetadataTerm(
    val displayValue: String,
    val normalizedKey: String,
)

object MetadataNormalizer {
    private val whitespace = Regex("[\\p{Z}\\s]+")

    fun normalizeTerm(value: String): NormalizedMetadataTerm {
        requireValidUtf16(value, "term")
        val displayValue = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(whitespace, " ")
            .trim()
        if (displayValue.isEmpty()) invalid("term must not be blank")
        if (displayValue.codePointCount() > CanonicalLimits.TERM_CODE_POINTS) {
            invalid("term exceeds ${CanonicalLimits.TERM_CODE_POINTS} code points")
        }
        return NormalizedMetadataTerm(
            displayValue = displayValue,
            normalizedKey = displayValue.lowercase(Locale.ROOT),
        )
    }

    fun normalizeCaption(value: String): String {
        requireValidUtf16(value, "caption")
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) invalid("caption must not be blank")
        if (normalized.utf8Size() > CanonicalLimits.CAPTION_UTF8_BYTES) {
            invalid("caption exceeds ${CanonicalLimits.CAPTION_UTF8_BYTES} UTF-8 bytes")
        }
        return normalized
    }

    fun normalizeIdentifier(value: String, field: String): String {
        requireValidUtf16(value, field)
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
        if (normalized.isEmpty()) invalid("$field must not be blank")
        if (normalized.codePointCount() > CanonicalLimits.PROVENANCE_ID_CODE_POINTS) {
            invalid("$field exceeds ${CanonicalLimits.PROVENANCE_ID_CODE_POINTS} code points")
        }
        if (normalized.any(Character::isISOControl)) {
            invalid("$field contains control characters")
        }
        return normalized
    }

    fun requireValidUtf16(value: String, field: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        invalid("$field contains an unpaired surrogate")
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) ->
                    invalid("$field contains an unpaired surrogate")
                else -> index++
            }
        }
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)
    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}

internal fun invalid(message: String): Nothing = throw CanonicalValidationException(message)

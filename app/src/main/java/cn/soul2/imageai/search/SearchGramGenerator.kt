package cn.soul2.imageai.search

import java.text.Normalizer
import java.util.Locale

object SearchGramGenerator {
    fun grams(text: String): Set<String> {
        requireValidSearchUtf16(text, "text")
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val grams = linkedSetOf<String>()
        val unit = mutableListOf<Int>()
        var unitKind: UnitKind? = null

        fun flush() {
            val size = when (unitKind) {
                UnitKind.CJK -> 2
                UnitKind.LATIN_OR_DIGIT -> 3
                null -> return
            }
            for (start in 0..unit.size - size) {
                grams += buildString {
                    repeat(size) { appendCodePoint(unit[start + it]) }
                }
            }
            unit.clear()
            unitKind = null
        }

        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            val kind = kindOf(codePoint)
            if (kind == null) {
                flush()
            } else {
                if (unitKind != null && unitKind != kind) flush()
                unitKind = kind
                unit += codePoint
            }
            offset += Character.charCount(codePoint)
        }
        flush()
        return grams
    }

    private fun kindOf(codePoint: Int): UnitKind? = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> UnitKind.CJK
        Character.UnicodeScript.LATIN -> UnitKind.LATIN_OR_DIGIT
        else -> if (Character.isDigit(codePoint)) UnitKind.LATIN_OR_DIGIT else null
    }

    private enum class UnitKind {
        CJK,
        LATIN_OR_DIGIT,
    }
}

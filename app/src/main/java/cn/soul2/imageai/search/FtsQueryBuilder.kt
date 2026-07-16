package cn.soul2.imageai.search

internal object FtsQueryBuilder {
    fun prefixQuery(normalized: String): String? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        normalized.codePoints().forEach { codePoint ->
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(codePoint)
            } else {
                flush()
            }
        }
        flush()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" AND ") { token -> "$token*" }
    }
}

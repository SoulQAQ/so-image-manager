package cn.soul2.imageai.search

data class StructuredSearchQuery(
    val freeText: String,
    val tags: List<String>,
    val categories: List<String>,
    val albums: List<String>,
) {
    val hasFilters: Boolean get() = tags.isNotEmpty() || categories.isNotEmpty() || albums.isNotEmpty()
}

object StructuredSearchParser {
    fun parse(raw: String): StructuredSearchQuery {
        val tags = mutableListOf<String>()
        val categories = mutableListOf<String>()
        val albums = mutableListOf<String>()
        val free = mutableListOf<String>()
        val converted = convertNaturalLanguageFilters(raw)
        converted.trim().split(Regex("\\s+")).filter(String::isNotBlank).forEach { token ->
            val split = token.indexOf(':').takeIf { it > 0 } ?: token.indexOf('：').takeIf { it > 0 }
            if (split == null) {
                free += token
                return@forEach
            }
            val key = token.substring(0, split).lowercase()
            val value = token.substring(split + 1).trim().take(128)
            if (value.isBlank()) return@forEach
            when (key) {
                "标签", "tag" -> tags += SearchTextNormalizer.normalizeQuery(value).text
                "分类", "category" -> categories += SearchTextNormalizer.normalizeQuery(value).text
                "相册", "album" -> albums += value
                else -> free += token
            }
        }
        return StructuredSearchQuery(
            free.joinToString(" ").take(512), tags.distinct(), categories.distinct(), albums.distinct(),
        )
    }

    private fun convertNaturalLanguageFilters(raw: String): String {
        var result = raw.take(2_048)
        NATURAL_FILTERS.forEach { (field, regex) ->
            result = regex.replace(result) { match -> "$field:${match.groupValues[1]}" }
        }
        return result
    }

    private val NATURAL_FILTERS = listOf(
        "标签" to Regex("(?:标签(?:是|为|叫)|带有标签)[\\s“”\"']*([^\\s，。；、“”\"']{1,64})"),
        "分类" to Regex("(?:分类(?:是|为|叫)|属于分类)[\\s“”\"']*([^\\s，。；、“”\"']{1,64})"),
        "相册" to Regex("(?:相册(?:是|为|叫)|相册里的|在相册)[\\s“”\"']*([^\\s，。；、“”\"']{1,64})"),
    )
}

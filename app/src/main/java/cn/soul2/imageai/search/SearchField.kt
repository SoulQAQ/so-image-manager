package cn.soul2.imageai.search

enum class SearchField(
    val mask: Int,
    val defaultWeight: Double,
) {
    FILE_NAME(1, 450.0),
    ALBUM(2, 450.0),
    CAPTION(4, 300.0),
    TAG(8, 500.0),
    CATEGORY(16, 500.0),
    SEARCH_TOKEN(32, 350.0),
    MEDIA_TEXT(64, 450.0),
}

package cn.soul2.imageai.search

enum class SearchStage {
    STRUCTURED,
    FTS4,
    SUBSTRING,
    TYPO,
    PINYIN,
}

enum class SearchTier {
    USER,
    EXACT_STRUCTURED,
    FTS4,
    SUBSTRING,
    TYPO,
    PINYIN,
}

enum class SearchPartialReason {
    GRAM_TERM_CAP,
    TYPO_TERM_CAP,
    IMAGE_CANDIDATE_CAP,
    STRUCTURED_TIMEOUT,
    SUBSTRING_TIMEOUT,
    FUZZY_TIMEOUT,
    INDEX_DEGRADED,
    REBUILD_REQUIRED,
}

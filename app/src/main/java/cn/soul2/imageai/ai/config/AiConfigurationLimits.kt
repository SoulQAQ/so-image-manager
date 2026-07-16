package cn.soul2.imageai.ai.config

object AiConfigurationLimits {
    const val ID_LENGTH = 128
    const val DISPLAY_NAME_LENGTH = 80
    const val BASE_URL_LENGTH = 2_048
    const val HEADERS_JSON_LENGTH = 16_384
    const val REDIRECT_ORIGINS_JSON_LENGTH = 8_192
    const val PROTOCOL_DEFINITION_JSON_LENGTH = 131_072
    const val PROMPT_LENGTH = 16_384
    const val MODEL_ID_LENGTH = 256
    const val MIN_TIMEOUT_MILLIS = 100
    const val MAX_TIMEOUT_MILLIS = 300_000
    const val MAX_CONCURRENCY = 64
    const val MAX_REQUESTS_PER_MINUTE = 60_000
    const val MAX_REQUESTS_PER_DAY = 1_000_000
    const val MIN_IMAGE_EDGE = 256
    const val MAX_IMAGE_EDGE = 4_096
    const val MIN_IMAGE_BYTES = 64 * 1_024
    const val MAX_IMAGE_BYTES = 8 * 1_024 * 1_024
    const val MAX_OUTPUT_TOKENS = 65_536
}

class AiConfigurationValidationException(message: String) : IllegalArgumentException(message)

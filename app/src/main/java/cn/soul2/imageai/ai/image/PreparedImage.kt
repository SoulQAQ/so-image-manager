package cn.soul2.imageai.ai.image

data class PreparedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

enum class ImagePreparationFailure {
    SOURCE_UNAVAILABLE,
    DECODE_FAILED,
    ENCODE_FAILED,
    OUTPUT_LIMIT_UNREACHABLE,
}

class ImagePreparationException(
    val failure: ImagePreparationFailure,
    cause: Throwable? = null,
) : Exception("Image preparation failed: $failure", cause)

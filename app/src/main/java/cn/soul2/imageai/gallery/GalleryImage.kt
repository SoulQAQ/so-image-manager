package cn.soul2.imageai.gallery

data class GalleryImage(
    val localId: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val capturedAtEpochMillis: Long?,
    val addedAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
    val bucketName: String?,
    val isFavorite: Boolean,
) {
    val originalAspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
}

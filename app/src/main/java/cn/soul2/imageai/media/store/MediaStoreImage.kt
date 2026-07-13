package cn.soul2.imageai.media.store

import java.security.MessageDigest

data class MediaStoreImage(
    val volumeName: String,
    val mediaStoreId: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val capturedAtEpochMillis: Long?,
    val addedAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
    val bucketId: Long?,
    val bucketName: String?,
    val isFavorite: Boolean,
    val generationModified: Long?,
) {
    fun quickFingerprint(): String {
        val metadata = listOf(
            sizeBytes.toString(),
            modifiedAtEpochMillis.toString(),
            width.toString(),
            height.toString(),
            mimeType,
        ).joinToString(separator = "|")
        return MessageDigest.getInstance("SHA-256")
            .digest(metadata.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

object MediaStoreTime {
    fun secondsToEpochMillis(seconds: Long): Long = seconds * MILLIS_PER_SECOND

    private const val MILLIS_PER_SECOND = 1_000L
}

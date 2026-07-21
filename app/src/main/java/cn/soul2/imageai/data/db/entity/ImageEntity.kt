package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ImageAvailability {
    AVAILABLE,
    REMOVED_FROM_SOIM,
    PERMISSION_REVOKED,
    SELECTION_REMOVED,
    VOLUME_UNMOUNTED,
    MEDIA_MISSING,
    TRANSIENT_IO,
}

enum class ImageSource {
    MEDIA_STORE,
    DOCUMENT,
}

@Entity(
    tableName = "image",
    indices = [
        Index(value = ["volume_name", "media_store_id"], unique = true),
        Index(value = ["availability", "sort_time_epoch_millis", "media_store_id"]),
        Index(
            value = [
                "bucket_id",
                "availability",
                "sort_time_epoch_millis",
                "media_store_id",
            ],
        ),
    ],
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id")
    val localId: Long = 0,
    @ColumnInfo(name = "volume_name")
    val volumeName: String,
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    @ColumnInfo(name = "content_uri")
    val contentUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long?,
    @ColumnInfo(name = "added_at_epoch_millis")
    val addedAtEpochMillis: Long,
    @ColumnInfo(name = "modified_at_epoch_millis")
    val modifiedAtEpochMillis: Long,
    @ColumnInfo(name = "sort_time_epoch_millis")
    val sortTimeEpochMillis: Long,
    @ColumnInfo(name = "bucket_id")
    val bucketId: Long?,
    @ColumnInfo(name = "bucket_name")
    val bucketName: String?,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean,
    @ColumnInfo(name = "quick_fingerprint")
    val quickFingerprint: String,
    val availability: ImageAvailability,
    @ColumnInfo(name = "last_seen_sync_run_id")
    val lastSeenSyncRunId: Long?,
    @ColumnInfo(name = "missing_candidate_since_epoch_millis")
    val missingCandidateSinceEpochMillis: Long?,
    @ColumnInfo(name = "missing_observation_count")
    val missingObservationCount: Int,
    val source: ImageSource = ImageSource.MEDIA_STORE,
)

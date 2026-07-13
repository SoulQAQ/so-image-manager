package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_sync_checkpoint")
data class MediaSyncCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "volume_name")
    val volumeName: String,
    val generation: Long?,
    @ColumnInfo(name = "media_store_version")
    val mediaStoreVersion: String?,
    @ColumnInfo(name = "cursor_modified_at_epoch_millis")
    val cursorModifiedAtEpochMillis: Long?,
    @ColumnInfo(name = "cursor_media_store_id")
    val cursorMediaStoreId: Long?,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "full_reconciliation_at_epoch_millis")
    val fullReconciliationAtEpochMillis: Long?,
)

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
    @ColumnInfo(name = "full_scan_cursor_modified_at_epoch_millis")
    val fullScanCursorModifiedAtEpochMillis: Long?,
    @ColumnInfo(name = "full_scan_cursor_media_store_id")
    val fullScanCursorMediaStoreId: Long?,
    @ColumnInfo(name = "incremental_high_water_modified_at_epoch_millis")
    val incrementalHighWaterModifiedAtEpochMillis: Long?,
    @ColumnInfo(name = "incremental_high_water_media_store_id")
    val incrementalHighWaterMediaStoreId: Long?,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "full_reconciliation_at_epoch_millis")
    val fullReconciliationAtEpochMillis: Long?,
)

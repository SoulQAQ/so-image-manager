package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_sync_run")
data class MediaSyncRunEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "run_id")
    val runId: Long = 0,
    val mode: String,
    val state: String,
    @ColumnInfo(name = "current_volume_name")
    val currentVolumeName: String?,
    @ColumnInfo(name = "discovered_count")
    val discoveredCount: Int,
    @ColumnInfo(name = "indexed_count")
    val indexedCount: Int,
    @ColumnInfo(name = "unavailable_count")
    val unavailableCount: Int,
    @ColumnInfo(name = "error_code")
    val errorCode: String?,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
)

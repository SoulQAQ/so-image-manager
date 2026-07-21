package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "batch_analysis_run")
data class BatchAnalysisRunEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "run_id") val runId: Long = 0,
    val state: String,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "completed_count") val completedCount: Int,
    @ColumnInfo(name = "failed_count") val failedCount: Int,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis") val completedAtEpochMillis: Long?,
)

@Entity(
    tableName = "batch_analysis_item",
    primaryKeys = ["run_id", "image_local_id"],
    foreignKeys = [
        ForeignKey(
            entity = BatchAnalysisRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["state", "run_id"]), Index(value = ["image_local_id"])],
)
data class BatchAnalysisItemEntity(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "image_local_id") val imageLocalId: Long,
    val state: String,
    @ColumnInfo(name = "failure_code") val failureCode: String? = null,
)

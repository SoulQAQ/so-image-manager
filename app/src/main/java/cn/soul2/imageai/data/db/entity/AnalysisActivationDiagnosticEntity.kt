package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "analysis_activation_diagnostic",
    foreignKeys = [
        ForeignKey(
            entity = ImageAnalysisEntity::class,
            parentColumns = ["analysis_id"],
            childColumns = ["analysis_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AnalysisActivationDiagnosticEntity(
    @PrimaryKey
    @ColumnInfo(name = "analysis_id")
    val analysisId: String,
    val code: String,
    val detail: String?,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

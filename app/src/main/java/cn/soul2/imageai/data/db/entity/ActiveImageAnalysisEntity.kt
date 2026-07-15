package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "active_image_analysis",
    primaryKeys = ["image_local_id"],
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ImageAnalysisEntity::class,
            parentColumns = ["analysis_id", "image_local_id"],
            childColumns = ["analysis_id", "image_local_id"],
            onDelete = ForeignKey.NO_ACTION,
            deferred = true,
        ),
    ],
    indices = [Index(value = ["analysis_id", "image_local_id"])],
)
data class ActiveImageAnalysisEntity(
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    @ColumnInfo(name = "analysis_id")
    val analysisId: String,
)

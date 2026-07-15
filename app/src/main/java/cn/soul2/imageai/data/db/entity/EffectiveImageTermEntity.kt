package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class EffectiveTermSource {
    AI,
    USER,
}

@Entity(
    tableName = "effective_image_term",
    primaryKeys = ["image_local_id", "kind", "normalized_key"],
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
            childColumns = ["source_analysis_id", "image_local_id"],
            onDelete = ForeignKey.NO_ACTION,
            deferred = true,
        ),
    ],
    indices = [
        Index(value = ["kind", "normalized_key", "image_local_id"]),
        Index(value = ["source_analysis_id", "image_local_id"]),
    ],
)
data class EffectiveImageTermEntity(
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    val kind: AnalysisTermKind,
    @ColumnInfo(name = "normalized_key")
    val normalizedKey: String,
    @ColumnInfo(name = "display_value")
    val displayValue: String,
    val source: EffectiveTermSource,
    val confidence: Double?,
    @ColumnInfo(name = "source_analysis_id")
    val sourceAnalysisId: String?,
)

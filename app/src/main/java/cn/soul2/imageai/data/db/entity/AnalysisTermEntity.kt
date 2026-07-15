package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class AnalysisTermKind {
    TAG,
    CATEGORY,
    SEARCH_TOKEN,
}

@Entity(
    tableName = "analysis_term",
    primaryKeys = ["analysis_id", "kind", "normalized_key"],
    foreignKeys = [
        ForeignKey(
            entity = ImageAnalysisEntity::class,
            parentColumns = ["analysis_id"],
            childColumns = ["analysis_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["analysis_id"])],
)
data class AnalysisTermEntity(
    @ColumnInfo(name = "analysis_id")
    val analysisId: String,
    val kind: AnalysisTermKind,
    @ColumnInfo(name = "normalized_key")
    val normalizedKey: String,
    @ColumnInfo(name = "display_value")
    val displayValue: String,
    val confidence: Double?,
)

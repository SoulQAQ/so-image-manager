package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class UserTermOverrideAction {
    ADD,
    TOMBSTONE,
}

@Entity(
    tableName = "user_term_override",
    primaryKeys = ["image_local_id", "kind", "normalized_key"],
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["image_local_id"])],
)
data class UserTermOverrideEntity(
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    val kind: AnalysisTermKind,
    @ColumnInfo(name = "normalized_key")
    val normalizedKey: String,
    val action: UserTermOverrideAction,
    @ColumnInfo(name = "display_value")
    val displayValue: String?,
    val revision: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

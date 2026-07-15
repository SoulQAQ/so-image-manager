package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

enum class CaptionCorrectionMode {
    INHERIT,
    SET,
    CLEARED,
}

@Entity(
    tableName = "image_user_correction",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ImageUserCorrectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    @ColumnInfo(name = "caption_mode")
    val captionMode: CaptionCorrectionMode,
    @ColumnInfo(name = "caption_value")
    val captionValue: String?,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

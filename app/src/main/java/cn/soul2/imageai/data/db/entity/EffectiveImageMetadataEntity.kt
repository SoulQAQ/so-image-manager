package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

enum class EffectiveCaptionSource {
    NONE,
    AI,
    USER,
    USER_CLEARED,
}

@Entity(
    tableName = "effective_image_metadata",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EffectiveImageMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    val caption: String?,
    @ColumnInfo(name = "caption_source")
    val captionSource: EffectiveCaptionSource,
    @ColumnInfo(name = "projection_generation")
    val projectionGeneration: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

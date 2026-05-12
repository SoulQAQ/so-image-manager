package cn.soul2.imageai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_feature",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["imageId"])]
)
data class ImageFeatureEntity(
    @PrimaryKey
    val imageId: Long,

    val phash: String?,
    val updatedAt: Long
)

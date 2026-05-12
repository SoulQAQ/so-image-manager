package cn.soul2.imageai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "image_tag",
    primaryKeys = ["imageId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["imageId"]),
        Index(value = ["tagId"])
    ]
)
data class ImageTagEntity(
    val imageId: Long,
    val tagId: Long,
    val confidence: Float = 1.0f,
    val isUserTag: Boolean = false
)

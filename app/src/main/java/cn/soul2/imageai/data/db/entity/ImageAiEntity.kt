package cn.soul2.imageai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_ai",
    primaryKeys = ["imageId"],
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
data class ImageAiEntity(
    val imageId: Long,

    // AI 生成结果
    val aiCaption: String?,
    val aiTagsJson: String?,
    val aiCategoriesJson: String?,
    val aiSearchTokens: String?,

    // 用户修正结果
    val userCaption: String?,
    val userTagsJson: String?,
    val userCategoriesJson: String?,

    // 其他字段
    val ocrText: String?,
    val rawJson: String?
)

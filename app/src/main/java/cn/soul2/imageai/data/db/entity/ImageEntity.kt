package cn.soul2.imageai.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image",
    indices = [
        Index(value = ["sha256"], unique = true),
        Index(value = ["aiStatus"]),
        Index(value = ["importTime"])
    ]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val uri: String,
    val path: String?,
    val sha256: String,

    val width: Int?,
    val height: Int?,
    val mimeType: String?,
    val fileSize: Long?,

    val createTime: Long?,
    val modifyTime: Long?,
    val importTime: Long,

    // AI 状态: 0=待处理, 1=处理中, 2=已完成, -1=失败
    val aiStatus: Int = 0,

    val aiModel: String? = null,
    val promptVersion: String? = null,
    val schemaVersion: String? = null,

    val analyzedAt: Long? = null,
    val userModified: Int = 0
) {
    companion object {
        const val AI_STATUS_PENDING = 0
        const val AI_STATUS_PROCESSING = 1
        const val AI_STATUS_DONE = 2
        const val AI_STATUS_FAILED = -1
    }
}

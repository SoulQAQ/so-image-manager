package cn.soul2.imageai.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_query_cache",
    indices = [Index(value = ["expireAt"])]
)
data class ImageQueryCacheEntity(
    @PrimaryKey
    val sha256: String,

    val aiModel: String,
    val schemaVersion: String,
    val resultJson: String,
    val createdAt: Long,
    val expireAt: Long
)

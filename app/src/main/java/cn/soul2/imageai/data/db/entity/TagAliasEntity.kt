package cn.soul2.imageai.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tag_alias",
    indices = [
        Index(value = ["alias"], unique = true),
        Index(value = ["canonical"])
    ]
)
data class TagAliasEntity(
    @PrimaryKey
    val alias: String,

    val canonical: String
)

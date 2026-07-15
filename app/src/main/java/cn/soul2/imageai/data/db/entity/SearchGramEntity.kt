package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

enum class SearchGramOwnerType {
    TERM,
    TERM_ALIAS,
    SOURCE_CHUNK,
    TEXT_ALIAS_CHUNK,
}

@Entity(
    tableName = "search_gram",
    primaryKeys = ["gram", "owner_type", "owner_id"],
    indices = [Index(value = ["gram", "owner_type"])],
)
data class SearchGramEntity(
    val gram: String,
    @ColumnInfo(name = "owner_type")
    val ownerType: SearchGramOwnerType,
    @ColumnInfo(name = "owner_id")
    val ownerId: Long,
)

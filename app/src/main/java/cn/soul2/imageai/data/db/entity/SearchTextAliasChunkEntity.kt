package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cn.soul2.imageai.search.PinyinAliasType

@Entity(
    tableName = "search_text_alias_chunk",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["image_local_id", "field", "alias_type", "ordinal"],
            unique = true,
        ),
    ],
)
data class SearchTextAliasChunkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "alias_chunk_id")
    val aliasChunkId: Long = 0,
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    val field: String,
    @ColumnInfo(name = "alias_type")
    val aliasType: PinyinAliasType,
    val ordinal: Int,
    @ColumnInfo(name = "alias_text")
    val aliasText: String,
)

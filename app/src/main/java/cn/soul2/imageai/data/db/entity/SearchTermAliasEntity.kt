package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cn.soul2.imageai.search.PinyinAliasType

@Entity(
    tableName = "search_term_alias",
    foreignKeys = [
        ForeignKey(
            entity = SearchTermEntity::class,
            parentColumns = ["term_id"],
            childColumns = ["term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["term_id", "alias_type"]),
        Index(value = ["term_id", "alias_type", "alias_text"], unique = true),
    ],
)
data class SearchTermAliasEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "alias_id")
    val aliasId: Long = 0,
    @ColumnInfo(name = "term_id")
    val termId: Long,
    @ColumnInfo(name = "alias_type")
    val aliasType: PinyinAliasType,
    @ColumnInfo(name = "alias_text")
    val aliasText: String,
)

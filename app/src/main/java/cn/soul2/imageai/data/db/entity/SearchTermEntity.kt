package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SearchTermUnitType {
    CJK,
    LATIN_DIGIT,
    MIXED,
}

@Entity(
    tableName = "search_term",
    indices = [Index(value = ["normalized_key"], unique = true)],
)
data class SearchTermEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "term_id")
    val termId: Long = 0,
    @ColumnInfo(name = "normalized_key")
    val normalizedKey: String,
    @ColumnInfo(name = "display_value")
    val displayValue: String,
    @ColumnInfo(name = "unit_type")
    val unitType: SearchTermUnitType,
)

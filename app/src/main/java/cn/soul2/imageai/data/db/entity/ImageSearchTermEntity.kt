package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class SearchTermOwnership {
    MEDIA,
    AI,
    USER,
}

@Entity(
    tableName = "image_search_term",
    primaryKeys = ["image_local_id", "term_id", "field_mask"],
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SearchTermEntity::class,
            parentColumns = ["term_id"],
            childColumns = ["term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["image_local_id", "field_mask", "weight"]),
        Index(value = ["term_id"]),
    ],
)
data class ImageSearchTermEntity(
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    @ColumnInfo(name = "term_id")
    val termId: Long,
    @ColumnInfo(name = "field_mask")
    val fieldMask: Int,
    val ownership: SearchTermOwnership,
    val weight: Double,
)

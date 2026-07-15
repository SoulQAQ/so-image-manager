package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "search_source_chunk",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["image_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["image_local_id", "field", "ordinal"], unique = true)],
)
data class SearchSourceChunkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "chunk_id")
    val chunkId: Long = 0,
    @ColumnInfo(name = "image_local_id")
    val imageLocalId: Long,
    val field: String,
    val ordinal: Int,
    @ColumnInfo(name = "normalized_text")
    val normalizedText: String,
)

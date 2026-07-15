package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "search_document",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["rowid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SearchDocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val imageLocalId: Long,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    val album: String,
    val caption: String,
    val tags: String,
    val categories: String,
    @ColumnInfo(name = "search_tokens")
    val searchTokens: String,
    @ColumnInfo(name = "media_text")
    val mediaText: String,
)

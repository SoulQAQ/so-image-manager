package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Fts4(contentEntity = SearchDocumentEntity::class)
@Entity(tableName = "search_document_fts")
data class SearchDocumentFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
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

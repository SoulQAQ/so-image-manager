package cn.soul2.imageai.data.db.dao

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import cn.soul2.imageai.data.db.entity.ImageQueryCacheEntity

data class SearchResult(
    val imageId: Long,
    val uri: String,
    val sha256: String,
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
    val relevance: Double
)

@Dao
interface SearchDao {

    // FTS5 搜索 - 使用 RawQuery 绕过编译时验证
    @RawQuery(observedEntities = [])
    suspend fun searchByRawQuery(query: SupportSQLiteQuery): List<SearchResult>

    suspend fun searchByText(query: String, limit: Int = 100): List<SearchResult> {
        val escapedQuery = query.replace("'", "''")
        val sql = """
            SELECT i.id as imageId, i.uri, i.sha256, i.width, i.height, i.mimeType, bm25(image_fts) as relevance
            FROM image_fts fts
            INNER JOIN image i ON fts.imageId = i.id
            WHERE image_fts MATCH '$escapedQuery'
            ORDER BY relevance
            LIMIT $limit
        """.trimIndent()
        return searchByRawQuery(SimpleSQLiteQuery(sql))
    }

    // 按标签ID搜索
    @Query("""
        SELECT i.id as imageId, i.uri, i.sha256, i.width, i.height, i.mimeType, 0.0 as relevance
        FROM image i
        WHERE i.id IN (
            SELECT DISTINCT it.imageId FROM image_tag it
            WHERE it.tagId IN (:tagIds)
        )
        ORDER BY i.importTime DESC
        LIMIT :limit
    """)
    suspend fun searchByTagIds(tagIds: List<Long>, limit: Int = 100): List<SearchResult>

    // 缓存操作
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(cache: ImageQueryCacheEntity)

    @Query("SELECT * FROM image_query_cache WHERE sha256 = :sha256 AND expireAt > :now")
    suspend fun getValidCache(sha256: String, now: Long): ImageQueryCacheEntity?

    @Query("DELETE FROM image_query_cache WHERE expireAt < :now")
    suspend fun cleanExpiredCache(now: Long)

    @Query("DELETE FROM image_query_cache")
    suspend fun clearAllCache()

    // FTS 索引维护 - 使用 RawQuery
    @RawQuery
    suspend fun executeRawQuery(query: SupportSQLiteQuery): Int

    suspend fun insertFtsEntry(imageId: Long, content: String) {
        val escapedContent = content.replace("'", "''")
        executeRawQuery(SimpleSQLiteQuery("INSERT INTO image_fts(imageId, content) VALUES ($imageId, '$escapedContent')"))
    }

    suspend fun deleteFtsEntry(imageId: Long) {
        executeRawQuery(SimpleSQLiteQuery("DELETE FROM image_fts WHERE imageId = $imageId"))
    }

    suspend fun updateFtsEntry(imageId: Long, content: String) {
        deleteFtsEntry(imageId)
        insertFtsEntry(imageId, content)
    }

    @Transaction
    suspend fun syncFtsForImage(imageId: Long, searchTokens: String) {
        deleteFtsEntry(imageId)
        insertFtsEntry(imageId, searchTokens)
    }
}

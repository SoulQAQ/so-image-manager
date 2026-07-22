package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.ImageSearchTermEntity
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.SearchDocumentEntity
import cn.soul2.imageai.data.db.entity.SearchGramEntity
import cn.soul2.imageai.data.db.entity.SearchGramOwnerType
import cn.soul2.imageai.data.db.entity.SearchSourceChunkEntity
import cn.soul2.imageai.data.db.entity.SearchTermAliasEntity
import cn.soul2.imageai.data.db.entity.SearchTermEntity
import cn.soul2.imageai.data.db.entity.SearchTextAliasChunkEntity

internal data class SearchGramCandidate(
    val ownerId: Long,
    val sharedGramCount: Int,
)

internal data class SearchMappingCandidate(
    val imageLocalId: Long,
    val termId: Long,
    val normalizedKey: String,
    val fieldMask: Int,
    val ownership: cn.soul2.imageai.data.db.entity.SearchTermOwnership,
    val weight: Double,
    val sortTimeEpochMillis: Long,
    val mediaStoreId: Long,
    val volumeName: String,
)

@Dao
internal interface SearchIndexDao {
    @Query("SELECT * FROM image WHERE local_id = :imageLocalId LIMIT 1")
    fun getImage(imageLocalId: Long): ImageEntity?

    @Upsert
    fun upsertDocument(document: SearchDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTerm(term: SearchTermEntity): Long

    @Query("SELECT * FROM search_term WHERE normalized_key = :normalizedKey LIMIT 1")
    fun getTerm(normalizedKey: String): SearchTermEntity?

    @Upsert
    fun upsertImageTerms(mappings: List<ImageSearchTermEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAliases(aliases: List<SearchTermAliasEntity>): List<Long>

    @Query(
        """
        SELECT * FROM search_term_alias
        WHERE term_id = :termId AND alias_type = :aliasType AND alias_text = :aliasText
        LIMIT 1
        """,
    )
    fun getAlias(
        termId: Long,
        aliasType: cn.soul2.imageai.search.PinyinAliasType,
        aliasText: String,
    ): SearchTermAliasEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSourceChunks(chunks: List<SearchSourceChunkEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTextAliasChunks(chunks: List<SearchTextAliasChunkEntity>): List<Long>

    @Upsert
    fun upsertGrams(grams: List<SearchGramEntity>)

    @Query("SELECT chunk_id FROM search_source_chunk WHERE image_local_id = :imageLocalId")
    fun getSourceChunkIds(imageLocalId: Long): List<Long>

    @Query("SELECT alias_chunk_id FROM search_text_alias_chunk WHERE image_local_id = :imageLocalId")
    fun getTextAliasChunkIds(imageLocalId: Long): List<Long>

    @Query("DELETE FROM search_gram WHERE owner_type = :ownerType AND owner_id = :ownerId")
    fun deleteOwnerGrams(ownerType: SearchGramOwnerType, ownerId: Long): Int

    @Query("DELETE FROM search_document WHERE rowid = :imageLocalId")
    fun deleteDocument(imageLocalId: Long): Int

    @Query("DELETE FROM image_search_term WHERE image_local_id = :imageLocalId")
    fun deleteImageTerms(imageLocalId: Long): Int

    @Query("DELETE FROM search_source_chunk WHERE image_local_id = :imageLocalId")
    fun deleteSourceChunks(imageLocalId: Long): Int

    @Query("DELETE FROM search_text_alias_chunk WHERE image_local_id = :imageLocalId")
    fun deleteTextAliasChunks(imageLocalId: Long): Int

    @Transaction
    fun deleteImageIndex(imageLocalId: Long) {
        getSourceChunkIds(imageLocalId).forEach { id ->
            deleteOwnerGrams(SearchGramOwnerType.SOURCE_CHUNK, id)
        }
        getTextAliasChunkIds(imageLocalId).forEach { id ->
            deleteOwnerGrams(SearchGramOwnerType.TEXT_ALIAS_CHUNK, id)
        }
        deleteDocument(imageLocalId)
        deleteImageTerms(imageLocalId)
        deleteSourceChunks(imageLocalId)
        deleteTextAliasChunks(imageLocalId)
    }

    @Query(
        """
        DELETE FROM search_gram
        WHERE (owner_type = 'TERM' AND NOT EXISTS (
            SELECT 1 FROM search_term WHERE term_id = search_gram.owner_id
        )) OR (owner_type = 'TERM_ALIAS' AND NOT EXISTS (
            SELECT 1 FROM search_term_alias WHERE alias_id = search_gram.owner_id
        )) OR (owner_type = 'SOURCE_CHUNK' AND NOT EXISTS (
            SELECT 1 FROM search_source_chunk WHERE chunk_id = search_gram.owner_id
        )) OR (owner_type = 'TEXT_ALIAS_CHUNK' AND NOT EXISTS (
            SELECT 1 FROM search_text_alias_chunk WHERE alias_chunk_id = search_gram.owner_id
        ))
        """,
    )
    fun deleteOrphanGrams(): Int

    @Query(
        """
        DELETE FROM search_term
        WHERE NOT EXISTS (
            SELECT 1 FROM image_search_term WHERE term_id = search_term.term_id
        )
        """,
    )
    fun deleteUnreferencedTerms(): Int

    @Query(
        """
        SELECT search_document_fts.rowid FROM search_document_fts
        INNER JOIN image AS i ON i.local_id = search_document_fts.rowid
        WHERE search_document_fts MATCH :matchQuery AND i.availability = 'AVAILABLE'
          AND i.partition = 'MAIN'
        ORDER BY i.sort_time_epoch_millis DESC, i.media_store_id DESC,
            i.volume_name DESC, i.local_id DESC
        LIMIT :limit
        """,
    )
    suspend fun findFtsCandidateIds(matchQuery: String, limit: Int): List<Long>

    @Query(
        """
        SELECT m.image_local_id AS imageLocalId, m.term_id AS termId,
            t.normalized_key AS normalizedKey, m.field_mask AS fieldMask,
            m.ownership AS ownership, m.weight AS weight,
            i.sort_time_epoch_millis AS sortTimeEpochMillis,
            i.media_store_id AS mediaStoreId, i.volume_name AS volumeName
        FROM image_search_term AS m
        INNER JOIN search_term AS t ON t.term_id = m.term_id
        INNER JOIN image AS i ON i.local_id = m.image_local_id
        WHERE t.normalized_key = :normalizedKey AND i.availability = 'AVAILABLE'
          AND i.partition = 'MAIN'
        ORDER BY m.weight DESC, i.sort_time_epoch_millis DESC,
            i.media_store_id DESC, i.volume_name DESC, i.local_id DESC
        LIMIT :limit
        """,
    )
    suspend fun findExactMappings(
        normalizedKey: String,
        limit: Int,
    ): List<SearchMappingCandidate>

    @Query(
        """
        SELECT m.image_local_id AS imageLocalId, m.term_id AS termId,
            t.normalized_key AS normalizedKey, m.field_mask AS fieldMask,
            m.ownership AS ownership, m.weight AS weight,
            i.sort_time_epoch_millis AS sortTimeEpochMillis,
            i.media_store_id AS mediaStoreId, i.volume_name AS volumeName
        FROM image_search_term AS m
        INNER JOIN search_term AS t ON t.term_id = m.term_id
        INNER JOIN image AS i ON i.local_id = m.image_local_id
        WHERE m.term_id IN (:termIds) AND i.availability = 'AVAILABLE'
          AND i.partition = 'MAIN'
        ORDER BY m.weight DESC, i.sort_time_epoch_millis DESC,
            i.media_store_id DESC, i.volume_name DESC, i.local_id DESC
        LIMIT :limit
        """,
    )
    suspend fun findMappingsForTerms(
        termIds: List<Long>,
        limit: Int,
    ): List<SearchMappingCandidate>

    @Query("SELECT * FROM search_document WHERE rowid IN (:imageLocalIds)")
    suspend fun getDocuments(imageLocalIds: List<Long>): List<SearchDocumentEntity>

    @Query("SELECT * FROM image WHERE local_id IN (:imageLocalIds)")
    suspend fun getImages(imageLocalIds: List<Long>): List<ImageEntity>

    @Query(
        """
        SELECT i.* FROM image AS i
        LEFT JOIN search_document AS d ON d.rowid = i.local_id
        WHERE i.availability = 'AVAILABLE' AND i.partition = 'MAIN' AND d.rowid IS NULL
        ORDER BY i.sort_time_epoch_millis DESC, i.media_store_id DESC,
            i.volume_name DESC, i.local_id DESC
        LIMIT :limit
        """,
    )
    suspend fun getUnindexedAvailableImages(limit: Int): List<ImageEntity>

    @Query("SELECT * FROM search_term WHERE term_id IN (:termIds)")
    suspend fun getTerms(termIds: List<Long>): List<SearchTermEntity>

    @Query("SELECT * FROM search_term_alias WHERE alias_id IN (:aliasIds)")
    suspend fun getAliases(aliasIds: List<Long>): List<SearchTermAliasEntity>

    @Query("SELECT * FROM search_source_chunk WHERE chunk_id IN (:chunkIds)")
    suspend fun getSourceChunks(chunkIds: List<Long>): List<SearchSourceChunkEntity>

    @Query("SELECT * FROM search_text_alias_chunk WHERE alias_chunk_id IN (:chunkIds)")
    suspend fun getTextAliasChunks(chunkIds: List<Long>): List<SearchTextAliasChunkEntity>

    @Query(
        """
        SELECT owner_id AS ownerId, COUNT(*) AS sharedGramCount
        FROM search_gram
        WHERE owner_type = :ownerType AND gram IN (:grams)
        GROUP BY owner_id
        ORDER BY sharedGramCount DESC, owner_id ASC
        LIMIT :limit
        """,
    )
    suspend fun findGramCandidates(
        ownerType: SearchGramOwnerType,
        grams: List<String>,
        limit: Int,
    ): List<SearchGramCandidate>
}

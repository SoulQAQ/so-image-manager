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
        SELECT rowid FROM search_document_fts
        WHERE search_document_fts MATCH :matchQuery
        ORDER BY rowid ASC
        LIMIT :limit
        """,
    )
    suspend fun findFtsCandidateIds(matchQuery: String, limit: Int): List<Long>

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

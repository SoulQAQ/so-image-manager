package cn.soul2.imageai.data.db.dao

import androidx.room.*
import cn.soul2.imageai.data.db.entity.TagAliasEntity
import cn.soul2.imageai.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Query("SELECT * FROM tag WHERE id = :tagId")
    suspend fun getById(tagId: Long): TagEntity?

    @Query("SELECT * FROM tag WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM tag WHERE name IN (:names)")
    suspend fun getByNames(names: List<String>): List<TagEntity>

    @Query("SELECT * FROM tag ORDER BY name")
    fun getAllFlow(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    suspend fun search(query: String, limit: Int = 50): List<TagEntity>

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("DELETE FROM tag WHERE id = :tagId")
    suspend fun deleteById(tagId: Long)

    @Query("SELECT COUNT(*) FROM tag")
    suspend fun getCount(): Int

    // TagAlias operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: TagAliasEntity)

    @Query("SELECT * FROM tag_alias WHERE alias = :alias")
    suspend fun getAlias(alias: String): TagAliasEntity?

    @Query("SELECT * FROM tag_alias WHERE canonical = :canonical")
    suspend fun getAliasesForCanonical(canonical: String): List<TagAliasEntity>

    @Query("SELECT * FROM tag_alias")
    fun getAllAliasesFlow(): Flow<List<TagAliasEntity>>

    @Query("DELETE FROM tag_alias WHERE alias = :alias")
    suspend fun deleteAlias(alias: String)

    @Transaction
    suspend fun getOrInsertTag(name: String): Long {
        val existing = getByName(name)
        return if (existing != null) {
            existing.id
        } else {
            insert(TagEntity(name = name))
        }
    }

    @Transaction
    suspend fun canonicalizeTag(tagName: String): String {
        val alias = getAlias(tagName)
        return alias?.canonical ?: tagName
    }
}

package cn.soul2.imageai.data.db.dao

import androidx.room.*
import cn.soul2.imageai.data.db.entity.ImageTagEntity
import cn.soul2.imageai.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

data class ImageTagWithDetails(
    val imageId: Long,
    val tagId: Long,
    val tagName: String,
    val confidence: Float,
    val isUserTag: Boolean
)

@Dao
interface ImageTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(imageTag: ImageTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(imageTags: List<ImageTagEntity>)

    @Query("DELETE FROM image_tag WHERE imageId = :imageId AND tagId = :tagId")
    suspend fun delete(imageId: Long, tagId: Long)

    @Query("DELETE FROM image_tag WHERE imageId = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    @Query("DELETE FROM image_tag WHERE imageId = :imageId AND isUserTag = 1")
    suspend fun deleteUserTagsForImage(imageId: Long)

    @Transaction
    @Query("""
        SELECT it.imageId, it.tagId, t.name as tagName, it.confidence, it.isUserTag
        FROM image_tag it
        INNER JOIN tag t ON it.tagId = t.id
        WHERE it.imageId = :imageId
        ORDER BY it.confidence DESC
    """)
    suspend fun getTagsForImage(imageId: Long): List<ImageTagWithDetails>

    @Transaction
    @Query("""
        SELECT it.imageId, it.tagId, t.name as tagName, it.confidence, it.isUserTag
        FROM image_tag it
        INNER JOIN tag t ON it.tagId = t.id
        WHERE it.imageId = :imageId
        ORDER BY it.confidence DESC
    """)
    fun getTagsForImageFlow(imageId: Long): Flow<List<ImageTagWithDetails>>

    @Query("""
        SELECT it.imageId, it.tagId, t.name as tagName, it.confidence, it.isUserTag
        FROM image_tag it
        INNER JOIN tag t ON it.tagId = t.id
        WHERE it.imageId = :imageId AND it.isUserTag = 0
        ORDER BY it.confidence DESC
    """)
    suspend fun getAiTagsForImage(imageId: Long): List<ImageTagWithDetails>

    @Query("""
        SELECT it.imageId, it.tagId, t.name as tagName, it.confidence, it.isUserTag
        FROM image_tag it
        INNER JOIN tag t ON it.tagId = t.id
        WHERE it.imageId = :imageId AND it.isUserTag = 1
        ORDER BY it.confidence DESC
    """)
    suspend fun getUserTagsForImage(imageId: Long): List<ImageTagWithDetails>

    @Query("SELECT imageId FROM image_tag WHERE tagId = :tagId")
    suspend fun getImageIdsByTag(tagId: Long): List<Long>

    @Query("SELECT imageId FROM image_tag WHERE tagId IN (:tagIds)")
    suspend fun getImageIdsByTags(tagIds: List<Long>): List<Long>

    @Query("SELECT COUNT(*) FROM image_tag WHERE tagId = :tagId")
    suspend fun getImageCountForTag(tagId: Long): Int

    @Transaction
    suspend fun setImageTags(imageId: Long, tagIdsWithConfidence: List<Pair<Long, Float>>, isUserTag: Boolean = false) {
        if (isUserTag) {
            deleteUserTagsForImage(imageId)
        }
        val imageTags = tagIdsWithConfidence.map { (tagId, confidence) ->
            ImageTagEntity(imageId, tagId, confidence, isUserTag)
        }
        insertAll(imageTags)
    }
}

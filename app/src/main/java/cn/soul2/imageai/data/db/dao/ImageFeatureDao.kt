package cn.soul2.imageai.data.db.dao

import androidx.room.*
import cn.soul2.imageai.data.db.entity.ImageFeatureEntity

@Dao
interface ImageFeatureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feature: ImageFeatureEntity)

    @Query("SELECT * FROM image_feature WHERE imageId = :imageId")
    suspend fun getByImageId(imageId: Long): ImageFeatureEntity?

    @Query("SELECT * FROM image_feature WHERE phash IS NOT NULL")
    suspend fun getAllWithPhash(): List<ImageFeatureEntity>

    @Query("DELETE FROM image_feature WHERE imageId = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    @Query("UPDATE image_feature SET phash = :phash, updatedAt = :updatedAt WHERE imageId = :imageId")
    suspend fun updatePhash(imageId: Long, phash: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM image_feature WHERE phash IS NOT NULL")
    suspend fun getPhashCount(): Int
}

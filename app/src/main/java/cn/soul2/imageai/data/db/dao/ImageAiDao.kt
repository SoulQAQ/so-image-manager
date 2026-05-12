package cn.soul2.imageai.data.db.dao

import androidx.room.*
import cn.soul2.imageai.data.db.entity.ImageAiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageAiDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(imageAi: ImageAiEntity)

    @Update
    suspend fun update(imageAi: ImageAiEntity)

    @Query("SELECT * FROM image_ai WHERE imageId = :imageId")
    suspend fun getByImageId(imageId: Long): ImageAiEntity?

    @Query("SELECT * FROM image_ai WHERE imageId = :imageId")
    fun getByImageIdFlow(imageId: Long): Flow<ImageAiEntity?>

    @Query("DELETE FROM image_ai WHERE imageId = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    @Query("UPDATE image_ai SET userCaption = :caption WHERE imageId = :imageId")
    suspend fun updateUserCaption(imageId: Long, caption: String)

    @Transaction
    suspend fun upsert(imageAi: ImageAiEntity) {
        val existing = getByImageId(imageAi.imageId)
        if (existing != null) {
            update(imageAi)
        } else {
            insert(imageAi)
        }
    }
}
